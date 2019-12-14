package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.util.*
import java.io.IOException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Artifact classifier.
 * @see DependencyId.classifier
 */
typealias Classifier = String

/** Artifacts scope.
 * See https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope */
typealias Scope = String

/** Concatenate two classifiers. */
fun joinClassifiers(first:Classifier, second:Classifier):Classifier {
    return when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> "$first-$second"
    }
}

/** No classifier (empty string) */
const val NoClassifier: Classifier = ""
/** Classifier appended to artifacts with sources */
const val SourcesClassifier: Classifier = "sources"
/** Classifier appended to artifacts with Javadoc */
const val JavadocClassifier: Classifier = "javadoc"

/** Available during compilation and runtime of both project itself and tests.
 * Available transitively. */
const val ScopeCompile: Scope = "compile"
/** Available only on compilation and test classpath. */
const val ScopeProvided: Scope = "provided"
/** Available for running and for testing, not for compilation. */
const val ScopeRuntime: Scope = "runtime"
/** Available for testing only. */
const val ScopeTest: Scope = "test"

internal const val DEFAULT_TYPE:String = "jar"
internal const val DEFAULT_SCOPE:Scope = ScopeCompile
internal const val DEFAULT_OPTIONAL:Boolean = false
internal const val DEFAULT_SNAPSHOT_VERSION:String = ""

/**
 * Unique identifier for project/module to be resolved.
 * May have dependencies on other projects and may have artifacts.
 */
@Json(DependencyId.Serializer::class)
class DependencyId constructor(
        /** group of the dependency (aka organisation, aka groupId) */
        val group: String,
        /** name of the dependency (aka artifactId) */
        val name: String,
        /** version of the project (aka revision) */
        val version: String,
        /**
         * Various variants of the same dependency.
         * Examples: jdk15, sources, javadoc, linux
         * @see SourcesClassifier
         * @see JavadocClassifier
         */
        classifier: Classifier = NoClassifier,
        /**
         * Corresponds to the packaging of the dependency (and overrides it).
         * Determines what sort of artifact is retrieved.
         * See https://maven.apache.org/ref/3.6.0/maven-core/artifact-handlers.html
         *
         * Examples: jar (default), pom (returns pom.xml, used internally)
         */
        type: String = DEFAULT_TYPE,
        /** When [isSnapshot] and repository uses unique snapshots, `SNAPSHOT` in the [version]
         * is replaced by this string for the last resolution pass. Resolver (Wemi) will automatically
         * replace it with the newest snapshot version (in format: `timestamp-buildNumber`),
         * but if you need a specific snapshot version, it can be set here. */
        snapshotVersion: String = DEFAULT_SNAPSHOT_VERSION) {

    val classifier = classifier.trim().toLowerCase()
    val type = type.trim().toLowerCase()
    val snapshotVersion = snapshotVersion.trim().toLowerCase()

    fun copy(group:String = this.group, name:String = this.name, version:String = this.version, classifier:Classifier = this.classifier, type:String = this.type, snapshotVersion:String = this.snapshotVersion):DependencyId {
        return DependencyId(group, name, version, classifier, type, snapshotVersion)
    }

    /** Snapshot [version]s end with `-SNAPSHOT` suffix. */
    val isSnapshot:Boolean
        get() = version.endsWith("-SNAPSHOT")


    override fun toString(): String {
        // group:name:version:classifier@type
        val result = StringBuilder()
        result.append(group).append(':').append(name).append(':').append(version)

        if (isSnapshot && snapshotVersion != DEFAULT_SNAPSHOT_VERSION) {
            result.setLength(maxOf(result.length - "SNAPSHOT".length, 0))
            result.append('(').append(snapshotVersion).append(')')
        }
        if (classifier != NoClassifier) {
            result.append(":").append(classifier)
        }
        if (type != DEFAULT_TYPE) {
            result.append("@").append(type)
        }
        return result.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DependencyId

        if (group != other.group) return false
        if (name != other.name) return false
        if (version != other.version) return false
        if (classifier != other.classifier) return false
        if (type != other.type) return false
        if (snapshotVersion != other.snapshotVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = group.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + classifier.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + snapshotVersion.hashCode()
        return result
    }

    internal class Serializer : JsonSerializer<DependencyId> {

        override fun JsonWriter.write(value: DependencyId) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                if (value.classifier != NoClassifier) {
                    field("classifier", value.classifier)
                }
                if (value.type != DEFAULT_TYPE) {
                    field("type", value.type)
                }
                if (value.snapshotVersion != DEFAULT_SNAPSHOT_VERSION) {
                    field("snapshotVersion", value.snapshotVersion)
                }
            }
        }

        override fun read(value: JsonValue): DependencyId {
            return DependencyId(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("classifier", NoClassifier),
                    value.field("type", DEFAULT_TYPE),
                    value.field("snapshotVersion", DEFAULT_SNAPSHOT_VERSION)
            )
        }
    }
}

