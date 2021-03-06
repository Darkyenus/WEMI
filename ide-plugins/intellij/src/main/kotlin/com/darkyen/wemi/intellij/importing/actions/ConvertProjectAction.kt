package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.WemiBuildDirectoryName
import com.darkyen.wemi.intellij.WemiBuildFileExtensions
import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.importing.actions.ImportProjectAction.Companion.importUnlinkedProject
import com.darkyen.wemi.intellij.importing.allWemiModules
import com.darkyen.wemi.intellij.importing.defaultWemiRootPathFor
import com.darkyen.wemi.intellij.importing.hasNonWemiLinkedModules
import com.darkyen.wemi.intellij.importing.isImportable
import com.darkyen.wemi.intellij.importing.reinstallWemiLauncher
import com.darkyen.wemi.intellij.showBalloon
import com.darkyen.wemi.intellij.util.toPath
import com.intellij.jarRepository.RepositoryLibraryType
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleSourceOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.vfs.LocalFileSystem
import icons.WemiIcons
import org.jetbrains.idea.maven.utils.library.RepositoryLibraryProperties
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.IOException
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action to convert foreign project to Wemi and import it.
 *
 * This is usually the first point at which user meets this plugin.
 */
class ConvertProjectAction : AnAction("Convert to Wemi Project",
        "Convert project from different or no build system to Wemi", WemiIcons.ACTION) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && isImportable(project) && hasNonWemiLinkedModules(project)
    }

    private fun Path.resolveNew(name:String, extension:String):Path {
        for (i in -1..100) {
            val resolved = when (i) {
                -1 -> this.resolve("$name.$extension")
                0 -> this.resolve("$name-converted.$extension")
                else -> this.resolve("$name-converted-$i.$extension")
            }

            if (!Files.exists(resolved)) {
                return resolved
            }
        }

        throw IOException("There is too many build scripts in $this")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !(isImportable(project) && hasNonWemiLinkedModules(project))) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val (projectBasePath, wemiLauncher) = reinstallWemiLauncher(defaultWemiRootPathFor(project) ?: return, "Could not convert to Wemi", project)
                ?: return


        val buildDirectory = projectBasePath.resolve(WemiBuildDirectoryName)
        try {
            Files.createDirectories(buildDirectory)
        } catch (e:Exception) {
            LOG.error("Failed to create build directory in $buildDirectory", e)
            WemiNotificationGroup.showBalloon(project,
                    "Could not convert to Wemi",
                    "Failed to create \"build\" directory",
                    NotificationType.ERROR)
            return
        }

        val buildScriptFile = buildDirectory.resolveNew("build", WemiBuildFileExtensions.first())
        try {
            createBuildScript(buildScriptFile, projectBasePath, project)
        } catch (e:Exception) {
            LOG.error("Failed to generate build script to $buildScriptFile", e)
            WemiNotificationGroup.showBalloon(project,
                    "Could not convert to Wemi",
                    "Failed to generate \"build/build.kt\" script",
                    NotificationType.ERROR)
            return
        }

        LocalFileSystem.getInstance().refreshAndFindFileByPath(projectBasePath.toAbsolutePath().toString())?.refresh(true, true)

        importUnlinkedProject(project, wemiLauncher)
    }

    private fun createBuildScript(file: Path, projectRoot:Path, project:Project) {
        val modules = allWemiModules(project, wemiModules = false).toList()

        val projectName = project.name
        val projectNameIdentifier = projectName.toIdentifier(PROJECT_PREFIX)

        val group = try {
            InetAddress.getLocalHost().hostName?.split('.')?.reversed()?.joinToString(".")
        } catch (e:Exception) { null } ?: "local.myself"
        val version = "1.0"

        val buildScript = StringBuilder()
        buildScript.append("@file:Suppress(\"unused\")\n")
        buildScript.append("import wemi.*\n")
        buildScript.append("import wemi.dependency.*\n")
        buildScript.append("import wemi.util.*\n")

        if (modules.isEmpty()) {
            buildScript.append("\n")
            buildScript.append("val ").append(projectNameIdentifier).append(" by project {\n")
            buildScript.append("\n")
            buildScript.append("\tprojectGroup set { \"").append(group.escapeStringLiteral()).append("\" }\n")
            buildScript.append("\tprojectName set { \"").append(projectName.escapeStringLiteral()).append("\" }\n")
            buildScript.append("\tprojectVersion set { \"").append(version.escapeStringLiteral()).append("\" }\n")
            buildScript.append("\n")
            buildScript.append("}\n")
        } else {
            for (module in modules) {
                buildScript.appendModuleInfo(projectRoot, module,
                        group,
                        if (modules.size == 1) projectName else module.name,
                        if (modules.size == 1) projectNameIdentifier else module.name.toIdentifier(MODULE_PREFIX),
                        version)
            }
        }

        Files.newBufferedWriter(file, StandardCharsets.UTF_8).use {
            it.append(buildScript)
        }
    }

    private fun StringBuilder.appendModuleInfo(projectRoot:Path, module: Module, group:String, name:String, nameIdentifier: CharSequence, version:String) {
        val moduleRoot = module.rootManager.contentRoots.firstOrNull()?.toPath() ?: projectRoot

        append("\n")
        append("val ").append(nameIdentifier).append(" by project")

        if (moduleRoot != projectRoot) {
            append("(path(\"")
            if (moduleRoot.startsWith(projectRoot)) {
                append(projectRoot.relativize(moduleRoot).toString().escapeStringLiteral())
            } else {
                append(moduleRoot.toString().escapeStringLiteral())
            }
            append("\"))")
        }

        append(" {\n")
        append("\n")
        append("\tprojectGroup set { \"").append(group.escapeStringLiteral()).append("\" }\n")
        append("\tprojectName set { \"").append(name.escapeStringLiteral()).append("\" }\n")
        append("\tprojectVersion set { \"").append(version.escapeStringLiteral()).append("\" }\n")
        append("\n")
        // Content roots
        val sourceFileSets = ArrayList<CharSequence>()
        val testSourceFileSets = ArrayList<CharSequence>()
        val resourceFileSets = ArrayList<CharSequence>()
        val testResourceFileSets = ArrayList<CharSequence>()

        val moduleRootManager = ModuleRootManager.getInstance(module)
        for (entry in moduleRootManager.contentEntries) {
            for (sourceFolder in entry.sourceFolders) {
                val rootList = when (sourceFolder.rootType) {
                    JavaSourceRootType.SOURCE -> sourceFileSets
                    JavaSourceRootType.TEST_SOURCE -> testSourceFileSets
                    JavaResourceRootType.RESOURCE -> resourceFileSets
                    JavaResourceRootType.TEST_RESOURCE -> testResourceFileSets
                    else -> null
                } ?: continue

                val path = sourceFolder.file?.toPath()?.toAbsolutePath() ?: continue
                if (path.startsWith(projectRoot)) {
                    rootList.add("FileSet(projectRoot.get() / \"${moduleRoot.relativize(path).toString().escapeStringLiteral()}\")")
                } else {
                    rootList.add("FileSet(path(\"${path.toString().escapeStringLiteral()}\"))")
                }
            }
        }
        if (sourceFileSets.isNotEmpty()) {
            append("\tsources set { ")
            sourceFileSets.joinTo(this, " + ")
            append(" }\n\n")
        }
        if (resourceFileSets.isNotEmpty()) {
            append("\tresources set { ")
            resourceFileSets.joinTo(this, " + ")
            append(" }\n\n")
        }
        if (testSourceFileSets.isNotEmpty()) {
            append("\ttestSources modify { it")
            for (set in testSourceFileSets) {
                append(" + ").append(set)
            }
            append(" }\n")
        }
        if (testResourceFileSets.isNotEmpty()) {
            append("\ttestResources modify { it")
            for (set in testResourceFileSets) {
                append(" + ").append(set)
            }
            append(" }\n")
        }

        // Module, library and jar dependencies
        val model = moduleRootManager.modifiableModel
        try {
            val moduleDependencies = HashMap<DependencyScope, ArrayList<Module>>()
            val jarDependencies = HashMap<DependencyScope, ArrayList<Path>>()
            val libraryDependencies = HashMap<DependencyScope, ArrayList<MavenLib>>()

            forEntries@for (entry in model.orderEntries) {
                when (entry) {
                    is JdkOrderEntry -> {}
                    is ModuleSourceOrderEntry -> {}
                    is ModuleOrderEntry -> {
                        val m = entry.module ?: continue@forEntries
                        moduleDependencies.getOrPut(entry.scope) {ArrayList()}.add(m)
                    }
                    is LibraryOrderEntry -> {
                        val library = entry.library ?: continue@forEntries
                        if (library is LibraryEx) {
                            if (library.kind == RepositoryLibraryType.REPOSITORY_LIBRARY_KIND) {
                                val properties = library.properties as? RepositoryLibraryProperties
                                if (properties != null) {
                                    libraryDependencies.getOrPut(entry.scope) {ArrayList()}.add(MavenLib(properties.groupId, properties.artifactId, properties.version))
                                    continue@forEntries
                                }
                            }
                        }


                        for (virtualFile in library.getFiles(OrderRootType.CLASSES)) {
                            val path = virtualFile.toPath()
                            if (path == null) {
                                LOG.warn("Not translating '$virtualFile', can't convert to Path")
                                continue
                            }
                            jarDependencies.getOrPut(entry.scope) {ArrayList()}.add(path)
                        }
                    }
                    else -> {
                        LOG.info("Unknown module order entry: ${entry.javaClass}")
                    }
                }
            }

            if (DependencyScope.COMPILE in moduleDependencies
                    || DependencyScope.COMPILE in jarDependencies
                    || DependencyScope.COMPILE in libraryDependencies) {
                appendProjectDependencies("\t", moduleDependencies[DependencyScope.COMPILE], "ScopeCompile")
                appendLibraryDependencies("\t", libraryDependencies[DependencyScope.COMPILE], "ScopeCompile")
                appendJarDependencies("\t", projectRoot, moduleRoot, jarDependencies[DependencyScope.COMPILE], "ScopeCompile")
                append("\n")
            }

            if (DependencyScope.TEST in moduleDependencies
                    || DependencyScope.TEST in jarDependencies
                    || DependencyScope.TEST in libraryDependencies) {
                appendProjectDependencies("\t", moduleDependencies[DependencyScope.TEST], "ScopeTest")
                appendLibraryDependencies("\t", libraryDependencies[DependencyScope.TEST], "ScopeTest")
                appendJarDependencies("\t", projectRoot, moduleRoot, jarDependencies[DependencyScope.TEST], "ScopeTest")
            }

            if (DependencyScope.PROVIDED in moduleDependencies
                    || DependencyScope.PROVIDED in jarDependencies
                    || DependencyScope.PROVIDED in libraryDependencies) {
                appendProjectDependencies("\t", moduleDependencies[DependencyScope.PROVIDED], "ScopeProvided")
                appendLibraryDependencies("\t", libraryDependencies[DependencyScope.PROVIDED], "ScopeProvided")
                appendJarDependencies("\t", projectRoot, moduleRoot, jarDependencies[DependencyScope.PROVIDED], "ScopeProvided")
            }

            if (DependencyScope.RUNTIME in moduleDependencies
                    || DependencyScope.RUNTIME in jarDependencies
                    || DependencyScope.RUNTIME in libraryDependencies) {
                appendProjectDependencies("\t", moduleDependencies[DependencyScope.RUNTIME], "ScopeRuntime")
                appendLibraryDependencies("\t", libraryDependencies[DependencyScope.RUNTIME], "ScopeRuntime")
                appendJarDependencies("\t", projectRoot, moduleRoot, jarDependencies[DependencyScope.RUNTIME], "ScopeRuntime")
            }
        } finally {
            model.dispose()
        }
        append("}\n")
    }

    private fun StringBuilder.appendProjectDependencies(prefix:String, modules:List<Module>?, scope:String) {
        for (moduleDependency in modules?:return) {
            append(prefix)
            append("projectDependencies add { ProjectDependency(")
            append(moduleDependency.name.toIdentifier(MODULE_PREFIX).escapeStringLiteral())
            append(", scope = ")
            append(scope)
            append(") }\n")
        }
    }

    class MavenLib(val group:String, val name:String, val version:String)
    private fun StringBuilder.appendLibraryDependencies(prefix:String, libraries:List<MavenLib>?, scope:String) {
        for (library in libraries?:return) {
            append(prefix)
            append("libraryDependencies add { dependency(\"")
            append(library.group.escapeStringLiteral()).append("\", \"")
            append(library.name.escapeStringLiteral()).append("\", \"")
            append(library.version.escapeStringLiteral()).append("\", scope = ")
            append(scope).append(") }\n")
        }
    }

    private fun StringBuilder.appendJarDependencies(prefix:String, projectRoot:Path, moduleRoot:Path, jars:List<Path>?, scope:String) {
        for (jar in jars?:return) {
            append(prefix)
            if (scope == "ScopeCompile") {
                append("unmanagedDependencies add { LocatedPath(")
            } else {
                append("externalClasspath add { ScopedLocatedPath(LocatedPath(")
            }
            if (jar.startsWith(projectRoot)) {
                append("path(\"")
                append(projectRoot.relativize(jar).toString().escapeStringLiteral())
                append("\")")
            } else if (jar.startsWith(moduleRoot)) {
                append("projectRoot.get() / \"")
                append(moduleRoot.relativize(jar).toString().escapeStringLiteral())
                append("\"")
            } else {
                append("path(\"")
                append(jar.toString().escapeStringLiteral())
                append("\")")
            }
            if (scope == "ScopeCompile") {
                append(") }\n")
            } else {
                append("), scope = ").append(scope).append(") }\n")
            }
        }
    }

    private val MODULE_PREFIX = "Module"
    private val PROJECT_PREFIX = "Project"

    private fun String.toIdentifier(prefix:String):CharSequence {
        val result = StringBuilder(prefix.length + this.length)
        result.append(prefix)
        for (c in this) {
            result.append(if (c.isJavaIdentifierPart()) c else '_')
        }
        return result
    }

    private fun CharSequence.escapeStringLiteral():CharSequence {
        var result:StringBuilder? = null
        for (i in indices) {
            val c = this[i]
            val escapeTo: String? = when {
                c == '"' -> "\\\""
                c == '\t' -> "\\t"
                c == '\n' -> "\\n"
                c == '\r' -> "\\r"
                c == '\\' -> "\\\\"
                c == '$' -> "\\$"
                c < ' ' -> "\\u" + c.toInt().toString(16).padStart(4, '0')
                else -> null
            }
            if (escapeTo != null) {
                if (result == null) {
                    result = StringBuilder(this.length * 2)
                    result.append(this, 0, i)
                }
                result.append(escapeTo)
            } else result?.append(c)
        }
        return result ?: this
    }

    companion object {
        private val LOG = Logger.getInstance(ConvertProjectAction::class.java)
    }
}