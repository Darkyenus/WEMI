package wemiplugin.intellij

import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.ValueModifier
import wemi.collections.toMutable
import wemi.keys.javaHome
import wemi.keys.mainClass
import wemi.keys.runEnvironment
import wemi.keys.runOptions
import wemi.run.runForegroundProcess
import wemi.util.SystemInfo
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.getPluginIds
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("RunIDE")

private val PREFIXES = mapOf(
		"IU" to null,
		"IC" to "Idea",
		"RM" to "Ruby",
		"PY" to "Python",
		"PC" to "PyCharmCore",
		"PE" to "PyCharmEdu",
		"PS" to "PhpStorm",
		"WS" to "WebStorm",
		"OC" to "AppCode",
		"CL" to "CLion",
		"DB" to "DataGrip",
		"AI" to "AndroidStudio",
		"GO" to "GoLand",
		"RD" to "Rider",
		"RS" to "Rider",
		"RDCPPP" to "Rider")

val DefaultModifySystemProperties : ValueModifier<Map<String, String>> = {
	val systemProperties = it.toMutableMap()
	val sandboxDir = IntelliJ.intellijIdeSandbox.get()
	val ideVersion = IntelliJ.intellijResolvedIdeDependency.get().version

	val configDirectory = sandboxDir.config
	val pluginsDirectory = sandboxDir.plugins
	val systemDirectory = sandboxDir.system
	val requiredPluginIds = getPluginIds()
	/*
	 * Enables auto-reload of dynamic plugins. Dynamic plugins will be reloaded automatically when their JARs are
	 * modified. This allows a much faster development cycle by avoiding a full restart of the development instance
	 * after code changes. Enabled by default in 2020.2 and higher.
	 */
	val autoReloadPlugins:Boolean = ideVersion.baselineVersion >= 202

	systemProperties.putAll(Utils.getIdeaSystemProperties(configDirectory, systemDirectory, pluginsDirectory, requiredPluginIds))
	if (SystemInfo.IS_MAC_OS) {
		systemProperties.putIfAbsent("idea.smooth.progress", "false")
		systemProperties.putIfAbsent("apple.laf.useScreenMenuBar", "true")
		systemProperties.putIfAbsent("apple.awt.fileDialogForDirectories", "true")
	} else if (SystemInfo.IS_UNIX) {
		systemProperties.putIfAbsent("sun.awt.disablegrab", "true")
	}
	systemProperties.putIfAbsent("idea.classpath.index.enabled", "false")
	systemProperties.putIfAbsent("idea.is.internal", "true")
	systemProperties.putIfAbsent("idea.auto.reload.plugins", autoReloadPlugins.toString())

	if (!systemProperties.containsKey("idea.platform.prefix")) {
		val abbreviation = ideVersion.productCode
		val prefix = PREFIXES[abbreviation]
		if (prefix != null && prefix.isNotBlank()) {
			systemProperties["idea.platform.prefix"] = prefix
		}
	}

	systemProperties
}

val DefaultModifyRunOptions : ValueModifier<List<String>> = {
	val runOptions = it.toMutable()
	if (!runOptions.any { o -> o.startsWith("-Xmx") }) {
		runOptions.add(/*"-Xmx512m" this is the gradle default, but it is not enough*/ "-Xmx1024m")
	}
	if (!runOptions.any { o -> o.startsWith("-Xms") }) {
		runOptions.add("-Xms256m")
	}
	val bootJar = IntelliJ.intellijResolvedIdeDependency.get().homeDir / "lib/boot.jar"
	if (bootJar.exists()) runOptions.add("-Xbootclasspath/a:${bootJar.absolutePath}")
	runOptions
}

fun EvalScope.runIde(extraArguments: List<String> = emptyList()): Int {
	val ideDependency = IntelliJ.intellijResolvedIdeDependency.get()
	val ideDirectory = ideDependency.homeDir
	val javaHome = javaHome.get()
	val executable = javaHome.javaExecutable
	val mainClass = mainClass.get()
	val environment = runEnvironment.get()

	val classpath = ArrayList<Path>()
	// Apparently the IDE needs to have the tools.jar on classpath
	val toolsJar = javaHome.toolsJar
	if (toolsJar != null) {
		classpath.add(toolsJar)
	}

	val version = ideDependency.version.baselineVersion
	if (version > 203 || (version == 203 && ideDependency.version.build > 0)) {
		classpath.add(ideDirectory / "lib/bootstrap.jar")
		classpath.add(ideDirectory / "lib/util.jar")
		classpath.add(ideDirectory / "lib/jdom.jar")
		classpath.add(ideDirectory / "lib/log4j.jar")
		classpath.add(ideDirectory / "lib/jna.jar")
	} else {
		classpath.add(ideDirectory / "lib/bootstrap.jar")
		classpath.add(ideDirectory / "lib/extensions.jar")
		classpath.add(ideDirectory / "lib/util.jar")
		classpath.add(ideDirectory / "lib/jdom.jar")
		classpath.add(ideDirectory / "lib/log4j.jar")
		classpath.add(ideDirectory / "lib/jna.jar")
		classpath.add(ideDirectory / "lib/trove4j.jar")
	}

	val processBuilder = wemi.run.prepareJavaProcess(
			executable, ideDirectory / "bin", classpath,
			mainClass, runOptions.get(), extraArguments, environment)

	return runForegroundProcess(processBuilder)
}