/** Represents exclusion rule. All non-null fields must match precisely to be considered for exclusion. */
@Json(DependencyExclusion.Serializer::class)
class DependencyExclusion(val group: String? = null, val name: String? = null, val version: String? = null,
                          val classifier: Classifier? = null, val type: String? = null) {

    private fun <T> matches(pattern: T?, value: T): Boolean {
        return (pattern ?: return true) == value
    }

    /**
     * Checks if given [dependencyId] is excluded by this rule.
     * @return true when all non-null properties are equal to the values in [dependencyId]
     */
    fun excludes(dependencyId: DependencyId): Boolean {
        return matches(group, dependencyId.group)
                && matches(name, dependencyId.name)
                && matches(version, dependencyId.version)
                && matches(classifier, dependencyId.classifier)
                && matches(type, dependencyId.type)
    }

    override fun toString(): String {
        return "${group ?: "*"}:${name ?: "*"}:${version ?: "*"} classifier:${classifier ?: "*"} type:${type ?: "*"}"
    }

    internal class Serializer : JsonSerializer<DependencyExclusion> {
        override fun JsonWriter.write(value: DependencyExclusion) {
            writeObject {
                field("group", value.group)
                field("name", value.name)
                field("version", value.version)

                field("classifier", value.classifier)
                field("type", value.type)
            }
        }

        override fun read(value: JsonValue): DependencyExclusion {
            return DependencyExclusion(
                    value.field("group"),
                    value.field("name"),
                    value.field("version"),

                    value.field("classifier"),
                    value.field("type")
            )
        }
    }
}

/** Represents dependency on a [dependencyId], with transitive dependencies, which may be excluded by [exclusions].
 *
 * @param exclusions to filter transitive dependencies with */
@Json(Dependency.Serializer::class)
class Dependency(val dependencyId: DependencyId,
                 scope: Scope = DEFAULT_SCOPE,
                 /** Optional transitive dependencies are skipped by default by Wemi.
                  * See https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html */
                 val optional: Boolean = DEFAULT_OPTIONAL,
                 /** Filtering applied to transitive dependencies to exclude some. */
                 val exclusions: List<DependencyExclusion> = emptyList(),
                 /** When transitive dependencies match any of these through "{groupId, artifactId, type, classifier}",
                  * they will be replaced with info in here.
                  *
                  * See https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management */
                 val dependencyManagement: List<Dependency> = emptyList()
) {

    fun copy(dependencyId: DependencyId = this.dependencyId,
             scope:String = this.scope,
             optional:Boolean = this.optional,
             exclusions:List<DependencyExclusion> = this.exclusions,
             dependencyManagement:List<Dependency> = this.dependencyManagement):Dependency {
        return Dependency(dependencyId, scope, optional, exclusions, dependencyManagement)
    }

    /**
     * Scope of the dependency.
     *
     * Examples: compile, provided, test
     * See https://maven.apache.org/pom.html#Dependencies
     */
    val scope = scope.trim().toLowerCase()

    override fun toString(): String {
        val result = StringBuilder()
        result.append(dependencyId)

        if (scope != DEFAULT_SCOPE) {
            result.append(" scope:").append(scope)
        }
        if (optional) {
            result.append(" optional")
        }
        if (exclusions.isNotEmpty()) {
            result.append(' ').append(exclusions.size).append(" exclusion(s)")
        }
        if (dependencyManagement.isNotEmpty()) {
            result.append(' ').append(dependencyManagement.size).append(" dependencyManagement(s)")
        }

        return result.toString()
    }

    internal class Serializer : JsonSerializer<Dependency> {
        override fun JsonWriter.write(value: Dependency) {
            writeObject {
                field("id", value.dependencyId)
                if (value.scope != DEFAULT_SCOPE) {
                    field("scope", value.scope)
                }
                if (value.optional != DEFAULT_OPTIONAL) {
                    field("optional", value.optional)
                }
                if (value.exclusions.isNotEmpty()) {
                    fieldCollection("exclusions", value.exclusions)
                }
                if (value.dependencyManagement.isNotEmpty()) {
                    fieldCollection("dependencyManagement", value.exclusions)
                }
            }
        }

        override fun read(value: JsonValue): Dependency {
            val id = value.field<DependencyId>("id")
            val scope = value.field("scope", DEFAULT_SCOPE)
            val optional = value.field("optional", DEFAULT_OPTIONAL)
            val exclusions:List<DependencyExclusion> = value.fieldToCollection("exclusions", ArrayList())
            val dependencyManagement:List<Dependency> = value.fieldToCollection("dependencyManagement", ArrayList())
            return Dependency(id, scope, optional, exclusions, dependencyManagement)
        }
    }
}

