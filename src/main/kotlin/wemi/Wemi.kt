package wemi

import wemi.compile.KotlinCompilerVersion
import wemi.dependency.*
import java.net.URL

/** Version of Wemi build system */
val WemiVersion = "0.0-SNAPSHOT"

val WemiVersionIsSnapshot = WemiVersion.endsWith("-SNAPSHOT")

/** Version of Kotlin used for build scripts */
val WemiKotlinVersion = KotlinCompilerVersion.Version1_1_4

/** Immutable view into the list of loaded projects. */
val AllProjects: Map<String, Project>
    get() = BuildScriptData.AllProjects

/** Immutable view into the list of loaded keys. */
val AllKeys: Map<String, Key<*>>
    get() = BuildScriptData.AllKeys

/** Immutable view into the list of loaded keys. */
val AllConfigurations: Map<String, Configuration>
    get() = BuildScriptData.AllConfigurations

/** Standard function type that is bound as value to the key in [BindingHolder] */
typealias BoundKeyValue<Value> = Scope.() -> Value

/** Value modifier that can be additionally bound to a key in [BindingHolder] */
typealias BoundKeyValueModifier<Value> = Scope.(Value) -> Value

fun project(projectRoot: java.io.File, initializer: Project.() -> Unit): ProjectDelegate {
    return ProjectDelegate(projectRoot, initializer)
}

fun <Value> key(description: String, defaultValue: Value, cached: Boolean = false): KeyDelegate<Value> {
    return KeyDelegate(description, true, defaultValue, cached)
}

fun <Value> key(description: String, cached: Boolean = false): KeyDelegate<Value> {
    return KeyDelegate(description, false, null, cached)
}

fun configuration(description: String, parent: Configuration?, initializer: Configuration.() -> Unit): ConfigurationDelegate {
    return ConfigurationDelegate(description, parent, initializer)
}

/** Convenience ProjectDependency creator. */
fun dependency(group: String, name: String, version: String, preferredRepository: Repository?, vararg attributes: Pair<ProjectAttribute, String>): ProjectDependency {
    return ProjectDependency(ProjectId(group, name, version, preferredRepository, attributes = mapOf(*attributes)))
}

/** Convenience ProjectDependency creator.
 * @param groupNameVersion Gradle-like semicolon separated group, name and version of the dependency.
 *          If the amount of ':'s isn't exactly 2, or one of the triplet is empty, runtime exception is thrown. */
fun dependency(groupNameVersion: String, preferredRepository: Repository?, vararg attributes: Pair<ProjectAttribute, String>): ProjectDependency {
    val first = groupNameVersion.indexOf(':')
    val second = groupNameVersion.indexOf(':', startIndex = maxOf(first + 1, 0))
    val third = groupNameVersion.indexOf(':', startIndex = maxOf(second + 1, 0))
    if (first < 0 || second < 0 || third >= 0) {
        throw WemiException("groupNameVersion must contain exactly two semicolons: '$groupNameVersion'")
    }
    if (first == 0 || second <= first + 1 || second + 1 == groupNameVersion.length) {
        throw WemiException("groupNameVersion must not have empty elements: '$groupNameVersion'")
    }

    val group = groupNameVersion.substring(0, first)
    val name = groupNameVersion.substring(first + 1, second)
    val version = groupNameVersion.substring(second + 1)

    return dependency(group, name, version, preferredRepository, *attributes)
}

fun Scope.kotlinDependency(name: String): ProjectDependency {
    return ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-" + name, Keys.kotlinVersion.get().string))
}

val Scope.KotlinStdlib: ProjectDependency
    get() = kotlinDependency("stdlib")

val Scope.KotlinReflect: ProjectDependency
    get() = kotlinDependency("reflect")

fun repository(name: String, url: String, checksum: Repository.M2.Checksum): Repository.M2 {
    val usedUrl = URL(url)
    return Repository.M2(name,
            usedUrl,
            if (usedUrl.protocol.equals("file", ignoreCase = true)) null else LocalM2Repository,
            checksum)
}