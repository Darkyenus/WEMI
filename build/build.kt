import wemi.Archetypes
import wemi.KeyDefaults.inProjectDependencies
import wemi.Keys.cacheDirectory
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinCompilerVersion
import wemi.createProject
import wemi.dependency
import wemi.dependency.JCenter
import wemi.dependency.Jitpack
import wemi.dependency.ProjectDependency
import wemi.expiresWith
import wemi.key
import wemi.publish.InfoNode
import wemi.test.JUnitPlatformLauncher
import wemi.util.FileSet
import wemi.util.ensureEmptyDirectory
import wemi.util.name
import java.time.Instant
import java.time.format.DateTimeFormatter

val CompilerProjects = listOf(
        createKotlinCompilerProject("1.1.61"),
        createKotlinCompilerProject("1.2.71"),
        createKotlinCompilerProject("1.3.20"),
        createKotlinCompilerProject("1.3.41")
)

const val WemiGroup = "com.darkyen.wemi"

val distributionArchive by key<Path>("Create a distribution archive for Wemi")

val wemi:Project by project(Archetypes.AggregateJVMProject) {

    projectGroup set { WemiGroup }
    projectName set { "wemi-core" }
    projectVersion set { System.getenv("BUILT_WEMI_VERSION") ?: "dev-${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}" }

    projectDependencies add { ProjectDependency(core, true) }
    projectDependencies addAll { CompilerProjects.map { ProjectDependency(it, true) } }
    projectDependencies add { ProjectDependency(dokkaInterfaceImplementation, true) }

    distributionArchive set {
        val distFolder = Files.createDirectories(cacheDirectory.get() / "-distribution-archive")
        distFolder.ensureEmptyDirectory()
        expiresWith(distFolder)
        val distSourceFolder = Files.createDirectories(distFolder / "sources")

        Files.copy(archive.get()!!, distFolder / "wemi.jar")
        Files.copy(using(archivingSources) { archive.get()!! }, distSourceFolder / "wemi.jar")
        for (path in using(running) { externalClasspath.get() }) {
            Files.copy(path.file, distFolder / path.file.name)
        }

        distFolder
    }

    publishMetadata modify { metadata ->
        setupSharedPublishMetadata(
                metadata,
                "Wemi",
                "Wonders Expeditiously, Mundane Instantly - Simple yet powerful build system - core jar",
                "2017"
        )
    }

    extend(archivingSources) {
        archive set {
            using(Configurations.archiving) {
                AssemblyOperation().use { assemblyOperation ->
                    // Load data
                    for (file in sources.getLocatedPaths()) {
                        assemblyOperation.addSource(file, true, extractJarEntries = false)
                    }

                    inProjectDependencies(true) {
                        for (file in sources.getLocatedPaths()) {
                            assemblyOperation.addSource(file, true, extractJarEntries = false)
                        }
                    }

                    val outputFile = archiveOutputFile.get()
                    assemblyOperation.assembly(
                            NoConflictStrategyChooser,
                            DefaultRenameFunction,
                            DefaultAssemblyMapFilter,
                            outputFile,
                            NoPrependData,
                            compress = true)

                    expiresWith(outputFile)
                    outputFile
                }
            }
        }
    }
    // end remove
}

/** Core functionality */
val core:Project by project {

    repositories add { Jitpack }

    val JLineVersion = "3.3.0"
    libraryDependencies set { setOf(
            latestKotlinDependency("stdlib"),
            latestKotlinDependency("reflect"),
            dependency("org.slf4j", "slf4j-api", "1.7.25"),
            dependency("com.darkyen", "tproll", "v1.3.0"),
            dependency("com.darkyen", "DaveWebb", "v1.2"),
            dependency("com.github.EsotericSoftware", "jsonbeans", "0.9"),
            dependency("org.jline", "jline-terminal", JLineVersion),
            dependency("org.jline", "jline-terminal-jansi", JLineVersion),
            dependency("org.jline", "jline-reader", JLineVersion)
        ) }

    /* Used ONLY in wemi.test.forked.TestLauncher */
    libraryDependencies add { Dependency(JUnitPlatformLauncher, scope=ScopeProvided) }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }
    compilerOptions[KotlinCompilerFlags.incremental] = { true }
    compilerOptions[JavaCompilerFlags.customFlags] = { it + "-Xlint:all" }

    libraryDependencies add { Dependency(JUnitAPI, scope=ScopeTest) }
    libraryDependencies add { Dependency(JUnitEngine, scope=ScopeTest) }
}

fun latestKotlinDependency(name:String):Dependency {
    val latestKotlinVersion = KotlinCompilerVersion.values().last().string
    return dependency("org.jetbrains.kotlin", "kotlin-$name", latestKotlinVersion)
}

fun setupSharedPublishMetadata(metadata:InfoNode, name:String, description:String, inceptionYear:String): InfoNode {
    return metadata.apply {
        child("name", name)
        child("description", description)
        child("url", "https://github.com/Darkyenus/WEMI")

        child("inceptionYear", inceptionYear)

        child("scm") {
            newChild("connection", "scm:git:https://github.com/Darkyenus/WEMI")
            newChild("developerConnection", "scm:git:https://github.com/Darkyenus/WEMI")
            newChild("tag", "HEAD")
            newChild("url", "https://github.com/Darkyenus/WEMI")
        }

        child("issueManagement") {
            child("system", "GitHub Issues")
            child("url", "https://github.com/Darkyenus/WEMI/issues")
        }
    }
}

fun createKotlinCompilerProject(version:String):Project {
    val projectName = StringBuilder("kotlinc_")
    for (c in version) {
        if (c.isJavaIdentifierPart()) {
            projectName.append(c)
        } else {
            projectName.append('_')
        }
    }

    return createProject(projectName.toString(), path("src/main-kotlinc/$version"), Archetypes.JavaKotlinProject) {
        sources set { FileSet(projectRoot.get() / "src") }

        compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }

        projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }
        // Disable default kotlin stdlib
        libraryDependencies set { setOf(
                dependency("org.jetbrains.kotlin", "kotlin-compiler", version, scope=ScopeProvided)
        ) }
    }
}

// Separate project, because Dokka is distributed through a fat-jar, which also includes kotlin stdlib.
// This caused compile problems because it leads to two stdlibs on the classpath for the main project.
val dokkaInterfaceImplementation by project(path("src/main-dokka")) {
    sources set { FileSet(projectRoot.get() / "src") }

    compilerOptions[KotlinCompilerFlags.customFlags] = { it + "-Xskip-runtime-version-check" }

    libraryDependencies set { emptySet() } // Disable default kotlin stdlib

    projectDependencies add { ProjectDependency(core, false, scope=ScopeProvided) }

    /* Used only in wemi.document.DokkaInterface */
    libraryDependencies add { dependency("org.jetbrains.dokka", "dokka-fatjar", "0.9.15", scope=ScopeProvided) }

    repositories set { setOf(JCenter) }
}