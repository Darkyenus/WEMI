@file:Suppress("unused")

package wemi.boot

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.Reader

/**
 * Since Java does not guarantee any persistency in ordering of annotation's fields/methods,
 * we have to store it ourselves.
 *
 * @param value ordered array of names of constructor fields of Build* directive annotation.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class DirectiveFields(val value:Array<String>)

/**
 * Build script file annotation, which requests given mavenized library to be available in the build script's classpath.
 *
 * **NOTE**: These annotations are not parsed by the Kotlin compiler, but by Wemi itself.
 * This places some constraints on their use:
 * - annotations MUST be placed one per line, with nothing else on the line, except for surrounding whitespace
 * - single annotation MUST NOT be split across multiple lines
 * - annotations MUST have constructors which contain only plain string arguments, without any escapes
 * - annotation constructors MAY contain valid name tags (they will be used to detect argument reordering)
 *
 * @param groupOrFull of the artifact coordinate OR full "group:name:version" for compact form
 * @param name of the artifact coordinate OR empty string (default) when using compact form
 * @param version of the artifact coordinate OR empty string (default) when using compact form
 * @see wemi.dependency function, which has similar arguments
 * @see wemi.Keys.libraryDependencies key, which stores library dependencies for built projects
 * @see BuildDependencyRepository to add more repositories to search in
 * @see BuildClasspathDependency to add files directly to the build script classpath, without resolution step
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["groupOrFull", "name", "version"])
annotation class BuildDependency(val groupOrFull:String, val name:String = "", val version:String = "")

/**
 * Build script file annotation, which adds a repository to search dependencies in (specified by [BuildDependency]).
 *
 * @param name of the repository
 * @param url of the repository
 * @see wemi.repository function, which has similar arguments
 * @see wemi.Keys.repositories key, which stores repositories for built projects
 * @see BuildDependency for important notes about build-script directive annotations.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["name", "url"])
annotation class BuildDependencyRepository(val name:String, val url:String)

/**
 * Build script file annotation, which adds a given file to the build script's classpath.
 *
 * @param file path to the classpath file (typically .jar) or directory.
 * Absolute or relative to the [wemi.boot.WemiRootFolder] (=project's root directory)
 * @see BuildDependency for important notes about build-script directive annotations
 * @see wemi.Keys.externalClasspath key, which stores raw classpath dependencies of built projects
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["file"])
annotation class BuildClasspathDependency(val file:String)

/**
 * Directives supported in build-scripts.
 */
internal val SupportedDirectives = arrayOf(
        BuildDependency::class.java,
        BuildDependencyRepository::class.java,
        BuildClasspathDependency::class.java)

private val LOG = LoggerFactory.getLogger("Directives")

/**
 * Regex used to find lines with directives.
 *
 * Directives are file annotations from Directives file, such as [BuildDependency].
 *
 * Matches: `@file:<wemi.boot.>BuildAnnotation(...)`
 */
private val DirectiveRegex = "\\s+@file\\s*:\\s*(?:wemi\\s*\\.\\s*boot\\s*\\.\\s*)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*)\\)\\s*".toRegex()

/**
 * Matches single, optionally named, argument, passed into the [DirectiveRegex] constructor.
 */
private val DirectiveConstructorPartRegex = "^\\s*(?:([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*)?\"([^\"]*)\"\\s*,?".toRegex()

/**
 * Read [source] as Kotlin file, that may optionally contain [directives] file annotations.
 * For each such annotation found, call [consumer].
 *
 * @param source from which lines are read. Caller's responsibility to close.
 * @param directives must be themselves annotated with [DirectiveFields].
 * @return true if all encountered annotations were valid, false if reading ended prematurely because of invalid annotation
 */
internal fun parseFileDirectives(source: BufferedReader, directives:Array<Class<out Annotation>>,
                                 consumer:(annotation:Class<out Annotation>, arguments:Array<String>) -> Unit):Boolean {
    var lineNumber = 0
    while (true) {
        lineNumber++
        val line = source.readLine() ?: break

        val directiveMatch = DirectiveRegex.matchEntire(line) ?: continue
        val directiveName = directiveMatch.groupValues[1]
        val directiveClass = directives.find { it.simpleName == directiveName } ?: continue

        val directiveClassFieldNames = directiveClass.getAnnotation(DirectiveFields::class.java).value
        val directiveClassFieldMethods = directiveClass.methods

        // Assume that all values are strings (because they are at this point)
        val directiveConstructorValues = arrayOfNulls<String>(directiveClassFieldNames.size)

        val constructor = directiveMatch.groupValues[2]

        var parameter = DirectiveConstructorPartRegex.find(constructor)
        var parameterIndex = 0
        while (parameter != null) {
            val name = parameter.groupValues[1]
            if (name.isEmpty()) {
                if (parameterIndex == -1) {
                    LOG.warn("{}:{} Invalid directive annotation - only named parameters are allowed after first named parameter", source, line)
                    return false
                }
                directiveConstructorValues[parameterIndex++] = parameter.groupValues[2]
            } else {
                @Suppress("UNUSED_VALUE")//TODO Remove when bug in plugin flow analyser is fixed
                parameterIndex = -1
                val index = directiveClassFieldNames.indexOf(name)
                if (index < 0) {
                    LOG.warn("{}:{} Invalid directive annotation - unknown parameter name {}", source, line, name)
                    return false
                }
                if (directiveConstructorValues[index] != null) {
                    LOG.warn("{}:{} Invalid directive annotation - parameter {} set multiple times", source, line, name)
                    return false
                }
                directiveConstructorValues[index] = parameter.groupValues[2]
            }

            parameter = parameter.next()
        }

        // Complete with default values and check if all variables are set
        for (i in directiveConstructorValues.indices) {
            if (directiveConstructorValues[i] == null) {
                directiveConstructorValues[i] = directiveClassFieldMethods.find { it.name == directiveClassFieldNames[i] }?.defaultValue as String?
                if (directiveConstructorValues[i] == null) {
                    LOG.warn("{}:{} Invalid directive annotation - parameter {} needs to be set", source, line, directiveClassFieldNames[i])
                    return false
                }
            }
        }

        // No value can be now null
        @Suppress("UNCHECKED_CAST")
        directiveConstructorValues as Array<String>

        // Use it
        consumer(directiveClass, directiveConstructorValues)
    }

    return true
}