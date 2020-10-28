package wemiplugin.intellij

import Files
import Keys
import Path
import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.Key
import wemi.WemiException
import wemi.archetype
import wemi.collections.toMutable
import wemi.configuration
import wemi.dependency
import wemi.dependency.Dependency
import wemi.dependency.Repository
import wemi.dependency.ScopeProvided
import wemi.dependency.TypeChooseByPackaging
import wemi.dependency.resolveDependencyArtifacts
import wemi.key
import wemi.util.FileSet
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.name
import wemi.util.pathWithoutExtension
import wemi.util.plus
import wemiplugin.intellij.utils.Patch
import wemiplugin.intellij.utils.unZipIfNew
import java.io.File
import java.net.URL
import java.util.stream.Collectors

private val LOG = LoggerFactory.getLogger("IntelliJPlugin")

/** All related keys. */
object IntelliJ {

	// TODO(jp): Remove unused elements
	const val PREPARE_SANDBOX_TASK_NAME = "prepareSandbox"
	const val BUILD_SEARCHABLE_OPTIONS_TASK_NAME = "buildSearchableOptions"
	const val SEARCHABLE_OPTIONS_DIR_NAME = "searchableOptions"
	const val JAR_SEARCHABLE_OPTIONS_TASK_NAME = "jarSearchableOptions"
	const val BUILD_PLUGIN_TASK_NAME = "buildPlugin"

	val intellijPluginName by key<String>("Name of the plugin")

	val intellijPluginDependencies by key<List<IntelliJPluginDependency>>("Dependencies on another plugins", emptyList())
	val intellijPluginRepositories by key<List<IntelliJPluginRepository>>("Repositories in which plugin dependencies can be found", listOf(IntelliJPluginsRepo))
	val resolvedIntellijPluginDependencies by key<List<ResolvedIntelliJPluginDependency>>("Resolved dependencies on another plugins")

	val intellijJbrVersion: Key<String?> by key("Explicitly set JBR version to use. null means use default for given IDE.", null as String?)
	val intellijJbrRepository: Key<URL?> by key("URL of repository for downloading JetBrains Java Runtime. null means use default for given version.", null as URL?)

	val intellijIdeDependency by key<IntelliJIDE>("The IntelliJ Platform IDE dependency specification")
	val intellijIdeRepository: Key<URL> by key("Repository to search for IntelliJ Platform IDE dependencies", IntelliJIDERepo)
	val resolvedIntellijIdeDependency by key<ResolvedIntelliJIDE>("IDE dependency to use for compilation and running")

	val instrumentCode by key("Instrument Java classes with nullability assertions and compile forms created by IntelliJ GUI Designer.", true)

	val intellijRobotServerDependency: Key<Pair<Dependency, Repository>> by key("Dependency on robot-server plugin for UI testing")

	val intelliJPluginXmlFiles by key<List<LocatedPath>>("plugin.xml files that should be patched and added to classpath", emptyList())
	val intelliJPluginXmlPatches by key<List<Patch>>("Values to change in plugin.xml. Later added values override previous patches, unless using the ADD mode.", emptyList())
	val intelliJPatchedPluginXmlFiles by key<List<LocatedPath>>("Patched variants of intelliJPluginXmlFiles")

	val preparedIntellijIdeSandbox by key<IntelliJIDESandbox>("Prepare and return a sandbox directory that can be used for running an IDE along with the developed plugin")

	val intellijVerifyPluginStrictness by key("How strict the plugin verification should be", Strictness.ALLOW_WARNINGS)
	val intellijPluginFolder by key<Path>("Prepare and return a directory containing the packaged plugin")
	val intellijPluginSearchableOptions by key<Path?>("A jar with indexing data for plugin's preferences (null if not supported for given IDE version)")
	val intellijPluginArchive by key<Path>("Package $intellijPluginFolder into a zip file, together with $intellijPluginSearchableOptions")

	val intellijPublishPluginToRepository: Key<Unit> by key("Publish plugin distribution on plugins.jetbrains.com")
	val intellijPublishPluginRepository: Key<String> by key("Repository to which the IntelliJ plugins are published to", "https://plugins.jetbrains.com")
	val intellijPublishPluginToken: Key<String> by key("Plugin publishing token")
	val intellijPublishPluginChannels: Key<List<String>> by key("Channels to which the plugin is published", listOf("default"))
}

val JetBrainsAnnotationsDependency = dependency("org.jetbrains", "annotations", "20.1.0", scope = ScopeProvided)
val IntelliJPluginsRepo = IntelliJPluginRepository.Maven(Repository("intellij-plugins-repo", "https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"))
val IntelliJThirdPartyRepo = Repository("intellij-third-party-dependencies", "https://jetbrains.bintray.com/intellij-third-party-dependencies")
val RobotServerDependency = dependency("org.jetbrains.test", "robot-server-plugin", "0.9.35", type = TypeChooseByPackaging)


