package wemiplugin.intellij

import Files
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.Configurations
import wemi.Value
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.dependency
import wemi.dependency.Repository
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.dependency.internal.OS_FAMILY_WINDOWS
import wemi.dependency.resolveDependencyArtifacts
import wemi.util.div
import wemi.util.executable
import wemi.util.exists
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.pathWithoutExtension
import wemi.util.toSafeFileName
import wemiplugin.intellij.dependency.BuiltinPluginsRegistry
import wemiplugin.intellij.dependency.IdeaExtraDependency
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.unZipIfNew
import java.net.URL
import java.nio.file.Path
import java.util.stream.Collectors

private val LOG = LoggerFactory.getLogger("ResolveIDE")

val IntelliJIDERepo = URL("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository")

/** A specification of an IntelliJ Platform IDE dependency. */
sealed class IntelliJIDE {
	/**
	 * A dependency on a locally installed IDE.
	 * @param path to the IDE's home directory
	 * @param sources folders or jars of IDE's sources
	 */
	data class Local(val path:Path, val sources:List<Path>) : IntelliJIDE()

	/**
	 * A dependency on an IDE from a remote repository.
	 * @param version of the IDE, see [version documentation](https://www.jetbrains.org/intellij/sdk/docs/basics/getting_started/plugin_compatibility.html)
	 * @param type of IDE distribution (IC, IU, CL, PY, PC, RD or JPS)
	 */
	data class External(val type:String = "IC", val version:String = "LATEST-EAP-SNAPSHOT") : IntelliJIDE()
}

class ResolvedIntelliJIDE(
		val buildNumber:String,
		val homeDir: Path,
		val sources:List<Path>,
		val pluginsRegistry : BuiltinPluginsRegistry,
		val extraDependencies:Collection<IdeaExtraDependency>,
		val jarFiles:Collection<Path>) {
	override fun toString(): String {
		return "IdeaDependency(buildNumber='$buildNumber', classes=$homeDir, sources=$sources)"
	}
}

fun defaultIdeDependency(downloadSources:Boolean): Value<ResolvedIntelliJIDE> = {
	val withKotlin = using(Configurations.running) {
		!Keys.libraryDependencies.get().any { it.dependencyId.group == "org.jetbrains.kotlin" && isKotlinRuntime(it.dependencyId.name) }
	}

	val result = when (val dependency = IntelliJ.intellijIdeDependency.get()) {
		is IntelliJIDE.Local -> resolveLocalIDE(dependency.path, dependency.sources, withKotlin)
		is IntelliJIDE.External -> {
			resolveRemoteIDE(IntelliJ.intellijIdeRepository.get(), dependency.version, dependency.type, downloadSources, IntelliJ.extraDependencies.get(), withKotlin, progressListener)
		}
	}

	// TODO(jp): Figure out what extraDependencies do and decide whether this should stay here
	if (result.extraDependencies.isNotEmpty()) {
		LOG.info("Note: {} extra dependencies ({}) should be applied manually", result.buildNumber, result.extraDependencies)
	}

	result
}

/** Resolve IDE from remote repository. */
fun resolveRemoteIDE(repoUrl: URL, version: String, type: String, sources_: Boolean, extraDependencies: List<String>, withKotlin: Boolean, progressListener: ActivityListener?): ResolvedIntelliJIDE {
	val releaseType = Utils.releaseType(version)

	var dependencyGroup = "com.jetbrains.intellij.idea"
	var dependencyName = "ideaIC"
	var sources = sources_
	if (type == "IU") {
		dependencyName = "ideaIU"
	} else if (type == "CL") {
		dependencyGroup = "com.jetbrains.intellij.clion"
		dependencyName = "clion"
	} else if (type == "PY" || type == "PC") {
		dependencyGroup = "com.jetbrains.intellij.pycharm"
		dependencyName = "pycharm$type"
	} else if (type == "RD") {
		dependencyGroup = "com.jetbrains.intellij.rider"
		dependencyName = "riderRD"
		if (sources && releaseType == "snapshots") {
			LOG.warn("IDE sources are not available for Rider SNAPSHOTS")
			sources = false
		}
	}

	val repository = Repository("idea-${repoUrl.host}-${repoUrl.path.toSafeFileName()}-$releaseType", repoUrl / releaseType)
	val dependency = dependency(dependencyGroup, dependencyName, version, type = "zip")
	val ideZipFile = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener)
			?.single()
			?: throw WemiException("Failed to resolve IDE dependency $dependency from $repository")
	LOG.debug("Resolved IDE zip: {}", ideZipFile)

	val homeDir = unzipDependencyFile(getZipCacheDirectory(ideZipFile, type), ideZipFile, type, version.endsWith("-SNAPSHOT"))
	LOG.info("IDE dependency cache directory: {}", homeDir)
	val sourceDirs = if (sources) resolveSources(version, repository, progressListener) else null
	val resolvedExtraDependencies = resolveExtraDependencies(version, extraDependencies, repository, progressListener)
	return createDependency(type, homeDir, sourceDirs ?: emptyList(), resolvedExtraDependencies, withKotlin)
}

/**
 * Resolve IDE from local installation.
 */
fun resolveLocalIDE(localPath: Path, localPathSources: List<Path>, withKotlin: Boolean): ResolvedIntelliJIDE {
	LOG.debug("Adding local IDE dependency")
	val homeDir = if (localPath.name.pathHasExtension("app")) {
		localPath.resolve("Contents")
	} else localPath

	if (!homeDir.exists() || !homeDir.isDirectory()) {
		throw WemiException("Specified localPath '$localPath' doesn't exist or is not a directory", false)
	}
	return createDependency(null, homeDir, localPathSources, emptyList(), withKotlin)
}

private val mainDependencies = arrayOf("ideaIC", "ideaIU", "riderRD", "riderRS")

