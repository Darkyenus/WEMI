@file:BuildDependencyRepository("jitpack", "https://jitpack.io")
@file:BuildDependency("com.github.esotericsoftware:jsonbeans:0.9")

import wemi.Configurations.compilingKotlin
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.documentation.DokkaOptions.SourceLinkMapItem
import com.esotericsoftware.jsonbeans.JsonValue

val foxColor by key<String>("Color of an animal")

val hello by project {

    projectGroup set { "com.darkyen" }
    projectName set { "hello" }
    projectVersion set { "1.0-SNAPSHOT" }

    val startYear = "2017"

    publishMetadata modify { metadata ->
        metadata.apply {
            child("inceptionYear", startYear)
        }
    }

    repositories add { repository("jitpack", "https://jitpack.io") }

    kotlinVersion set { wemi.compile.KotlinCompilerVersion.Version1_2_21 }

    libraryDependencies add { dependency("org.slf4j:slf4j-api:1.7.22") }
    libraryDependencies add { dependency("com.github.Darkyenus:tproll:v1.2.4") }
    libraryDependencies add { dependency("com.h2database:h2:1.4.196") }
    libraryDependencies add { kotlinDependency("reflect") }

    extend(compilingJava) {
        compilerOptions[JavaCompilerFlags.customFlags] += "-Xlint:all"
    }

    extend(compilingKotlin) {
        compilerOptions[KotlinCompilerFlags.customFlags] += "-Xno-optimize" // (example, not needed)
        compilerOptions[KotlinCompilerFlags.incremental] = true
    }

    extend(running) {
        projectName set { "Greeting Simulator $startYear" }
    }

    extend(testing) {
        // JUnit 5
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }

        // JUnit 4
        //libraryDependencies add { dependency("junit:junit:4.12") }
        //libraryDependencies add { JUnit4Engine }
    }

    mainClass set { "hello.HelloWemiKt" }

    publishMetadata modify { metadata ->
        metadata.child("name").text = "Wemi Hello World Project"
        metadata.child("description").text = "Demonstrates usage of Wemi"
        metadata.removeChild("url")
        metadata.removeChild("licenses")

        metadata
    }

    extend (archivingDocs) {
        archiveDokkaOptions modify { options ->
            options.outputFormat = wemi.documentation.DokkaOptions.FORMAT_HTML

            val root = projectRoot.get()
            for (sourceRoot in sourceRoots.get()) {
                options.sourceLinks.add(SourceLinkMapItem(
                        sourceRoot,
                        "https://github.com/Darkyenus/WEMI/tree/master/test%20repositories/hello/${root.relativize(sourceRoot).toString()}",
                        "#L"
                ))
            }

            options
        }
    }

    // Test of build-script dependencies
    if (JsonValue(true).toString() != "true") {
        // Does not happen.
        println("Json doesn't work!")
    }

    // Fox example
    foxColor set { "Red" }
}

val arctic by configuration("When in snowy regions") {
    foxColor set {"White"}
}

val wonderland by configuration("When in wonderland") {
    foxColor set {"Rainbow"}

    extend (arctic) {
        foxColor set {"Transparent"}
    }
}

val heaven by configuration("Like wonderland, but better", wonderland) {
    foxColor set { "Octarine" }
}

/**
 * Running this project should produce the same result as running [hello], after it was published.
 */
val helloFromRepository by project(wemi.Archetypes.BlankJVMProject) {
    libraryDependencies add { dependency("com.darkyen:hello:1.0-SNAPSHOT") }

    mainClass set { "hello.HelloWemiKt" }
}