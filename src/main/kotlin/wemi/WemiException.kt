package wemi

/**
 * Exception thrown when Wemi does not like how its APIs are used.
 */
@Suppress("unused")
open class WemiException : RuntimeException {

    /**
     * If stacktrace of this exception should be shown.
     *
     * Set to false if the stacktrace would only confuse the user and is not important to the problem resolution.
     */
    val showStacktrace: Boolean

    constructor(message: String, showStacktrace: Boolean = true) : super(message) {
        this.showStacktrace = showStacktrace
    }

    constructor(message: String, cause: Throwable, showStacktrace: Boolean = true) : super(message, cause) {
        this.showStacktrace = showStacktrace
    }

    /**
     * Special version of the [WemiException], thrown when the [key] that is being evaluated is not set in [scope]
     * it is being evaluated in.
     *
     * @see Scope.get can throw this
     */
    class KeyNotAssignedException(val key: Key<*>, val scope: Scope) : WemiException("'${key.name}' not assigned in $scope", showStacktrace = false)
}