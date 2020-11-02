package wemi.generation

import Files
import Keys
import LocatedPath
import div
import org.slf4j.LoggerFactory
import wemi.BindingHolder
import wemi.EvalScope
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.boot.WemiVersion
import wemi.collections.toMutable
import wemi.util.FileSet
import wemi.util.constructLocatedFiles
import wemi.util.ensureEmptyDirectory
import wemi.util.forCodePoints
import wemi.util.isHidden
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.plus
import wemi.util.toSafeFileName
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/*
 * Source, resource and classpath generation utilities.
 */

private val LOG = LoggerFactory.getLogger("Generation")

/**
 * Create a new source directory ([root]) for generated sources and add it to [wemi.Keys.sources].
 * On each invocation of [wemi.Keys.sources], the directory is emptied and [generate] should generate necessary files.
 * @param name unique name of the generated sources directory
 */
inline fun BindingHolder.generateSources(name:String, crossinline generate:EvalScope.(root:Path)->Unit) {
	Keys.sources modify {
		val sourceDir = WemiCacheFolder / "-generated-sources-${Keys.projectName.get().toSafeFileName()}-$name"
		sourceDir.ensureEmptyDirectory()
		generate(sourceDir)
		it + FileSet(sourceDir)
	}
}

/**
 * Create a new resource directory ([root]) for generated resources and add it to [wemi.Keys.resources].
 * On each invocation of [wemi.Keys.resources], the directory is emptied and [generate] should generate necessary files.
 * @param name unique name of the generated sources directory
 */
inline fun BindingHolder.generateResources(name:String, crossinline generate:EvalScope.(root:Path)->Unit) {
	Keys.resources modify {
		val resourceDir = WemiCacheFolder / "-generated-resources-${Keys.projectName.get().toSafeFileName()}-$name"
		resourceDir.ensureEmptyDirectory()
		generate(resourceDir)
		it + FileSet(resourceDir)
	}
}

/**
 * Create a new internal classpath directory ([root]) for generated classpath entries and add it to [wemi.Keys.generatedClasspath].
 * On each invocation of [wemi.Keys.generatedClasspath], the directory is emptied and [generate] should generate necessary files.
 *
 * Jar files in the [root] directory will be automatically added directly.
 */
inline fun BindingHolder.generateClasspath(name:String, crossinline generate:EvalScope.(root:Path)->Unit) {
	Keys.generatedClasspath modify {
		val cpDir = WemiCacheFolder / "-generated-classpath-${Keys.projectName.get().toSafeFileName()}-$name"
		cpDir.ensureEmptyDirectory()
		generate(cpDir)
		val classpath = it.toMutable()
		var hasNonJars = false
		for (path in Files.list(cpDir)) {
			if (path.isHidden()) {
				continue
			}
			if (path.name.pathHasExtension("jar")) {
				classpath.add(LocatedPath(path))
			} else {
				hasNonJars = true
			}
		}
		if (hasNonJars) {
			constructLocatedFiles(cpDir, classpath) { f -> !f.name.pathHasExtension("jar") }
		}
		classpath
	}
}

/** A typed constant for [generateJavaConstantsFile], [generateKotlinConstantsFile], etc.
 * @param comment that should be added to before the constant */
sealed class Constant(val comment:String) {
	class StringConstant(val value:String, comment:String = "") : Constant(comment)
	class IntConstant(val value:Int, comment:String = "") : Constant(comment)
	class LongConstant(val value:Long, comment:String = "") : Constant(comment)
}

/** @param name of a top-level class, possibly including package name (example: java.lang.String)
 *  @return whether [name] is a valid top-level class name*/
fun isValidTopLevelClassName(name:String):Boolean {
	var firstCharOfBlock = true
	name.forCodePoints { cp ->
		if (firstCharOfBlock) {
			if (cp == '.'.toInt() || !Character.isJavaIdentifierStart(cp)) {
				return false
			}
			firstCharOfBlock = false
		} else if (cp == '.'.toInt()) {
			firstCharOfBlock = true
		} else if (!Character.isJavaIdentifierPart(cp)) {
			return false
		}
	}

	return !firstCharOfBlock
}

