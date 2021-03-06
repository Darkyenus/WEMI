import wemi.archetypes.*
import wemi.keys.*
import wemi.*

val hello by project(JavaProject) {

    projectGroup set { "com.darkyen" }
    projectName set { "hello-java" }
    projectVersion set { "1.0-SNAPSHOT" }

    mainClass set { "hello.HelloWemi" }

    libraryDependencies add { dependency("com.esotericsoftware:minlog:1.3.1") }
}
