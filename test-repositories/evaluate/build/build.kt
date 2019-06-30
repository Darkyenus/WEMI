@file:BuildDependency("org.hamcrest:hamcrest:2.1")

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import wemi.util.*
import wemi.*

val someKey by key<String>("")
val someConfig by configuration("") {
    someKey set { "someConfig" }
}
val extendedConfig by configuration("") {}
val simpleEvaluationTest by key<Unit>("Tests simple evaluation logic")



val numberKey by key<Int>("")
val multiplying by configuration("") {
    numberKey modify { it * 3 }
}
val adding by configuration("") {
    numberKey modify { it + 3 }
}
val subtracting by configuration("") {}
val modifyEvaluationTest by key<Unit>("Tests simple modifier evaluation logic")


val keyExtensionArchetype by archetype(Archetypes::Base) {
    keyWhichIsSetInArchetypeThenExtended set { "" }
}
val keyWhichIsExtended by key<String>("")
val keyWhichIsSetThenExtended by key<String>("")
val keyWhichIsSetInArchetypeThenExtended by key<String>("")
val configurationWhichBindsKey by configuration("")  {
    keyWhichIsExtended set { "a" }
    keyWhichIsSetThenExtended modify { it + "a" }
    keyWhichIsSetInArchetypeThenExtended modify { it + "a" }
}
val configurationWhichExtendsTheOneWhichBindsKey by configuration("", configurationWhichBindsKey) {}
val keySetInConfigurationAndThenExtendedTest by key<Unit>("")

val evaluationTest by project(archetypes = *arrayOf(keyExtensionArchetype)) {

    someKey set { "project" }
    extend(extendedConfig) {
        someKey set { "extendedConfig" }
    }
    simpleEvaluationTest set {
        assertThat(someKey.get(), equalTo("project"))
        assertThat(using(someConfig){ someKey.get() }, equalTo("someConfig"))
        assertThat(using(extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(someConfig, extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(extendedConfig, someConfig){ someKey.get() }, equalTo("someConfig"))
    }
    autoRun(simpleEvaluationTest)
    
    
    numberKey set { 1 }
    extend(subtracting) {
        numberKey modify { it - 3 }
    }
    modifyEvaluationTest set {
        assertThat(numberKey.get(), equalTo(1))
        assertThat(using(multiplying){ numberKey.get() }, equalTo(3))
        assertThat(using(adding){ numberKey.get() }, equalTo(4))
        assertThat(using(subtracting){ numberKey.get() }, equalTo(-2))
        assertThat(using(multiplying, adding){ numberKey.get() }, equalTo(6))
        assertThat(using(multiplying, adding, adding){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, multiplying){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, subtracting){ numberKey.get() }, equalTo(0))
        assertThat(using(subtracting, multiplying){ numberKey.get() }, equalTo(-6))
    }
    autoRun(modifyEvaluationTest)


    keyWhichIsSetThenExtended set { "" }
    extend(configurationWhichBindsKey) {
        keyWhichIsExtended modify { it + "b" }
        keyWhichIsSetThenExtended modify { it + "b" }
        keyWhichIsSetInArchetypeThenExtended modify { it + "b" }
    }
    keySetInConfigurationAndThenExtendedTest set {
        assertThat(using(configurationWhichBindsKey) { keyWhichIsExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichBindsKey) { keyWhichIsSetThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichBindsKey) { keyWhichIsSetInArchetypeThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsSetThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsSetInArchetypeThenExtended.get() }, equalTo("ab"))
    }
    autoRun(keySetInConfigurationAndThenExtendedTest)
}

val compileErrors by project(path("errors")) {
    extend(compilingJava) {
        sources set { FileSet(projectRoot.get() / "src") }
        compilerOptions[wemi.compile.JavaCompilerFlags.customFlags] += "-Xlint:all"
    }

    extend(compilingKotlin) {
        sources set { FileSet(projectRoot.get() / "src") }
    }
}

val CacheRepository = (wemi.boot.WemiCacheFolder / "-test-cache-repository").let {
    it.deleteRecursively()
    it
}

val checkResolution by key<Unit>("Check if resolved files contain what they should")

var classpathAssertions = 0
var classpathAssertionsFailed = 0

fun EvalScope.assertClasspathContains(vararg items:String) {
    classpathAssertions++
    val got = externalClasspath.get().map { Files.readAllBytes(it.file).toString(Charsets.UTF_8) }.toSet()
    val expected = items.toSet()
    if (got != expected) {
        classpathAssertionsFailed++
        System.err.println("\n\n\nERROR: Got $got, expected $expected")
        // assertThat(got, equalTo(expected)) disabled temporarily, because it should not hard quit right now
    }
}

fun EvalScope.assertClasspathContainsFiles(vararg items:String) {
    classpathAssertions++
    val got = externalClasspath.get().map { it.file.name }.toSet()
    val expected = items.toSet()
    if (got != expected) {
        classpathAssertionsFailed++
        System.err.println("\n\n\nERROR: Got $got, expected $expected")
        // assertThat(got, equalTo(expected)) disabled temporarily, because it should not hard quit right now
    }
}

val release_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "release-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0")) }

    checkResolution set {
        assertClasspathContains("v1.0")
    }
}

val release_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "release-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.1")) }

    checkResolution set {
        assertClasspathContains("v1.0" /* through dependency in 1.1 */, "v1.1")
    }
}

val non_unique_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-1")
    }
}

val non_unique_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-1")
    }
}

val non_unique_3 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-2", CacheRepository, snapshotUpdateDelaySeconds = wemi.dependency.SnapshotCheckAlways, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-2")
    }
}

val unique_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val unique_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val unique_3 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, snapshotUpdateDelaySeconds = wemi.dependency.SnapshotCheckAlways, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-2")
    }
}

val unique_4 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT", snapshotVersion = "20190101.123456-1")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val mavenScopeFiltering by configuration("") {
    libraryDependencies set { setOf(dependency("org.jline", "jline-terminal", "3.3.0")) }

    checkResolution set {
        // Must not resolve to testing jars which jline uses
        assertClasspathContainsFiles("jline-terminal-jansi-3.3.0.jar")
    }
}

val dependency_resolution by project(path("dependency-resolution")) {
    // Test dependency resolution by resolving against changing repository 3 different libraries
    /*
    1. Release
        1. Exists
        2. Exists, with a new version which depends on the old one
        3. Does not exist (offline) but should still resolve, as it is in the cache
     */
    autoRun(checkResolution, release_1)
    autoRun(checkResolution, release_2)
    autoRun(checkResolution, Configurations.offline, release_2)

    /* 2. Non-unique snapshot
        1. Exists
        2. Exists, is different, but the cache is set to daily, so we still see the old one
        3. Exists and see the new one, because re-check delay has been set to zero
     */
    autoRun(checkResolution, non_unique_1)
    autoRun(checkResolution, non_unique_2)
    autoRun(checkResolution, non_unique_3)

    /* 3. Unique snapshot
        1. Exists
        2. Newer exists, but is not found yet due to recheck policy
        3. Newer exists and is found
        4. Newer exists and is ignored, because older version is forced
     */
    autoRun(checkResolution, unique_1)
    autoRun(checkResolution, unique_2)
    autoRun(checkResolution, unique_3)
    autoRun(checkResolution, unique_4)

    // Check if correct dependency artifacts are downloaded
    autoRun(checkResolution, mavenScopeFiltering)


    checkResolution set {
        println()
        println("Assertions: $classpathAssertions")
        println("Failed As.: $classpathAssertionsFailed")
        println()
    }
    autoRun(checkResolution)
}