/** @return whether [name] is a valid Java identifier */
fun isValidIdentifierName(name:String):Boolean {
	var firstCharOfBlock = true
	name.forCodePoints { cp ->
		if (firstCharOfBlock) {
			if (!Character.isJavaIdentifierStart(cp)) {
				return false
			}
			firstCharOfBlock = false
		} else if (!Character.isJavaIdentifierPart(cp)) {
			return false
		}
	}

	return !firstCharOfBlock
}

/** Process [comment] so that it can safely appear in a multi-line comment in Java or Kotlin */
private fun escapeComment(comment:String):String {
	return comment.replace("*/", "* /").replace("/*", "/ *")
}

/**
 * Generate a Java file with constants.
 * The generated file will contain a comment notice that the file was automatically generated,
 * the [WemiVersion] used, and the time of file's creation.
 *
 * @param root source directory which should contain the constants
 * @param name fully qualified top-level class name (example: com.example.Version)
 * @param constants a map of constant names (must be valid Java identifier) and their values. The constants will appear in natural iteration order, so a use of order-preserving map may be in order.
 * @param includeUserName the comment notice will also contain info about which user generated the file
 */
fun generateJavaConstantsFile(root: Path, name:String, constants:Map<String, Constant>, includeUserName:Boolean = false) {
	if (!isValidTopLevelClassName(name)) {
		throw WemiException("Cannot generate Java constants file, \"$name\" is not a valid top-level class name")
	}

	for (key in constants.keys) {
		if (!isValidIdentifierName(key)) {
			throw WemiException("Cannot generate Java constants file, \"$key\" is not a valid identifier name")
		}
	}

	val packageName:String
	val className:String
	val packageFile:Path

	val classNameDivider = name.lastIndexOf('.')
	if (classNameDivider < 0) {
		packageName = ""
		className = name
		packageFile = root
	} else {
		packageName = name.substring(0, classNameDivider)
		className = name.substring(classNameDivider + 1)
		packageFile = root.resolve(packageName.replace('.', '/'))
	}

	Files.createDirectories(packageFile)

	val classFile = packageFile.resolve("$className.java")
	LOG.debug("Generating a Java constants file {}", classFile)

	Files.newBufferedWriter(classFile, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
		writer.append("/* DO NOT MODIFY! Generated by Wemi ").append(escapeComment(WemiVersion)).append(" at ").append(escapeComment(Instant.now().toString()))
		val userName = System.getProperty("user.name")
		if (includeUserName && !userName.isNullOrBlank()) {
			writer.append(" by ").append(escapeComment(userName))
		}
		writer.append(" */\n\n")

		if (!packageName.isBlank()) {
			writer.append("package ").append(packageName).append(";\n\n")
		}

		writer.append("public final class ").append(className).append(" {\n\n")

		for ((key, value) in constants) {
			if (!value.comment.isBlank()) {
				if (value.comment.indexOf('\n') >= 0) {
					// Multi-line comment
					writer.append("\t/**\n")
					for (commentLine in value.comment.replace("\r", "").split('\n')) {
						writer.append("\t * ").append(escapeComment(commentLine)).append('\n')
					}
					writer.append("\t */\n")
				} else {
					// Single-line comment
					writer.append("\t/** ").append(escapeComment(value.comment)).append(" */\n")
				}
			}

			writer.append("\tpublic static final ")
			writer.append(when (value) {
				is Constant.StringConstant -> "String"
				is Constant.IntConstant -> "int"
				is Constant.LongConstant -> "long"
			})
			writer.append(' ').append(key).append(" = ")
			when (value) {
				is Constant.StringConstant -> {
					writer.append('"')
					for (c in value.value) {
						when (c) {
							'\t' -> writer.append("\\t")
							'\b' -> writer.append("\\b")
							'\n' -> writer.append("\\n")
							'\r' -> writer.append("\\r")
							'\u000C' -> writer.append("\\f")
							'"' -> writer.append("\\\"")
							'\\' -> writer.append("\\\\")
							else -> {
								if (c.isISOControl() || !c.isDefined()) {
									writer.append("\\u").append("%04x".format(c.toInt()))
								} else {
									writer.append(c)
								}
							}
						}
					}
					writer.append('"')
				}
				is Constant.IntConstant -> writer.append(value.value.toString())
				is Constant.LongConstant -> writer.append(value.value.toString()).append('L')
			}
			writer.append(";\n\n")
		}

		writer.append("}\n")
	}
}