fun isKotlinRuntime(name:String):Boolean {
	return "kotlin-runtime" == name || "kotlin-reflect" == name || name.startsWith("kotlin-stdlib")
}

private val ALLOWED_JPS_JAR_NAMES = arrayOf("jps-builders.jar", "jps-model.jar", "util.jar")

private fun createDependency(type: String?, homeDir: Path, sourceDirs: List<Path>,
                             extraDependencies: Collection<IdeaExtraDependency>, withKotlin: Boolean): ResolvedIntelliJIDE {
	val lib = homeDir / "lib"
	var jars = Utils.collectJars(lib).filter {
		val libName = it.name
		(withKotlin || !isKotlinRuntime(libName.pathWithoutExtension())) && libName != "junit.jar" && libName != "annotations.jar"
	}
	val pluginsRegistry:BuiltinPluginsRegistry
	if (type == "JPS") {
		// What is JPS? I don't know. Probably this?
		// https://intellij-support.jetbrains.com/hc/en-us/community/posts/360008114139-IDEA-independent-building-
		jars = jars.filter { it.name in ALLOWED_JPS_JAR_NAMES }
		pluginsRegistry = BuiltinPluginsRegistry(homeDir)
	} else {
		pluginsRegistry = BuiltinPluginsRegistry.fromDirectory(homeDir / "plugins")
	}

	val buildTxt = Utils.run {
		if (OS_FAMILY == OS_FAMILY_MAC) {
			val file = homeDir.resolve("Resources/build.txt")
			if (file.exists()) {
				return@run file
			}
		}
		homeDir.resolve("build.txt")
	}
	val buildNumber = String(Files.readAllBytes(buildTxt), Charsets.UTF_8).trim()

	return ResolvedIntelliJIDE(buildNumber, homeDir, sourceDirs, pluginsRegistry, extraDependencies, jars.collect(Collectors.toList()))
}

private fun resolveSources(version: String, repository: Repository, progressListener: ActivityListener?): List<Path>? {
	val dependency = dependency("com.jetbrains.intellij.idea", "ideaIC", version, classifier = "sources", type = "jar")
	val artifacts = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener) ?: return null
	LOG.debug("IDE sources: {}", artifacts)
	return artifacts
}

private fun getZipCacheDirectory(zipFile: Path, type: String): Path {
	if (type == "RD" && OS_FAMILY == OS_FAMILY_WINDOWS) {
		// TODO(jp): Not sure why is this needed
		return WemiCacheFolder
	}
	return zipFile.parent
}

private fun resolveExtraDependencies(version: String, extraDependencies: List<String>, repository: Repository, progressListener: ActivityListener?):Collection<IdeaExtraDependency> {
	if (extraDependencies.isEmpty()) {
		return emptyList()
	}
	LOG.info("Configuring IDE extra dependencies {}", extraDependencies)
	val mainInExtraDeps = extraDependencies.filter { dep -> mainDependencies.any { it == dep } }
	if (mainInExtraDeps.isNotEmpty()) {
		throw WemiException("The items $mainInExtraDeps cannot be used as extra dependencies", false)
	}
	val resolvedExtraDependencies = ArrayList<IdeaExtraDependency>()
	for (name in extraDependencies) {
		val dependencyFile = resolveExtraDependency(version, name, repository, progressListener) ?: continue
		val extraDependency = IdeaExtraDependency(name, dependencyFile)
		LOG.debug("IDE extra dependency $name in $dependencyFile files: ${extraDependency.jarFiles}")
		resolvedExtraDependencies.add(extraDependency)
	}
	return resolvedExtraDependencies
}

private fun resolveExtraDependency(version: String, name: String, repository: Repository, progressListener: ActivityListener?) : Path? {
	val dependency = dependency("com.jetbrains.intellij.idea", name, version)
	val files = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener)
	if (files == null) {
		LOG.warn("Cannot resolve IDE extra dependency {}", name)
		return null
	} else if (files.size == 1) {
		val depFile = files.first()
		if (depFile.name.endsWith(".zip")) {
			val cacheDirectory = getZipCacheDirectory(depFile, "IC")
			LOG.debug("IDE extra dependency {}: {}", name, cacheDirectory)
			return unzipDependencyFile(cacheDirectory, depFile, "IC", version.endsWith("-SNAPSHOT"))
		} else {
			LOG.debug("IDE extra dependency {}: {}", name, depFile)
			return depFile
		}
	} else {
		// TODO(jp): This is stupid
		LOG.warn("Cannot attach IDE extra dependency {}. Found files: {}", name, files)
		return null
	}
}

private fun unzipDependencyFile(cacheDirectory: Path, zipFile: Path, type: String, checkVersionChange: Boolean): Path {
	val targetDir = cacheDirectory / zipFile.name.pathWithoutExtension()
	if (unZipIfNew(zipFile, targetDir, existenceIsEnough = !checkVersionChange)) {
		if (type == "RD" && OS_FAMILY != OS_FAMILY_WINDOWS) {
			setExecutable(targetDir, "lib/ReSharperHost/dupfinder.sh")
			setExecutable(targetDir, "lib/ReSharperHost/inspectcode.sh")
			setExecutable(targetDir, "lib/ReSharperHost/JetBrains.ReSharper.Host.sh")
			setExecutable(targetDir, "lib/ReSharperHost/runtime.sh")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/env-wrapper")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen-gdb.py")
			setExecutable(targetDir, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen")
			setExecutable(targetDir, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen-gdb.py")
		}
	}
	return targetDir
}

private fun setExecutable(parent: Path, child: String) {
	val file = parent / child
	LOG.debug("Resetting executable permissions for {}", file)
	try {
		file.executable = true
	} catch (ignored:Exception) {}
}