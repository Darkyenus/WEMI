package wemiplugin.intellij

import Files
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import wemi.EvalScope
import wemi.keys.cacheDirectory
import wemi.util.deleteRecursively
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.exists
import wemi.util.linkOrCopyRecursively
import wemi.util.name
import wemiplugin.intellij.utils.namedElements
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.saveXml
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.xml.parsers.DocumentBuilderFactory

private val LOG = LoggerFactory.getLogger("PrepareSandbox")

@Suppress("MemberVisibilityCanBePrivate")
class IntelliJIDESandbox(val base:Path, val config:Path, val plugins:Path, val system:Path) {
	override fun toString(): String {
		return "IntelliJIDESandbox(base=$base, config=${base.relativize(config)}, plugins=${base.relativize(plugins)}, system=${base.relativize(system)})"
	}
}

fun EvalScope.prepareIntelliJIDESandbox(sandboxDir: Path = cacheDirectory.get() / "idea-sandbox", testSuffix:String = "", vararg extraPluginDirectories: Path): IntelliJIDESandbox {
	val destinationDir = sandboxDir / "plugins$testSuffix"

	// A file which contains names of files added by us to clear.
	// We do not want to delete everything, because user might have added custom plugins from marketplace etc.
	val customContentManifest = destinationDir / "wemiContentManifest.txt"
	if (customContentManifest.exists()) {
		for (line in Files.readAllLines(customContentManifest)) {
			val dir = destinationDir / line.trim()
			dir.deleteRecursively()
		}
	} else {
		destinationDir.ensureEmptyDirectory()
	}

	val customContentNames = ArrayList<String>()

	val pluginJar = IntelliJ.intellijPluginFolder.get()
	pluginJar.linkOrCopyRecursively(destinationDir / pluginJar.name)
	customContentNames.add(pluginJar.name)

	for (dependency in IntelliJ.intellijResolvedPluginDependencies.get()) {
		if (dependency.isBuiltin) {
			continue
		}
		dependency.artifact.linkOrCopyRecursively(destinationDir / dependency.artifact.name)
		customContentNames.add(dependency.artifact.name)
	}
	for (path in extraPluginDirectories) {
		path.linkOrCopyRecursively(destinationDir / path.name)
		customContentNames.add(path.name)
	}

	Files.write(customContentManifest, customContentNames, Charsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)

	// Disable IDE update
	val configDir = sandboxDir / "config$testSuffix"
	try {
		disableIdeUpdate(configDir)
	} catch (e:Exception) {
		LOG.warn("Failed to disallow IDE update check", e)
	}

	return IntelliJIDESandbox(sandboxDir, configDir, destinationDir, sandboxDir / "system$testSuffix")
}

private fun disableIdeUpdate(configDir: Path) {
	val optionsDir = configDir / "options"
	try {
		Files.createDirectories(optionsDir)
	} catch (e:Exception) {
		LOG.warn("Failed to disable update checking in host IDE", e)
		return
	}

	val updatesConfig = optionsDir / "updates.xml"
	val updatesXml = parseXml(updatesConfig) ?: DocumentBuilderFactory.newInstance().run {
		isNamespaceAware = false
		isValidating = false
		isXIncludeAware = false
		val document: Document = newDocumentBuilder().newDocument()
		document.appendChild(document.createElement("application"))
		document
	}

	val application = updatesXml.documentElement!!
	val updatesConfigurable = application.namedElements("component")
			.find { it.getAttribute("name") == "UpdatesConfigurable" }
			?: run {
				val uc: Element = updatesXml.createElement("component")
				uc.setAttribute("name", "UpdatesConfigurable")
				application.appendChild(uc)
				uc
			}
	val checkNeeded = updatesConfigurable.namedElements("option")
			.find { it.getAttribute("name") == "CHECK_NEEDED" }
			?: run {
				val cn: Element = updatesXml.createElement("option")
				cn.setAttribute("name", "CHECK_NEEDED")
				updatesConfigurable.appendChild(cn)
				cn
			}

	checkNeeded.setAttribute("value", "false")

	saveXml(updatesConfig, updatesXml)
}