/**
 * Generate a Kotlin file with top-level constants.
 * The generated file will contain a comment notice that the file was automatically generated,
 * the [WemiVersion] used, and the time of file's creation.
 *
 * @param root source directory which should contain the constants
 * @param name fully qualified top-level class name (example: com.example.Version)
 * @param constants a map of constant names (must be valid Kotlin identifier) and their values. The constants will appear in natural iteration order, so a use of order-preserving map may be in order.
 * @param includeUserName the comment notice will also contain info about which user generated the file
 */
fun generateKotlinConstantsFile(root: Path, name:String, constants:Map<String, Constant>, includeUserName:Boolean = false) {
	if (!isValidTopLevelClassName(name)) {
		throw WemiException("Cannot generate Kotlin constants file, \"$name\" is not a valid top-level class name")
	}

	for (key in constants.keys) {
		if (!isValidIdentifierName(key)) {
			throw WemiException("Cannot generate Kotlin constants file, \"$key\" is not a valid identifier name")
		}
	}

	val packageName:String
	val className:String
	val packageFile:Path

	val classNameDivider = name.lastIndexOf('.')
	if (classNameDivider < 0) {
		packageName = ""
		className = name
		packageFile = root
	} else {
		packageName = name.substring(0, classNameDivider)
		className = name.substring(classNameDivider + 1)
		packageFile = root.resolve(packageName.replace('.', '/'))
	}

	Files.createDirectories(packageFile)

	val classFile = packageFile.resolve("$className.kt")
	LOG.debug("Generating a Kotlin constants file {}", classFile)

	Files.newBufferedWriter(classFile, Charsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { writer ->
		writer.append("/* DO NOT MODIFY! Generated by Wemi ").append(escapeComment(WemiVersion)).append(" at ").append(escapeComment(Instant.now().toString()))
		val userName = System.getProperty("user.name")
		if (includeUserName && !userName.isNullOrBlank()) {
			writer.append(" by ").append(escapeComment(userName))
		}
		writer.append(" */\n\n")

		if (!packageName.isBlank()) {
			writer.append("package ").append(packageName).append("\n\n")
		}

		for ((key, value) in constants) {
			if (!value.comment.isBlank()) {
				if (value.comment.indexOf('\n') >= 0) {
					// Multi-line comment
					writer.append("/**\n")
					for (commentLine in value.comment.replace("\r", "").split('\n')) {
						writer.append(" * ").append(escapeComment(commentLine)).append('\n')
					}
					writer.append(" */\n")
				} else {
					// Single-line comment
					writer.append("/** ").append(escapeComment(value.comment)).append(" */\n")
				}
			}

			writer.append("const val ").append(key).append(": ")
			writer.append(when (value) {
				is Constant.StringConstant -> "String"
				is Constant.IntConstant -> "Int"
				is Constant.LongConstant -> "Long"
			})
			writer.append(" = ")
			when (value) {
				is Constant.StringConstant -> {
					writer.append('"')
					for (c in value.value) {
						when (c) {
							'\t' -> writer.append("\\t")
							'\b' -> writer.append("\\b")
							'\n' -> writer.append("\\n")
							'\r' -> writer.append("\\r")
							'$' -> writer.append("\\\$")
							'"' -> writer.append("\\\"")
							'\\' -> writer.append("\\\\")
							else -> {
								if (c.isISOControl() || !c.isDefined()) {
									writer.append("\\u").append("%04x".format(c.toInt()))
								} else {
									writer.append(c)
								}
							}
						}
					}
					writer.append('"')
				}
				is Constant.IntConstant -> writer.append(value.value.toString())
				is Constant.LongConstant -> writer.append(value.value.toString()).append('L')
			}
			writer.append("\n\n")
		}
	}
}
