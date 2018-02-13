@file:Suppress("MemberVisibilityCanBePrivate")

package wemi

import com.darkyen.tproll.util.StringBuilderWriter
import org.slf4j.LoggerFactory
import wemi.Configurations.archiving
import wemi.Configurations.assembling
import wemi.Configurations.compilingJava
import wemi.Configurations.compilingKotlin
import wemi.Configurations.publishing
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.boot.WemiBuildScript
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import wemi.dependency.Repository.M2.Companion.JavadocClassifier
import wemi.dependency.Repository.M2.Companion.SourcesClassifier
import wemi.dependency.Repository.M2.Companion.joinClassifiers
import wemi.publish.InfoNode
import wemi.test.TEST_LAUNCHER_MAIN_CLASS
import wemi.test.TestParameters
import wemi.test.TestReport
import wemi.test.handleProcessForTesting
import wemi.util.*
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import javax.tools.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

/**
 * Contains default values bound to keys.
 *
 * This includes implementations of most tasks.
 */
object KeyDefaults {

    val SourceRootsJavaKotlin: BoundKeyValue<WSet<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = WMutableSet<Path>()
        for (base in bases) {
            roots.add(base / "kotlin")
            roots.add(base / "java")
        }
        roots
    }

    val ResourceRoots: BoundKeyValue<WSet<Path>> = {
        val bases = Keys.sourceBases.get()
        val roots = WMutableSet<Path>()
        for (base in bases) {
            roots.add(base / "resources")
        }
        roots
    }

    val SourceFiles: BoundKeyValue<WList<LocatedFile>> = {
        val roots = Keys.sourceRoots.get()
        val extensions = Keys.sourceExtensions.get()
        val result = WMutableList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result) { it.name.pathHasExtension(extensions) }
        }

        result
    }

    val ResourceFiles: BoundKeyValue<WList<LocatedFile>> = {
        val roots = Keys.resourceRoots.get()
        val result = WMutableList<LocatedFile>()

        for (root in roots) {
            constructLocatedFiles(root, result)
        }

        result
    }

    /**
     * Create value for [Keys.libraryDependencyProjectMapper] that appends given classifier to sources.
     */
    fun classifierAppendingLibraryDependencyProjectMapper(appendClassifier:String):(Dependency) -> Dependency = {
        (projectId, exclusions): Dependency ->
            val classifier = joinClassifiers(projectId.attribute(Repository.M2.Classifier), appendClassifier)!!
            val sourcesProjectId = projectId.copy(attributes = projectId.attributes + (Repository.M2.Classifier to classifier))
            Dependency(sourcesProjectId, exclusions)
        }


    val ResolvedLibraryDependencies: BoundKeyValue<Partial<Map<DependencyId, ResolvedDependency>>> = {
        val repositories = Keys.repositoryChain.get()
        val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
        val complete = DependencyResolver.resolve(resolved, Keys.libraryDependencies.get(), repositories, Keys.libraryDependencyProjectMapper.get())
        Partial(resolved, complete)
    }

    private val ResolvedProjectDependencies_CircularDependencyProtection = CycleChecker<Scope>()
    val ResolvedProjectDependencies: BoundKeyValue<WList<LocatedFile>> = {
        ResolvedProjectDependencies_CircularDependencyProtection.block(this, failure = {
            //TODO Show cycle
            throw WemiException("Cyclic dependencies in projectDependencies are not allowed", showStacktrace = false)
        }, action = {
            val result = WMutableList<LocatedFile>()
            val projectDependencies = Keys.projectDependencies.get()

            for (projectDependency in projectDependencies) {
                // Enter a different scope
                projectDependency.project.evaluate(*projectDependency.configurations) {
                    ExternalClasspath_LOG.debug("Resolving project dependency on {}", this)
                    result.addAll(Keys.externalClasspath.get())
                    result.addAll(Keys.internalClasspath.get())
                }
            }
            result
        })
    }

    private val ExternalClasspath_LOG = LoggerFactory.getLogger("ProjectDependencyResolution")
    val ExternalClasspath: BoundKeyValue<WList<LocatedFile>> = {
        val result = WMutableList<LocatedFile>()

        val resolved = Keys.resolvedLibraryDependencies.get()
        if (!resolved.complete) {
            throw WemiException("Failed to resolve all artifacts\n${resolved.value.prettyPrint(null)}", showStacktrace = false)
        }
        for ((_, resolvedDependency) in resolved.value) {
            result.add(LocatedFile(resolvedDependency.artifact ?: continue))
        }

        val projectDependencies = Keys.resolvedProjectDependencies.get()
        result.addAll(projectDependencies)

        val unmanaged = Keys.unmanagedDependencies.get()
        result.addAll(unmanaged)

        result
    }

    val InternalClasspath: BoundKeyValue<WList<LocatedFile>> = {
        val compiled = Keys.compile.get()
        val resources = Keys.resourceFiles.get()

        val classpath = WMutableList<LocatedFile>(resources.size + 128)
        constructLocatedFiles(compiled, classpath)
        classpath.addAll(resources)

        classpath
    }

    val Clean: BoundKeyValue<Int> = {
        val folders = arrayOf(
                Keys.outputClassesDirectory.get(),
                Keys.outputSourcesDirectory.get(),
                Keys.outputHeadersDirectory.get()
        )
        var clearedCount = 0
        for (folder in folders) {
            if (folder.exists()) {
                folder.deleteRecursively()
                clearedCount += 1
            }
        }

        for ((_, project) in AllProjects) {
            clearedCount += project.projectScope.cleanCache(true)
        }

        clearedCount
    }

    fun outputClassesDirectory(tag: String): BoundKeyValue<Path> = {
        Keys.buildDirectory.get() / "cache/$tag-${Keys.projectName.get().toSafeFileName('_')}"
    }

    private val CompileLOG = LoggerFactory.getLogger("Compile")

    val CompileJava: BoundKeyValue<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val javaSources = using(compilingJava) { Keys.sourceFiles.get() }
            val javaSourceRoots = mutableSetOf<Path>()
            for ((file, _, root) in javaSources) {
                javaSourceRoots.add((root ?: file).toAbsolutePath())
            }

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })

            // Compile Java
            if (javaSources.isNotEmpty()) {
                val compiler = using(compilingJava) { Keys.javaCompiler.get() }
                val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)
                val writerSb = StringBuilder()
                val writer = StringBuilderWriter(writerSb)
                val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }

                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                sourcesOut.ensureEmptyDirectory()
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                headersOut.ensureEmptyDirectory()

                val pathSeparator = System.getProperty("path.separator", ":")
                val compilerOptions = ArrayList<String>()
                compilerFlags.use(JavaCompilerFlags.customFlags) {
                    compilerOptions.addAll(it)
                }
                compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it.version)
                }
                compilerFlags.use(JavaCompilerFlags.targetVersion) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it.version)
                }
                compilerOptions.add("-classpath")
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
                compilerOptions.add(classpathString)
                compilerOptions.add("-sourcepath")
                compilerOptions.add(javaSourceRoots.joinToString(pathSeparator) { it.absolutePath })
                compilerOptions.add("-d")
                compilerOptions.add(output.absolutePath)
                compilerOptions.add("-s")
                compilerOptions.add(sourcesOut.absolutePath)
                compilerOptions.add("-h")
                compilerOptions.add(headersOut.absolutePath)

                val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.map { it.file.toFile() })

                val success = compiler.getTask(
                        writer,
                        fileManager,
                        null,
                        compilerOptions,
                        null,
                        javaFiles
                ).call()

                if (!writerSb.isBlank()) {
                    val format = if (writerSb.contains('\n')) "\n{}" else "{}"
                    if (success) {
                        CompileLOG.info(format, writerSb)
                    } else {
                        CompileLOG.warn(format, writerSb)
                    }
                }

                if (!success) {
                    throw WemiException("Java compilation failed", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Java compiler")
            }

            output
        }
    }

    val CompileJavaKotlin: BoundKeyValue<Path> = {
        using(Configurations.compiling) {
            val output = Keys.outputClassesDirectory.get()
            output.ensureEmptyDirectory()

            val javaSources = using(compilingJava) { Keys.sourceFiles.get() }
            val javaSourceRoots = mutableSetOf<Path>()
            for ((file, _, root) in javaSources) {
                javaSourceRoots.add((root ?: file).toAbsolutePath())
            }
            val kotlinSources = using(compilingKotlin) { Keys.sourceFiles.get() }

            val externalClasspath = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry })

            // Compile Kotlin
            if (kotlinSources.isNotEmpty()) {
                val sources: MutableList<Path> = mutableListOf()
                for ((file, _, _) in kotlinSources) {
                    sources.add(file)
                }
                sources.addAll(javaSourceRoots)

                val compiler = using(compilingKotlin) { Keys.kotlinCompiler.get() }
                val compilerFlags = using(compilingKotlin) { Keys.compilerOptions.get() }

                val compileResult = compiler.compileJVM(javaSources + kotlinSources, externalClasspath, output, compilerFlags, CompileLOG, null)
                if (compileResult != KotlinCompiler.CompileExitStatus.OK) {
                    throw WemiException("Kotlin compilation failed: " + compileResult, showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Kotlin compiler")
            }

            // Compile Java
            if (javaSources.isNotEmpty()) {
                val compiler = using(compilingJava) { Keys.javaCompiler.get() }
                val fileManager = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)
                val writerSb = StringBuilder()
                val writer = StringBuilderWriter(writerSb)
                val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }

                val sourcesOut = using(compilingJava) { Keys.outputSourcesDirectory.get() }
                sourcesOut.ensureEmptyDirectory()
                val headersOut = using(compilingJava) { Keys.outputHeadersDirectory.get() }
                headersOut.ensureEmptyDirectory()

                val pathSeparator = System.getProperty("path.separator", ":")
                val compilerOptions = ArrayList<String>()
                compilerFlags.use(JavaCompilerFlags.customFlags) {
                    compilerOptions.addAll(it)
                }
                compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                    compilerOptions.add("-source")
                    compilerOptions.add(it.version)
                }
                compilerFlags.use(JavaCompilerFlags.targetVersion) {
                    compilerOptions.add("-target")
                    compilerOptions.add(it.version)
                }
                compilerOptions.add("-classpath")
                val classpathString = externalClasspath.joinToString(pathSeparator) { it.absolutePath }
                if (kotlinSources.isNotEmpty()) {
                    compilerOptions.add(classpathString + pathSeparator + output.absolutePath)
                } else {
                    compilerOptions.add(classpathString)
                }
                compilerOptions.add("-sourcepath")
                compilerOptions.add(javaSourceRoots.joinToString(pathSeparator) { it.absolutePath })
                compilerOptions.add("-d")
                compilerOptions.add(output.absolutePath)
                compilerOptions.add("-s")
                compilerOptions.add(sourcesOut.absolutePath)
                compilerOptions.add("-h")
                compilerOptions.add(headersOut.absolutePath)

                val javaFiles = fileManager.getJavaFileObjectsFromFiles(javaSources.map { it.file.toFile() })

                val success = compiler.getTask(
                        writer,
                        fileManager,
                        null,
                        compilerOptions,
                        null,
                        javaFiles
                ).call()

                if (!writerSb.isBlank()) {
                    val format = if (writerSb.contains('\n')) "\n{}" else "{}"
                    if (success) {
                        CompileLOG.info(format, writerSb)
                    } else {
                        CompileLOG.warn(format, writerSb)
                    }
                }

                if (!success) {
                    throw WemiException("Java compilation failed", showStacktrace = false)
                }

                compilerFlags.warnAboutUnusedFlags("Java compiler")
            }

            output
        }
    }

    val RunOptions: BoundKeyValue<WList<String>> = {
        val options = WMutableList<String>()
        options.add("-ea")
        val debugPort = System.getenv("WEMI_RUN_DEBUG_PORT")?.toIntOrNull()
        if (debugPort != null) {
            options.add("-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=$debugPort")
        }
        options
    }

    val Run: BoundKeyValue<Int> = {
        using(Configurations.running) {
            val javaExecutable = Keys.javaExecutable.get()
            val classpathEntries = LinkedHashSet<Path>()
            for (locatedFile in Keys.externalClasspath.get()) {
                classpathEntries.add(locatedFile.classpathEntry)
            }
            for (locatedFile in Keys.internalClasspath.get()) {
                classpathEntries.add(locatedFile.classpathEntry)
            }
            val directory = Keys.runDirectory.get()
            val mainClass = Keys.mainClass.get()
            val options = Keys.runOptions.get()
            val arguments = Keys.runArguments.get()

            val processBuilder = wemi.run.prepareJavaProcess(javaExecutable, directory, classpathEntries,
                    mainClass, options, arguments)

            // Separate process output from Wemi output
            println()
            val process = processBuilder.start()
            val result = process.waitFor()
            println()

            result
        }
    }

    val RunMain: BoundKeyValue<Int> = {
        val mainClass = Keys.input.get().read("main", "Main class to start", ClassNameValidator)
                ?: throw WemiException("Main class not specified", showStacktrace = false)

        using({
            Keys.mainClass.set { mainClass }
        }) {
            Keys.run.get()
        }
    }

    val TestParameters: BoundKeyValue<TestParameters> = {
        val testParameters = wemi.test.TestParameters()
        testParameters.filter.classNamePatterns.include("^.*Tests?$")
        testParameters.select.classpathRoots.add(Keys.outputClassesDirectory.get().absolutePath)
        testParameters
    }

    val Test: BoundKeyValue<TestReport> = {
        using(Configurations.testing) {
            val javaExecutable = Keys.javaExecutable.get()
            val directory = Keys.runDirectory.get()
            val options = Keys.runOptions.get()

            val externalClasspath = Keys.externalClasspath.get().map { it.classpathEntry }.distinct()
            val internalClasspath = Keys.internalClasspath.get().map { it.classpathEntry }.distinct()
            val wemiClasspathEntry = WemiBuildScript!!.wemiLauncherJar

            val classpathEntries = ArrayList<Path>(internalClasspath.size + externalClasspath.size + 1)
            classpathEntries.addAll(internalClasspath)
            classpathEntries.addAll(externalClasspath)
            classpathEntries.add(wemiClasspathEntry)

            val processBuilder = wemi.run.prepareJavaProcess(
                    javaExecutable, directory, classpathEntries,
                    TEST_LAUNCHER_MAIN_CLASS, options, emptyList())

            val testParameters = Keys.testParameters.get()

            val report = handleProcessForTesting(processBuilder, testParameters)
                    ?: throw WemiException("Test execution failed, see logs for more information", showStacktrace = false)

            report
        }
    }

    val Archive: BoundKeyValue<Path> = {
        using(archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    /**
     * Special version of [Archive] that includes classpath contributions from [Keys.projectDependencies].
     */
    val ArchivePublishing: BoundKeyValue<Path> = {
        using(archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }

                for (file in Keys.resolvedProjectDependencies.get()) {
                    assemblyOperation.addSource(file, false)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    val ArchiveSources: BoundKeyValue<Path> = {
        using(archiving) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in using(compilingJava) { Keys.sourceFiles.get() }) {
                    assemblyOperation.addSource(file, true)
                }
                for (file in using(compilingKotlin) { Keys.sourceFiles.get() }) {
                    assemblyOperation.addSource(file, true)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    val ArchiveJavadocOptions: BoundKeyValue<WList<String>> = {
        using(archiving) {
            val options = WMutableList<String>()

            val compilerFlags = using(compilingJava) { Keys.compilerOptions.get() }
            var version = ""
            compilerFlags.use(JavaCompilerFlags.sourceVersion) {
                options.add("-source")
                options.add(it.version)
                version = it.version
            }

            val linkURL = when (version) {
                "1.1", "1", //These versions don't have API uploaded, so fall back to 1.5
                "1.2", "2",
                "1.3", "3",
                "1.4", "4",
                "1.5", "5" ->
                        "https://docs.oracle.com/javase/1.5.0/docs/api/"
                "1.6", "6" ->
                        "https://docs.oracle.com/javase/6/docs/api/"
                "1.7", "7" ->
                    "https://docs.oracle.com/javase/7/docs/api/"
                "1.8", "8" ->
                    "https://docs.oracle.com/javase/8/docs/api/"
                "1.9", "9" ->
                    "https://docs.oracle.com/javase/9/docs/api/"
                else ->
                    // Default is 9 because that is newest
                    "https://docs.oracle.com/javase/9/docs/api/"
            }
            options.add("-link")
            options.add(linkURL)

            val pathSeparator = System.getProperty("path.separator", ":")
            options.add("-classpath")
            val classpathString = LinkedHashSet(Keys.externalClasspath.get().map { it.classpathEntry }).joinToString(pathSeparator) { it.absolutePath }
            options.add(classpathString)

            options
        }
    }

    private val ARCHIVE_JAVADOC_LOG = LoggerFactory.getLogger("ArchiveJavadoc")
    val ArchiveJavadoc: BoundKeyValue<Path> = {
        using(archiving) {
            val sourceFiles = using(compilingJava){ Keys.sourceFiles.get() }

            val diagnosticListener:DiagnosticListener<JavaFileObject> = DiagnosticListener { diagnostic ->
                ARCHIVE_JAVADOC_LOG.debug("{}", diagnostic)
            }

            val documentationTool = ToolProvider.getSystemDocumentationTool()!!
            val fileManager = documentationTool.getStandardFileManager(diagnosticListener, Locale.ROOT, Charsets.UTF_8)
            fileManager.setLocation(StandardLocation.SOURCE_PATH, using(Configurations.compilingJava) { Keys.sourceRoots.get() }.map { it.toFile() })
            val javadocOutput = Keys.cacheDirectory.get() / "javadoc-${Keys.projectName.get().toSafeFileName('_')}"
            javadocOutput.ensureEmptyDirectory()
            fileManager.setLocation(DocumentationTool.Location.DOCUMENTATION_OUTPUT, listOf(javadocOutput.toFile()))

            // Try to specify doclet path explicitly
            // This is because when java is run from "jre" part of JDK install, "tools.jar" is not in the classpath
            val javaHome = Keys.javaHome.get()
            val toolsJar = javaHome.resolve("lib/tools.jar").takeIf { it.exists() }
                ?: (if (javaHome.name == "jre") javaHome.resolve("../lib/tools.jar").takeIf { it.exists() } else null)

            if (toolsJar != null) {
                fileManager.setLocation(DocumentationTool.Location.DOCLET_PATH, listOf(toolsJar.toFile()))
            }

            val options = Keys.archiveJavadocOptions.get()

            val docTask = documentationTool.getTask(LineReadingWriter { line ->
                ARCHIVE_JAVADOC_LOG.warn("{}", line)
            }, fileManager,
                    diagnosticListener,
                    null,
                    options,
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles.map { it.file.toFile() }))

            docTask.setLocale(Locale.ROOT)
            val result = docTask.call()

            if (!result) {
                throw WemiException("Failed to package javadoc", showStacktrace = false)
            }

            val locatedFiles = ArrayList<LocatedFile>()
            constructLocatedFiles(javadocOutput, locatedFiles)

            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in locatedFiles) {
                    assemblyOperation.addSource(file, true, false)
                }

                val outputFile = Keys.archiveOutputFile.get()
                assemblyOperation.assembly(
                        NoConflictStrategyChooser,
                        DefaultRenameFunction,
                        outputFile,
                        NoPrependData,
                        compress = true)

                outputFile
            }
        }
    }

    private val PUBLISH_MODEL_LOG = LoggerFactory.getLogger("PublishModelM2")
    /**
     * Creates Maven2 compatible pom.xml-like [InfoNode].
     * 
     * [Configurations.publishing] scope is applied at [Keys.publish], so this does not handle it.
     */
    val PublishModelM2: BoundKeyValue<InfoNode> = {
        /*
        Commented out code is intended as example when customizing own publishMetadata pom.xml.
        
        Full pom.xml specification can be obtained here:
        https://maven.apache.org/ref/3.5.2/maven-model/maven.html
        
        Mandatory fields are described here:
        https://maven.apache.org/project-faq.html
        */
        InfoNode("project") {
            newChild("modelVersion", "4.0.0")
            attribute("xmlns", "http://maven.apache.org/POM/4.0.0")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute("xsi:schemaLocation", "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd")

            newChild("groupId", Keys.projectGroup.get()) // MANDATORY
            newChild("artifactId", Keys.projectName.get()) // MANDATORY
            newChild("version", Keys.projectVersion.get()) // MANDATORY

            newChild("packaging", "jar") // MANDATORY (defaults to jar)

            newChild("name") // MANDATORY, filled by user
            newChild("description") // MANDATORY, filled by user
            newChild("url") // MANDATORY, filled by user
            @Suppress("DEPRECATION")
            newChild("inceptionYear", Keys.startYear.get().toString())

            /* Example
            newChild("organization") {
                newChild("name", "ACME Codes Inc.")
                newChild("url", "https://acme.codes.example.com")
            }
            */

            newChild("licenses") // Mandatory, filled by user
            /*{ Example
                newChild("license") {
                    newChild("name", "MyLicense")
                    newChild("url", "https://my.license.example.com")
                    newChild("distribution", "repo") // or manual, if user has to download this *dependency* manually
                    newChild("comments") // Optional comments
                }
            }*/

            /* Example
            newChild("developers") { // People directly developing the project
                newChild("developer") {
                    newChild("id", "Darkyenus") // SCM handle (for example Github login)
                    newChild("name", "Jan Polák") // Full name
                    newChild("email", "darkyenus@example.com")
                    newChild("url", "https://example.com")
                    newChild("organization", "ACME Codes Inc.")
                    newChild("organizationUrl", "https://acme.codes.example.com")
                    newChild("roles") {
                        newChild("role", "Developer")
                        newChild("role", "Creator of Wemi")
                    }
                    newChild("timezone", "Europe/Prague") // Or number, such as +1 or -14
                    newChild("properties") {
                        newChild("IRC", "Darkyenus") // Any key-value pairs
                    }
                }
            }
            newChild("contributors") { // People contributing to the project, but without SCM access
                newChild("contributor") {
                    // Same as "developer", but without "id"
                }
            }
            */

            /* Example
            newChild("scm") {
                // See https://maven.apache.org/scm/scms-overview.html
                newChild("connection", "scm:git:https://github.com/Darkyenus/WEMI")
                newChild("developerConnection", "scm:git:https://github.com/Darkyenus/WEMI")
                newChild("tag", "HEAD")
                newChild("url", "https://github.com/Darkyenus/WEMI")
            }
            */

            /* Example
            newChild("issueManagement") {
                newChild("system", "GitHub Issues")
                newChild("url", "https://github.com/Darkyenus/WEMI/issues")
            }
            */

            newChild("dependencies") {
                for (dependency in Keys.libraryDependencies.get()) {
                    newChild("dependency") {
                        newChild("groupId", dependency.dependencyId.group)
                        newChild("artifactId", dependency.dependencyId.name)
                        newChild("version", dependency.dependencyId.version)
                        dependency.dependencyId.attribute(Repository.M2.Type)?.let {
                            newChild("type", it)
                        }
                        dependency.dependencyId.attribute(Repository.M2.Classifier)?.let {
                            newChild("classifier", it)
                        }
                        dependency.dependencyId.attribute(Repository.M2.Scope)?.let {
                            newChild("scope", it)
                        }
                        newChild("exclusions") {
                            for (exclusion in dependency.exclusions) {
                                if (exclusion in DefaultExclusions) {
                                    continue
                                }
                                // Check if Maven compatible (only group and name is set)
                                val mavenCompatible = exclusion.group != "*"
                                        && exclusion.name != "*"
                                        && exclusion.version == "*"
                                        && exclusion.attributes.isEmpty()

                                if (mavenCompatible) {
                                    newChild("exclusion") {
                                        newChild("artifactId", exclusion.name)
                                        newChild("groupId", exclusion.group)
                                    }
                                } else {
                                    PUBLISH_MODEL_LOG.warn("Exclusion {} on {} is not supported by pom.xml and will be omitted", exclusion, dependency.dependencyId)
                                }
                            }
                        }
                        dependency.dependencyId.attribute(Repository.M2.Optional)?.let {
                            newChild("optional", it)
                        }
                    }
                }
            }

            newChild("repositories") {
                for (repository in Keys.repositories.get()) {
                    if (repository == MavenCentral) {
                        // Added by default
                        continue
                    }
                    if (repository !is Repository.M2) {
                        PUBLISH_MODEL_LOG.warn("Omitting repository {}, only M2 repositories are supported", repository)
                        continue
                    }
                    if (repository.local) {
                        PUBLISH_MODEL_LOG.warn("Omitting repository {}, it is local")
                        continue
                    }

                    newChild("repository") {
                        /* Extra info we don't collect
                        newChild("releases") {
                            newChild("enabled", "true") // Use for downloading releases?
                            newChild("updatePolicy") // always, daily (default), interval:<minutes>, never
                            newChild("checksumPolicy") // ignore, fail, warn (default)
                        }
                        newChild("snapshots") {
                            // Like "releases"
                        }
                        */
                        newChild("id", repository.name)
                        //newChild("name", "Human readable name")
                        newChild("url", repository.url.toString())
                    }
                }
            }
        }
    }

    val PublishM2: BoundKeyValue<URI> = {
        using(publishing) {
            val repository = Keys.publishRepository.get()

            val artifact = Keys.archive.get()
            val sourceArtifact = using(Configurations.archivingSources) { Keys.archive.get() }
            val docsArtifact = using(Configurations.archivingDocs) { Keys.archive.get() }

            val metadata = Keys.publishMetadata.get()
            val classifier = Keys.publishClassifier.get()

            val artifacts = ArrayList<Pair<Path, String?>>()
            if (artifact != null) {
                artifacts.add(artifact to classifier)
            }
            if (sourceArtifact != null) {
                artifacts.add(sourceArtifact to joinClassifiers(classifier, SourcesClassifier))
            }
            if (docsArtifact != null) {
                artifacts.add(docsArtifact to joinClassifiers(classifier, JavadocClassifier))
            }

            repository.publish(metadata, artifacts)
        }
    }

    val Assembly: BoundKeyValue<Path> = {
        using(assembling) {
            AssemblyOperation().use { assemblyOperation ->
                // Load data
                for (file in Keys.internalClasspath.get()) {
                    assemblyOperation.addSource(file, true)
                }
                for (file in Keys.externalClasspath.get()) {
                    assemblyOperation.addSource(file, false)
                }

                val outputFile = Keys.assemblyOutputFile.get()
                assemblyOperation.assembly(Keys.assemblyMergeStrategy.get(),
                        Keys.assemblyRenameFunction.get(),
                        outputFile,
                        Keys.assemblyPrependData.get(),
                        compress = true)

                outputFile
            }
        }
    }
}