private val ARTIFACT_PATH_LOG = LoggerFactory.getLogger(ArtifactPath::class.java)

/** Represents a [path] with lazily loaded [data]. */
class ArtifactPath(val path:Path, data:ByteArray?,
                   /** From which repository was the artifact retrieved */
                   val repository:Repository,
                   /** Inside the repository from which the artifact was possibly originally obtained */
                   val url:URL,
                   /** Whether or not was this artifact retrieved from cache */
                   val fromCache:Boolean) {

    /** When null, but [path] exists, getter will attempt to load it and store the result for later queries. */
    var data: ByteArray? = data
        get() {
            if (field == null) {
                try {
                    field = Files.readAllBytes(path)
                } catch (e: IOException) {
                    ARTIFACT_PATH_LOG.warn("Failed to load data from '{}' which was supposed to be there", path, e)
                }
            }

            return field
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArtifactPath

        if (path != other.path) return false

        return true
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return path.toString()
    }
}

/**
 * Data retrieved by resolving a dependency.
 *
 * If successful, contains information about transitive [dependencies] and holds artifacts that were found.
 */
class ResolvedDependency private constructor(
        /** That was being resolved */
        val id: DependencyId,
        /** Scope in which the dependency was resolved. */
        val scope:Scope,
        /** Of the [id] that were found */
        val dependencies: List<Dependency>,
        /** In which (non-cache) repository was [id] ultimately found in */
        val resolvedFrom: Repository?,
        /** May contain a message explaining why did the dependency failed to resolve, if [hasError] */
        val log: CharSequence?,
        /** If the artifact has been resolved to a file in a local filesystem, it is here. */
        val artifact:ArtifactPath?
) : JsonWritable {

    /** `true` if this dependency failed to resolve (partially or completely), for any reason */
    val hasError: Boolean
        get() = log != null

    /** Error constructor */
    constructor(id:DependencyId, log:CharSequence, resolvedFrom:Repository? = null)
            : this(id, "", emptyList(), resolvedFrom, log, null)

    /** Success constructor */
    constructor(id:DependencyId, scope:String, dependencies:List<Dependency>, resolvedFrom:Repository, artifact:ArtifactPath)
            :this(id, scope, dependencies, resolvedFrom, null, artifact)

    fun copy(id:DependencyId = this.id, scope:String = this.scope, dependencies:List<Dependency> = this.dependencies,
             resolvedFrom:Repository? = this.resolvedFrom, log:CharSequence? = this.log, artifact:ArtifactPath? = this.artifact):ResolvedDependency {
        return ResolvedDependency(id, scope, dependencies, resolvedFrom, log, artifact)
    }

    override fun JsonWriter.write() {
        writeObject {
            field("id", id)
            fieldCollection("dependencies", dependencies)
            field("resolvedFrom", resolvedFrom)
            field("hasError", hasError)
            if (!log.isNullOrBlank()) {
                field("log", log.toString())
            }
            if (artifact != null) {
                field("artifact", artifact.path)
            }
        }
    }

    override fun toString(): String {
        val result = StringBuilder()
        result.append("ResolvedDependency(id=").append(id)
        result.append(", dependencies=").append(dependencies)
        result.append(", resolvedFrom=").append(resolvedFrom)
        result.append(", hasError=").append(hasError)
        if (!log.isNullOrBlank()) {
            result.append(", log=").append(log)
        }
        if (artifact != null) {
            result.append(", artifact=").append(artifact.path)
        }
        return result.append(')').toString()
    }
}