/** A layer over [wemi.Archetypes.JVMBase] which turns the project into an IntelliJ platform plugin. */
val IntelliJPluginLayer by archetype {

	IntelliJ.intellijPluginName set { Keys.projectName.get() }

	Keys.libraryDependencies add { JetBrainsAnnotationsDependency }

	IntelliJ.preparedIntellijIdeSandbox set { prepareIntelliJIDESandbox() }

	IntelliJ.intellijRobotServerDependency set { RobotServerDependency to IntelliJThirdPartyRepo }

	Keys.runSystemProperties modify DefaultModifySystemProperties
	Keys.runOptions modify DefaultModifyRunOptions
	Keys.javaExecutable set DefaultJavaExecutable

	extend(Configurations.testing) {
		IntelliJ.preparedIntellijIdeSandbox set { prepareIntelliJIDESandbox(testSuffix = "-test") }

		Keys.externalClasspath modify {
			val ec = it.toMutable()
			val ideaDependency = IntelliJ.resolvedIntellijIdeDependency.get()
			ec.add(LocatedPath(ideaDependency.homeDir / "lib/resources.jar"))
			ec.add(LocatedPath(ideaDependency.homeDir / "lib/idea.jar"))
			ec
		}

		Keys.runSystemProperties modify {
			val sp = it.toMutableMap()

			val sandboxDir = IntelliJ.preparedIntellijIdeSandbox.get()

			// since 193 plugins from classpath are loaded before plugins from plugins directory
			// to handle this, use plugin.path property as task's the very first source of plugins
			// we cannot do this for IDEA < 193, as plugins from plugin.path can be loaded twice
			val ideVersion = IntelliJ.resolvedIntellijIdeDependency.get().version
			if (ideVersion.baselineVersion >= 193) {
				sp["plugin.path"] = Files.list(sandboxDir.plugins).collect(Collectors.toList()).joinToString(File.pathSeparator+",") { p -> p.absolutePath }
			}

			sp
		}
	}

	extend(uiTesting) {
		IntelliJ.preparedIntellijIdeSandbox set {
			val (dep, repo) = IntelliJ.intellijRobotServerDependency.get()
			val artifacts = resolveDependencyArtifacts(listOf(dep), listOf(repo), progressListener)
					?: throw WemiException("Failed to obtain robot-server dependency", false)
			val artifactZip = artifacts.singleOrNull()
					?: throw WemiException("Failed to obtain robot-server dependency - single artifact expected, but got $artifacts", false)
			val robotFolder = artifactZip.parent / artifactZip.name.pathWithoutExtension()
			unZipIfNew(artifactZip, robotFolder)
			prepareIntelliJIDESandbox(testSuffix = "-uiTest", extraPluginDirectories = *arrayOf(robotFolder))
		}
	}

	extend(Configurations.publishing) {
		// TODO(jp): Implement
		/*
        def prepareSandboxTask = project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask
        def jarSearchableOptionsTask = project.tasks.findByName(JAR_SEARCHABLE_OPTIONS_TASK_NAME) as Jar
        Zip zip = project.tasks.create(BUILD_PLUGIN_TASK_NAME, Zip).with {
            description = "Bundles the project as a distribution."
            from { "${prepareSandboxTask.getDestinationDir()}/${prepareSandboxTask.getPluginName()}" }
            into { prepareSandboxTask.getPluginName() }

            def searchableOptionsJar = VersionNumber.parse(project.gradle.gradleVersion) >= VersionNumber.parse("5.1")
                    ? jarSearchableOptionsTask.archiveFile : { jarSearchableOptionsTask.archivePath }
            from(searchableOptionsJar) { into 'lib' }
            dependsOn(JAR_SEARCHABLE_OPTIONS_TASK_NAME)
            if (VersionNumber.parse(project.gradle.gradleVersion) >= VersionNumber.parse("5.1")) {
                archiveBaseName.set(project.provider { prepareSandboxTask.getPluginName() })
            } else {
                conventionMapping('baseName', { prepareSandboxTask.getPluginName() })
            }
            it
        }
        Configuration archivesConfiguration = project.configurations.getByName(Dependency.ARCHIVES_CONFIGURATION)
        if (archivesConfiguration) {
            ArchivePublishArtifact zipArtifact = new ArchivePublishArtifact(zip)
            archivesConfiguration.getArtifacts().add(zipArtifact)
            project.getExtensions().getByType(DefaultArtifactPublicationSet.class).addCandidate(zipArtifact)
            project.getComponents().add(new IntelliJPluginLibrary())
        }
		 */
	}

	extend(Configurations.compiling) {
		Keys.externalClasspath modify { cp ->
			val mcp = cp.toMutable()
			for (path in IntelliJ.resolvedIntellijIdeDependency.get().jarFiles) {
				mcp.add(LocatedPath(path))
			}
			for (dependency in IntelliJ.resolvedIntellijPluginDependencies.get()) {
				for (it in dependency.classpath()) {
					mcp.add(LocatedPath(it))
				}
			}
			mcp
		}
	}

	IntelliJ.intellijIdeDependency set { IntelliJIDE.External() }
	IntelliJ.resolvedIntellijIdeDependency set defaultIdeDependency(false)

	extend(Configurations.retrievingSources) {
		IntelliJ.resolvedIntellijIdeDependency set defaultIdeDependency(true)
		Keys.externalClasspath addAll {
			IntelliJ.resolvedIntellijIdeDependency.get().sources.map { LocatedPath(it) }
		}
	}

	IntelliJ.intellijPluginFolder set DefaultIntelliJPluginFolder
	IntelliJ.intellijPluginSearchableOptions set DefaultIntelliJSearchableOptions
	IntelliJ.intellijPluginArchive set DefaultIntelliJPluginArchive
	IntelliJ.resolvedIntellijPluginDependencies set DefaultResolvedIntellijPluginDependencies

	IntelliJ.intelliJPluginXmlPatches addAll  DefaultIntelliJPluginXmlPatches
	IntelliJ.intelliJPatchedPluginXmlFiles set PatchedPluginXmlFiles
	IntelliJ.intellijPublishPluginToRepository set DefaultIntellijPublishPluginToRepository

	// Add the patched xml files into resources
	Keys.resources modify { it + IntelliJ.intelliJPatchedPluginXmlFiles.get().fold(null as FileSet?) { left, next -> left + FileSet(next) } }
}

val uiTesting by configuration("IDE UI Testing", Configurations.testing) {}
