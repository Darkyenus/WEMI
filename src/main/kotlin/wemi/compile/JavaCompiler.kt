package wemi.compile

import com.esotericsoftware.jsonbeans.Json
import wemi.boot.MachineWritable

val JavaSourceFileExtensions = listOf("java")

/**
 *
 */
object JavaCompilerFlags {
    val customFlags = CompilerFlag<Collection<String>>("customFlags", "Custom flags to be parsed by the javac CLI")
    val sourceVersion = CompilerFlag<JavaVersion>("sourceVersion", "Version of the source files")
    val targetVersion = CompilerFlag<JavaVersion>("targetVersion", "Version of the created class files")
}

enum class JavaVersion(val version:String) : MachineWritable {
    V1_5("1.5"),
    V1_6("1.6"),
    V1_7("1.7"),
    V1_8("1.8"),
    V1_9("1.9"),
    ;

    override fun writeMachine(json: Json) {
        json.writeValue(version as Any, String::class.java)
    }
}