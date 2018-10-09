@file:Suppress("MemberVisibilityCanPrivate", "MemberVisibilityCanBePrivate")

package wemi

import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import wemi.collections.ArrayMap
import wemi.collections.toMutable
import wemi.compile.CompilerFlag
import wemi.compile.CompilerFlags
import wemi.util.*
import java.io.File
import java.lang.ref.SoftReference
import java.nio.file.Path
import java.util.*

typealias InputKey = String
typealias InputKeyDescription = String

/** 
 * Key which can have value of type [Value] assigned, through [Project] or [Configuration].
 */
class Key<Value> internal constructor(
        /**
         * Name of the key. Specified by the variable name this key was declared at.
         * Uniquely describes the key.
         * Having two separate key instances with same name has undefined behavior and will lead to problems.
         */
        val name: String,
        /**
         * Human readable description of this key.
         * Values bound to this key should follow this as a specification.
         */
        val description: String,
        /** True if defaultValue is set, false if not.
         * Needed, because we don't know whether or not is [Value] nullable
         * or not, so we need to know if we should return null or not. */
        internal val hasDefaultValue: Boolean,
        /**
         * Default value used for this key, when no other value is bound.
         */
        internal val defaultValue: Value?,
        /**
         * Input keys that are used by this key.
         * Used only for documentation and CLI purposes (autocomplete).
         * @see [Input]
         */
        internal val inputKeys: Array<Pair<InputKey, InputKeyDescription>>,
        /**
         * Optional function that can convert the result of this key's evaluation to a more readable
         * or more informative string.
         *
         * Called when the key is evaluated in CLI top level.
         */
        internal val prettyPrinter: ((Value) -> CharSequence)?) : WithDescriptiveString, JsonWritable, Comparable<Key<*>> {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other?.javaClass != javaClass) return false

        other as Key<*>

        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    /**
     * Returns [name]
     */
    override fun toString(): String = name

    /**
     * Returns [name] - [description]
     */
    override fun toDescriptiveAnsiString(): String = "${format(name, format = Format.Bold)} - $description"

    override fun compareTo(other: Key<*>): Int {
        return this.name.compareTo(other.name)
    }

    override fun JsonWriter.write() {
        writeObject {
            field("name", name)
            field("description", description)
            field("hasDefaultValue", hasDefaultValue)
        }
    }
}

/**
 * Configuration is a layer of bindings that can be added to the [Scope].
 *
 * Configuration's bound values is the sum of it's [parent]'s values and own values, where own ones override
 * parent ones, if any.
 *
 * @param name of the configuration. Specified by the variable name this configuration was declared at.
 * @param description to be displayed in the CLI as help
 * @param parent of the [Configuration]
 * @see BindingHolder for info about how the values are bound
 */
class Configuration internal constructor(val name: String,
                                         val description: String,
                                         val parent: Configuration?)
    : BindingHolder(), JsonWritable {

    /**
     * @return [name]
     */
    override fun toString(): String {
        return name
    }

    override fun toDescriptiveAnsiString(): String =
            StringBuilder().format(format = Format.Bold).append(name)
                    .format(Color.White).append(':').format().toString()

    override fun JsonWriter.write() {
        writeObject {
            field("name", name)
            field("description", description)
        }
    }
}

private val AnonymousConfigurationDescriptiveAnsiString = format("<anonymous>", format = Format.Bold).toString()

/**
 * A special version of [Configuration] that is anonymous and can be created at runtime, any time.
 * Unlike full configuration, does not have name, description, nor parent.
 *
 * @param fromScope for debug only
 * @see [Scope.using] about creation of these
 */
class AnonymousConfiguration @PublishedApi internal constructor(private val fromScope:Scope) : BindingHolder() {
    /**
     * @return <anonymous>
     */
    override fun toDescriptiveAnsiString(): String =
            StringBuilder().format(Color.White).append(fromScope)
                    .append(".using(").format(format = Format.Bold)
                    .append("<anonymous>:").format(Color.White).append(')').format().toString()

    override fun toString(): String = "$fromScope.using(<anonymous>:)"
}

/**
 * Holds information about configuration extensions made by [BindingHolder.extend].
 * Values bound to this take precedence over the values [extending],
 * like values in [Configuration] take precedence over those in [Configuration.parent].
 *
 * @param extending which configuration is being extended by this extension
 * @param from which this extension has been created, mostly for debugging
 */
class ConfigurationExtension internal constructor(
        @Suppress("MemberVisibilityCanBePrivate")
        val extending: Configuration,
        val from: BindingHolder) : BindingHolder() {

    override fun toDescriptiveAnsiString(): String {
        return StringBuilder()
                .format(format=Format.Bold).append(from.toDescriptiveAnsiString())
                .format(Color.White).append(".extend(").format(format=Format.Bold).append(extending.toDescriptiveAnsiString())
                .format(Color.White).append(") ").format().toString()
    }

    override fun toString(): String = "$from.extend($extending)"
}

/**
 * Configuration is a base binding layer for a [Scope].
 *
 * @param name of the configuration. Specified by the variable name this project was declared at.
 * @param projectRoot at which this [Project] is located at in the filesystem
 * @see BindingHolder for info about how the values are bound
 */
class Project internal constructor(val name: String, internal val projectRoot: Path?, archetypes:Array<out Archetype>)
    : BindingHolder(), WithDescriptiveString, JsonWritable {

    /**
     * @return [name]
     */
    override fun toString(): String = name

    /**
     * @return [name] at [projectRoot]
     */
    override fun toDescriptiveAnsiString(): String =
            StringBuilder().format(format = Format.Bold).append(name)
                    .format(Color.White).append('/').format().toString()

    override fun JsonWriter.write() {
        writeValue(name, String::class.java)
    }

    /**
     * Scope for this [Project]. This is where scopes start.
     *
     * @see evaluate to use this
     */
    internal val projectScope: Scope = run {
        val holders = ArrayList<BindingHolder>(1 + archetypes.size * 2)
        holders.add(this)

        // Iterate through archetypes, most important first
        var i = archetypes.lastIndex
        while (i >= 0) {
            var archetype = archetypes[i--]

            while (true) {
                holders.add(archetype)
                archetype = archetype.parent ?: break
            }
        }

        Scope(name, holders, null)
    }

    /**
     * Evaluate the [action] in the scope of this [Project].
     * This is the entry-point to the key-evaluation mechanism.
     *
     * Keys can be evaluated only inside the [action].
     *
     * @param configurations that should be applied to this [Project]'s [Scope] before [action] is run in it.
     *          It is equivalent to calling [Scope.using] directly in the [action], but more convenient.
     */
    inline fun <Result> evaluate(vararg configurations:Configuration, action:Scope.()->Result):Result {
        try {
            return startEvaluate(this, configurations, null).run(action)
        } finally {
            endEvaluate()
        }
    }

    inline fun <Result> evaluate(configurations:List<Configuration>, action:Scope.()->Result):Result {
        try {
            return startEvaluate(this, null, configurations).run(action)
        } finally {
            endEvaluate()
        }
    }

    companion object {

        @Volatile
        private var currentlyEvaluatingThread:Thread? = null
        private var currentlyEvaluatingNestLevel = 0

        /**
         * Prepare to evaluate some keys.
         * Must be closed with [endEvaluate].
         *
         * Exactly one of [configurationsArray] and [configurationsList] must be non-null.
         *
         * @param project that is the base of the scope
         * @return scope that should be used to evaluate the action
         * @see evaluate
         */
        @PublishedApi
        internal fun startEvaluate(project:Project,
                                   configurationsArray: Array<out Configuration>?,
                                   configurationsList:List<Configuration>?):Scope {
            // Ensure that this thread is the only thread that can be evaluating
            synchronized(this@Companion) {
                val currentThread = Thread.currentThread()
                if (currentlyEvaluatingThread == null) {
                    currentlyEvaluatingThread = currentThread
                } else if (currentlyEvaluatingThread != currentThread) {
                    throw IllegalStateException("Can't evaluate from $currentThread, already evaluating from $currentlyEvaluatingThread")
                }
            }

            // Prepare scope to use
            var scope = project.projectScope
            if (configurationsArray != null) {
                for (config in configurationsArray) {
                    scope = scope.scopeFor(config)
                }
            } else {
                for (config in configurationsList!!) {
                    scope = scope.scopeFor(config)
                }
            }

            currentlyEvaluatingNestLevel++
            return scope
        }

        @PublishedApi
        internal fun endEvaluate() {
            currentlyEvaluatingNestLevel--
            assert(currentlyEvaluatingNestLevel >= 0)
            if (currentlyEvaluatingNestLevel == 0) {
                // Release the thread lock
                synchronized(this@Companion) {
                    assert(currentlyEvaluatingThread == Thread.currentThread())
                    currentlyEvaluatingThread = null
                }
            }
        }
    }
}

/**
 * Contains a collection of default key settings, that are common for all [Project]s of this archetype.
 *
 * Archetype usually specifies which languages can be used and what the compile output is.
 *
 * @param name used mostly for debugging
 * @see Archetypes for more info about this concept
 */
class Archetype internal constructor(val name: String, val parent:Archetype?) : BindingHolder() {

    override fun toDescriptiveAnsiString(): String {
        val sb = StringBuilder()
        if (parent != null) {
            sb.format(Color.White).append('(')
            val parentStack = ArrayList<Archetype>()
            var parent = this.parent
            while (parent != null) {
                parentStack.add(parent)
                parent = parent.parent
            }
            for (i in parentStack.indices.reversed()) {
                sb.append(parentStack[i].name).append("//")
            }
            sb.append(')')
        }
        sb.format(format = Format.Bold).append(name).append("//").format()
        return sb.toString()
    }

    override fun toString(): String = "$name//"
}

/**
 * Defines a part of scope in the scope linked-list structure.
 * Scope allows to query the values of bound [Keys].
 * Each scope is internally formed by an ordered list of [BindingHolder]s.
 *
 * @param scopeBindingHolders list of holders contributing to the scope's holder stack. Most significant holders first.
 * @param scopeParent of the scope, only [Project] may have null parent.
 */
class Scope internal constructor(
        private val name: String,
        val scopeBindingHolders: List<BindingHolder>,
        val scopeParent: Scope?) {

    private val configurationScopeCache: MutableMap<Configuration, Scope> = java.util.HashMap()

    private inline fun traverseHoldersBack(action: (BindingHolder) -> Unit) {
        var scope = this
        while (true) {
            scope.scopeBindingHolders.forEach(action)
            scope = scope.scopeParent ?: break
        }
    }

    private fun addReverseExtensions(to:ArrayList<BindingHolder>, of:BindingHolder) {
        traverseHoldersBack { holderToBeExtended ->
            if (holderToBeExtended !is Configuration) {
                return@traverseHoldersBack
            }
            val extension = of.configurationExtensions[holderToBeExtended] ?: return@traverseHoldersBack

            addReverseExtensions(to, extension)
            to.add(extension)
        }
    }

    @PublishedApi
    internal fun scopeFor(configuration: Configuration): Scope {
        val scopes = configurationScopeCache
        synchronized(scopes) {
            return scopes.getOrPut(configuration) {
                // Most significant holder first
                val newScopeHolders = ArrayList<BindingHolder>()

                // Add extensions in [configuration], which should be applied based on the content of this scope
                addReverseExtensions(newScopeHolders, configuration)

                // Now add scope holders from parents, while resolving other extensions
                var conf = configuration
                while (true) {
                    // Configuration may have been extended, add extensions
                    traverseHoldersBack { holder ->
                        val extension = holder.configurationExtensions[configuration] ?: return@traverseHoldersBack
                        // Does this extension contain more extensions that are now applicable?
                        addReverseExtensions(newScopeHolders, extension)
                        newScopeHolders.add(extension)
                    }

                    // Extensions added, now add the configuration itself
                    newScopeHolders.add(conf)

                    // Add configuration's parents, if any, with lesser priority than the configuration itself
                    conf = conf.parent ?: break
                }

                Scope(configuration.name, newScopeHolders, this)
            }
        }
    }

    @PublishedApi
    internal fun scopeFor(anonymousConfiguration: AnonymousConfiguration): Scope {
        // Cannot be extended
        anonymousConfiguration.locked = true // Here because of visibility rules
        return Scope("<anonymous>", listOf(anonymousConfiguration), this)
    }

    /**
     * Run the [action] in a scope, which is created by layering [configurations] over this [Scope].
     */
    @Suppress("unused")
    inline fun <Result> Scope.using(vararg configurations: Configuration, action: Scope.() -> Result): Result {
        var scope = this
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return scope.action()
    }

    /**
     * Run the [action] in a scope, which is created by layering [configurations] over this [Scope].
     */
    @Suppress("unused")
    inline fun <Result> Scope.using(configurations: Collection<Configuration>, action: Scope.() -> Result): Result {
        var scope = this
        for (configuration in configurations) {
            scope = scope.scopeFor(configuration)
        }
        return scope.action()
    }

    /**
     * Run the [action] in a scope, which is created by layering [configuration] over this [Scope].
     */
    @Suppress("unused")
    inline fun <Result> Scope.using(configuration: Configuration, action: Scope.() -> Result): Result {
        return scopeFor(configuration).action()
    }

    /**
     * Run the [action] in a scope, which is created by layering new anonymous configuration,
     * created by the [anonInitializer], over this [Scope].
     *
     * @param anonInitializer initializer of the anonymous scope. Works exactly like standard [Configuration] initializer
     */
    @Suppress("unused")
    inline fun <Result> Scope.using(anonInitializer: AnonymousConfiguration.() -> Unit,
                                    action: Scope.() -> Result): Result {
        val anonConfig = AnonymousConfiguration(this)
        anonConfig.anonInitializer()
        // Locking is deferred to scopeFor because of visibility rules for inlined functions
        return scopeFor(anonConfig).action()
    }

    private fun <Value : Output, Output> getKeyValue(key: Key<Value>, otherwise: Output, useOtherwise: Boolean): Output {
        val listener = activeKeyEvaluationListener

        listener?.keyEvaluationStarted(this, key)

        // Evaluate
        var foundValue: Value? = key.defaultValue
        var foundValueValid = key.hasDefaultValue
        var holderOfFoundValue:BindingHolder? = null
        val allModifiersReverse: ArrayList<BoundKeyValueModifier<Value>> = ArrayList()

        var scope: Scope? = this

        searchForValue@ while (scope != null) {
            // Retrieve the holder
            @Suppress("UNCHECKED_CAST")
            for (holder in scope.scopeBindingHolders) {
                val holderModifiers = holder.modifierBindings[key] as ArrayList<BoundKeyValueModifier<Value>>?
                if (holderModifiers != null && holderModifiers.isNotEmpty()) {
                    listener?.keyEvaluationHasModifiers(scope, holder, holderModifiers.size)

                    allModifiersReverse.addAllReversed(holderModifiers)
                }

                val boundValue = holder.binding[key] as BoundKeyValue<Value>?
                if (boundValue != null) {
                    // Unpack the value
                    try {
                        foundValue = boundValue()
                    } catch (t:Throwable) {
                        try {
                            listener?.keyEvaluationFailedByError(t, true)
                        } catch (suppressed:Throwable) {
                            t.addSuppressed(suppressed)
                        }
                        throw t
                    }

                    foundValueValid = true
                    holderOfFoundValue = holder
                    break@searchForValue
                }
            }

            scope = scope.scopeParent
        }

        if (foundValueValid) {
            @Suppress("UNCHECKED_CAST")
            var result = foundValue as Value

            // Apply modifiers
            try {
                for (i in allModifiersReverse.indices.reversed()) {
                    result = allModifiersReverse[i].invoke(this, result)
                }
            } catch (t:Throwable) {
                try {
                    listener?.keyEvaluationFailedByError(t, false)
                } catch (suppressed:Throwable) {
                    t.addSuppressed(suppressed)
                }
                throw t
            }

            // Done
            listener?.keyEvaluationSucceeded(key, scope, holderOfFoundValue, result)

            return result
        }

        if (useOtherwise) {
            listener?.keyEvaluationFailedByNoBinding(true, otherwise)
            return otherwise
        } else {
            listener?.keyEvaluationFailedByNoBinding(false, null)
            throw WemiException.KeyNotAssignedException(key, this@Scope)
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get(): Value {
        @Suppress("UNCHECKED_CAST")
        return getKeyValue(this, null, false) as Value
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set. */
    fun <Value : Else, Else> Key<Value>.getOrElse(unset: Else): Else {
        return getKeyValue(this, unset, true)
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns `null` if no value set. */
    fun <Value> Key<Value>.getOrNull(): Value? {
        return getKeyValue(this, null, true)
    }

    /**
     * @return [Project] that is at the root of this [Scope]
     */
    fun scopeProject():Project {
        // Project is always in the scope without parent
        var scope = this
        while (true) {
            scope = scope.scopeParent ?: break
        }
        for (holder in scope.scopeBindingHolders) {
            // Should be the first one
            if (holder is Project) {
                return holder
            }
        }
        throw IllegalStateException("No Project in Scope")
    }

    /**
     * Forget cached values stored in this and descendant caches.
     *
     * @return amount of values forgotten
     */
    internal fun cleanCache(): Int {
        var sum = configurationScopeCache.values.sumBy { it.cleanCache() }

        for (holder in scopeBindingHolders) {
            for (value in holder.binding.values) {
                if (value is CachedBoundValue) {
                    value.cleanCache()
                    sum++
                }
            }
        }

        return sum
    }

    private fun buildToString(sb: StringBuilder) {
        scopeParent?.buildToString(sb)
        sb.append(name)
        if (scopeParent == null) {
            sb.append('/')
        } else {
            sb.append(':')
        }
    }

    /**
     * @return scope in the standard syntax, i.e. project/config1:config2:
     */
    override fun toString(): String {
        val sb = StringBuilder()
        buildToString(sb)
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Scope

        if (name != other.name) return false
        if (scopeBindingHolders != other.scopeBindingHolders) return false
        if (scopeParent != other.scopeParent) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + scopeBindingHolders.hashCode()
        result = 31 * result + (scopeParent?.hashCode() ?: 0)
        return result
    }
}

private val LOG: Logger = LoggerFactory.getLogger("BindingHolder")

/**
 * Holds [Key] value bindings (through [BoundKeyValue]),
 * key modifiers (through [BoundKeyValueModifier]),
 * and [ConfigurationExtension] extensions (through [ConfigurationExtension]).
 *
 * Also provides ways to set them during the object's initialization.
 * After the initialization, the holder is locked and no further modifications are allowed.
 *
 * [BindingHolder] instances form building elements of a [Scope].
 */
sealed class BindingHolder : WithDescriptiveString {

    internal val binding = HashMap<Key<*>, BoundKeyValue<Any?>>()
    internal val modifierBindings = HashMap<Key<*>, ArrayList<BoundKeyValueModifier<Any?>>>()
    internal val configurationExtensions = HashMap<Configuration, ConfigurationExtension>()
    internal var locked = false

    private fun ensureUnlocked() {
        if (locked) throw IllegalStateException("Binding holder $this is already locked")
    }

    /**
     * Bind given [value] to the receiver [Key] for this scope.
     * [value] will be evaluated every time someone queries receiver key and this [BindingHolder] is
     * the topmost in the query [Scope] with the key bound.
     *
     * If the key already has [BoundKeyValue] bound in this scope, it will be replaced.
     *
     * @see modify
     */
    infix fun <Value> Key<Value>.set(value: BoundKeyValue<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val old = binding.put(this as Key<Any>, value as BoundKeyValue<Any?>)
        if (old != null) {
            LOG.debug("Overriding previous value bound to {} in {}", this, this@BindingHolder)
        }
    }

    /**
     * Add given [valueModifier] to the list of receiver key modifiers for this scope.
     *
     * When the key is queried and obtained in this or any less significant [BindingHolder] in the [Scope],
     * [valueModifier] are all evaluated on the obtained value, from those in least significant [BindingHolder]s
     * first, those added earlier first, up to the last-added, most-significant modifier in [Scope].
     *
     * First modifier will receive as an argument result of the [BoundKeyValue], second the result of first, and so on.
     * Result of last modifier will be then used as a definitive result of the key query.
     *
     * @param valueModifier to be added
     * @see set
     */
    infix fun <Value> Key<Value>.modify(valueModifier: BoundKeyValueModifier<Value>) {
        ensureUnlocked()
        @Suppress("UNCHECKED_CAST")
        val modifiers = modifierBindings.getOrPut(this as Key<Any>) { ArrayList() } as ArrayList<BoundKeyValueModifier<*>>
        modifiers.add(valueModifier)
    }

    /**
     * Extend given configuration so that when it is accessed with this [BindingHolder] in [Scope],
     * given [ConfigurationExtension] will be queried for bindings first.
     *
     * @param configuration to extend
     * @param initializer that will be executed to populate the configuration
     */
    @Suppress("MemberVisibilityCanPrivate")
    fun extend(configuration: Configuration, initializer: ConfigurationExtension.() -> Unit) {
        ensureUnlocked()
        val extensions = this@BindingHolder.configurationExtensions
        val extension = extensions.getOrPut(configuration) { ConfigurationExtension(configuration, this) }
        extension.locked = false
        extension.initializer()
        extension.locked = true
    }

    /**
     * [extend] multiple configurations at the same time.
     * ```
     * extendMultiple(a, b, c, init)
     * ```
     * is equivalent to
     * ```
     * extend(a, init)
     * extend(b, init)
     * extend(c, init)
     * ```
     */
    fun extendMultiple(vararg configurations: Configuration, initializer: ConfigurationExtension.() -> Unit) {
        for (configuration in configurations) {
            extend(configuration, initializer)
        }
    }

    //region Modify utility methods
    /**
     * Add a modifier that will add the result of [additionalValue] to the resulting collection.
     *
     * @see modify
     */
    inline infix fun <Value, Coll : Collection<Value>> Key<Coll>.add(crossinline additionalValue: BoundKeyValue<Value>) {
        this.modify { collection ->
            val mutable = collection.toMutable()
            mutable.add(additionalValue())
            @Suppress("UNCHECKED_CAST")
            mutable as Coll
        }
    }

    /**
     * Add a modifier that will add elements of the result of [additionalValues] to the resulting collection.
     *
     * @see modify
     */
    inline infix fun <Value, Coll : Collection<Value>> Key<Coll>.addAll(crossinline additionalValues: BoundKeyValue<Iterable<Value>>) {
        this.modify { collection ->
            val mutable = collection.toMutable()
            mutable.addAll(additionalValues())
            @Suppress("UNCHECKED_CAST")
            mutable as Coll
        }
    }

    /**
     * Add a modifier that will remove the result of [valueToRemove] from the resulting collection.
     *
     * Defined on [Set] only, because operating on [List] may have undesired effect of removing
     * only one occurrence of [valueToRemove].
     *
     * @see modify
     */
    inline infix fun <Value, Coll : Set<Value>> Key<Coll>.remove(crossinline valueToRemove: BoundKeyValue<Value>) {
        this.modify { collection ->
            val mutable = collection.toMutable()
            mutable.remove(valueToRemove())
            @Suppress("UNCHECKED_CAST")
            mutable as Coll
        }
    }

    /**
     * Add a modifier that will remove elements of the result of [valuesToRemove] from the resulting collection.
     *
     * @see modify
     */
    inline infix fun <Value, Coll : Collection<Value>> Key<Coll>.removeAll(crossinline valuesToRemove: BoundKeyValue<Iterable<Value>>) {
        this.modify { collection ->
            val mutable = collection.toMutable()
            mutable.removeAll(valuesToRemove())
            @Suppress("UNCHECKED_CAST")
            mutable as Coll
        }
    }
    //endregion

    //region CompilerFlags utility methods
    /**
     * @see BindingHolder.plusAssign
     * @see BindingHolder.minusAssign
     */
    operator fun <Type> Key<CompilerFlags>.get(flag: CompilerFlag<Collection<Type>>): CompilerFlagKeySetting<Collection<Type>> {
        return CompilerFlagKeySetting(this, flag)
    }

    /**
     * Modify [CompilerFlags] to set the given compiler [flag] to the given [value].
     *
     * @see modify
     */
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: Type) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to set the given compiler [flag] to the given [value]
     * that will be evaluated as if it was a key binding.
     *
     * @see modify
     */
    operator fun <Type> Key<CompilerFlags>.set(flag: CompilerFlag<Type>, value: BoundKeyValue<Type>) {
        this.modify { flags: CompilerFlags ->
            flags[flag] = value()
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to add given [value] to the collection
     * assigned to the compiler flag of [CompilerFlagKeySetting].
     *
     * @see modify
     */
    operator fun <Type> CompilerFlagKeySetting<Collection<Type>>.plusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag].let {
                flags[flag] = if (it == null) listOf(value) else it + value
            }
            flags
        }
    }

    /**
     * Modify [CompilerFlags] to remove given [value] from the collection
     * assigned to the compiler flag of [CompilerFlagKeySetting].
     *
     * @see modify
     */
    @Suppress("MemberVisibilityCanPrivate")
    operator fun <Type> CompilerFlagKeySetting<Collection<Type>>.minusAssign(value: Type) {
        key.modify { flags: CompilerFlags ->
            flags[flag]?.let {
                flags[flag] = it - value
            }
            flags
        }
    }

    /**
     * Boilerplate for [BindingHolder.plusAssign] and [BindingHolder.minusAssign].
     */
    class CompilerFlagKeySetting<Type> internal constructor(
            internal val key: Key<CompilerFlags>,
            internal val flag: CompilerFlag<Type>)
    //endregion

    /**
     * Bind this key to values that itself holds, under given configurations.
     */
    internal infix fun <Value> Key<Set<Value>>.setToUnionOfSelfIn(configurations: Scope.()->Iterable<Configuration>) {
        this set {
            val configurationsIter = configurations().iterator()
            if (!configurationsIter.hasNext()) {
                emptySet()
            } else {
                var result = using(configurationsIter.next()) { this@setToUnionOfSelfIn.get() }
                while (configurationsIter.hasNext()) {
                    val mutableResult = result.toMutable()
                    mutableResult.addAll(using(configurationsIter.next()) { this@setToUnionOfSelfIn.get() })
                    result = mutableResult
                }
                result
            }
        }
    }

    /**
     * Bind this key to values that itself holds, under given configurations.
     */
    internal infix fun <Value> Key<List<Value>>.setToConcatenationOfSelfIn(configurations: Scope.()->Iterable<Configuration>) {
        this set {
            val configurationsIter = configurations().iterator()
            if (!configurationsIter.hasNext()) {
                emptyList()
            } else {
                var result = using(configurationsIter.next()) { this@setToConcatenationOfSelfIn.get() }
                while (configurationsIter.hasNext()) {
                    val mutableResult = result.toMutable()
                    mutableResult.addAll(using(configurationsIter.next()) { this@setToConcatenationOfSelfIn.get() })
                    result = mutableResult
                }
                result
            }
        }
    }

    /**
     * One line string, using only White foreground for non-important stuff and Bold for important stuff.
     */
    abstract override fun toDescriptiveAnsiString(): String
}

/**
 * @see useKeyEvaluationListener
 */
@Volatile
private var activeKeyEvaluationListener:WemiKeyEvaluationListener? = null

/**
 * Execute [action] with [listener] set to listen to any key evaluations that are done during this time.
 *
 * Only one listener may be active at any time.
 */
fun <Result>useKeyEvaluationListener(listener: WemiKeyEvaluationListener, action:()->Result):Result {
    if (activeKeyEvaluationListener != null) {
        throw WemiException("Failed to apply KeyEvaluationListener, someone already has listener applied")
    }
    try {
        activeKeyEvaluationListener = listener
        return action()
    } finally {
        assert(activeKeyEvaluationListener === listener) { "Someone has applied different listener during action()!" }
        activeKeyEvaluationListener = null
    }
}

/**
 * Called with details about key evaluation.
 * Useful for closer inspection of key evaluation.
 *
 * Keys are evaluated in a tree, the currently evaluated key is on a stack.
 * @see keyEvaluationStarted for more information
 */
interface WemiKeyEvaluationListener {
    /**
     * Evaluation of a key has started.
     *
     * This will be always ended with [keyEvaluationFailedByNoBinding], [keyEvaluationFailedByError] or
     * with [keyEvaluationSucceeded] call. Between those, [keyEvaluationHasModifiers] can be called
     * and more [keyEvaluationStarted]-[keyEvaluationSucceeded]/Failed pairs can be nested, even recursively.
     *
     * This captures the calling stack of key evaluation.
     *
     * @param fromScope in which scope is the binding being searched from
     * @param key that is being evaluated
     */
    fun keyEvaluationStarted(fromScope: Scope, key: Key<*>)

    /**
     * Called when evaluation of key on top of the key evaluation stack will use some modifiers, if it succeeds.
     *
     * @param modifierFromScope in which scope the modifier has been found
     * @param modifierFromHolder in which holder inside the [modifierFromScope] the modifier has been found
     * @param amount of modifiers added from this scope-holder
     */
    fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder:BindingHolder, amount:Int)

    /**
     * Called when evaluation of key on top of the key evaluation stack used some special feature,
     * such as retrieval from cache.
     *
     * @param feature short uncapitalized human readable description of the feature, for example "from cache"
     * @see FEATURE_READ_FROM_CACHE
     * @see FEATURE_WRITTEN_TO_CACHE
     */
    fun keyEvaluationFeature(feature:String)

    /**
     * Evaluation of key on top of key evaluation stack has been successful.
     *
     * @param key that just finished executing, same as the one from [keyEvaluationStarted]
     * @param bindingFoundInScope scope in which the binding of this key has been found, null if default value
     * @param bindingFoundInHolder holder in [bindingFoundInScope] in which the key binding has been found, null if default value
     * @param result that has been used, may be null if caller considers null valid
     */
    fun <Value>keyEvaluationSucceeded(key: Key<Value>,
                                      bindingFoundInScope: Scope?,
                                      bindingFoundInHolder: BindingHolder?,
                                      result: Value)

    /**
     * Evaluation of key on top of key evaluation stack has failed, because the key has no binding, nor default value.
     *
     * @param withAlternative user supplied alternative will be used if true (passed in [alternativeResult]),
     *                          [wemi.WemiException.KeyNotAssignedException] will be thrown if false
     */
    fun keyEvaluationFailedByNoBinding(withAlternative:Boolean, alternativeResult:Any?)

    /**
     * Evaluation of key or one of the modifiers has thrown an exception.
     * This means that the key evaluation has failed and the exception will be thrown.
     *
     * This is not invoked when the exception is [wemi.WemiException.KeyNotAssignedException] thrown by the key
     * evaluation system itself. [keyEvaluationFailedByNoBinding] will be called for that.
     *
     * @param exception that was thrown
     * @param fromKey if true, the evaluation of key threw the exception, if false it was one of the modifiers
     */
    fun keyEvaluationFailedByError(exception:Throwable, fromKey:Boolean)

    companion object {
        /** [keyEvaluationFeature] to signify that this value has been read from cache */
        const val FEATURE_READ_FROM_CACHE = "from cache"
        /** [keyEvaluationFeature] to signify that this value has not been found in cache, but was stored there for later use */
        const val FEATURE_WRITTEN_TO_CACHE = "to cache"
    }
}

/**
 * Special [BoundKeyValue] for values that should be evaluated only once per whole binding,
 * regardless of Scope. Use only for values with no dependencies on any modifiable input.
 *
 * Example:
 * ```kotlin
 * projectName set Static("MyProject")
 * ```
 *
 * @see LazyStatic for values that are in this nature, but their computation is not trivial
 */
class Static<Value>(private val value:Value) : (Scope) -> Value {
    override fun invoke(ignored: Scope): Value {
        activeKeyEvaluationListener?.keyEvaluationFeature("static")
        return value
    }
}

/**
 * Special [BoundKeyValue] for values that should be evaluated only once, but lazily.
 * Similar to [Static] in nature of supported values.
 *
 * Example:
 * ```kotlin
 * heavyResource set LazyStatic { createHeavyResource() }
 * ```
 */
class LazyStatic<Value>(generator:()->Value) : (Scope) -> Value {
    private var generator:(()->Value)? = generator
    private var cachedValue:Value? = null

    override fun invoke(scope: Scope): Value {
        val generator = this.generator
        val value:Value
        @Suppress("UNCHECKED_CAST")
        if (generator != null) {
            value = generator.invoke()
            this.cachedValue = value
            this.generator = null
            activeKeyEvaluationListener?.keyEvaluationFeature("first lazy static")
        } else {
            activeKeyEvaluationListener?.keyEvaluationFeature("lazy static")
            value = this.cachedValue as Value
        }
        return value
    }
}

/** Bound values that cache their content should implement this interface to let `clean` command clean it. */
interface CachedBoundValue {
    fun cleanCache()
}

/** Specialization of [Cached] for single value input, with values cached per input.
 * Do not use when many different temporary inputs are expected, as it may cause a memory leak.
 *
 * Example:
 * ```kotlin
 * kotlinCompiler set CachedBy(kotlinVersion) { version -> createKotlinCompilerForVersion(version) }
 * ```
 */
class CachedBy<Value, By>(private val byKey:Key<By>, private val valueProducer:(By)->Value) : (Scope) -> Value, CachedBoundValue {

    private val cache = ArrayMap<By, Value>()

    override fun invoke(scope: Scope): Value {
        val byValue = scope.run {
            byKey.get()
        }

        var createdNew = false
        val result = cache.getOrPut(byValue) {
            createdNew = true
            valueProducer(byValue)
        }

        if (!createdNew) {
            activeKeyEvaluationListener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_READ_FROM_CACHE)
        } else {
            activeKeyEvaluationListener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_WRITTEN_TO_CACHE)
        }

        return result
    }

    override fun cleanCache() {
        cache.clear()
    }
}

/** BoundValue that is cached based on used inputs.
 *
 * Inputs + result is stored per scope. When [Cached] is queried for a value, all stored inputs from all scopes
 * are checked for a match. If one is found, that value is returned. Otherwise a new value is generated
 * and stored for later use.
 *
 * [valueProducer] is not called with [Scope], so it is not possible to directly [Scope.get].
 * This is intentional, as the retrieval should happen inside [CachedEvaluation.use], so that [Cached] may capture its inputs.
 *
 * Then, when result is to be produced, it should be done so through [CachedEvaluation.produce].
 *
 * Example:
 * ```kotlin
 * expensiveOperation set Cached {
 *      // Code here always happens
 *      val someFiles = use { inputFiles.get() }
 *      val option = use { expensiveOperationOptions.get() }
 *
 *      produce {
 *          // Code here happens only when inputs retrieved through `use` before were not seen yet
 *          performExpensiveOperation(someFiles, option)
 *      }
 * }
 * ```
 */
class Cached<Value>(private val valueProducer:CachedEvaluation<Value>.()->Value) : (Scope) -> Value, CachedBoundValue {

    internal val cacheByScope = WeakHashMap<Scope, SoftReference<CacheEntry<*>>>()

    override fun invoke(scope: Scope): Value {
        return valueProducer.invoke(CachedEvaluation(scope, cacheByScope))
    }

    override fun cleanCache() {
        cacheByScope.clear()
    }

    @CacheDsl
    class CachedEvaluation<Value> internal constructor(
            @PublishedApi internal val scope:Scope,
            private val cacheByScope:MutableMap<Scope, SoftReference<CacheEntry<*>>>) {

        private val cacheEntry = CacheEntry<Value>()

        /** Obtain value bound to key in [obtain] and return it.
         * Same value is also wrapped with [wrapValueForCache] and remembered through [inputValueUsed].
         * @see useAs */
        inline fun <Value> use(obtain:Scope.() -> Value):Value {
            return useAs(obtain, ::wrapValueForCache)
        }

        /** Obtain value bound to key in [obtain] and return it.
         * Same value is also wrapped with [wrap] and remembered through [inputValueUsed].
         * The wrapping is done to add additional comparison logic to used types.
         * @see use
         * @see wrapValueForCache for wrapping rationale */
        inline fun <Value> useAs(obtain:Scope.() -> Value, wrap:(Value) -> Any?):Value {
            val value = scope.run(obtain)
            inputValueUsed(wrap(value))
            return value
        }

        /** Wrap the value to be more useful when comparing old inputs to new inputs.
         *
         * Current implementation only wraps instances of [Path], [File] and [LocatedPath] (and their [List]s and [Set]s)
         * into [PathCacheEntry], so that when file changes, equal [Path] instances won't equal anymore. */
        fun wrapValueForCache(value:Any?):Any? {
            return when (value) {
                is Path ->
                    PathCacheEntry(value)
                is File ->
                    PathCacheEntry(value)
                is LocatedPath ->
                    PathCacheEntry(value)
                is List<*> ->
                    value.map(::wrapValueForCache)
                is Set<*> ->
                    value.mapTo(HashSet(), ::wrapValueForCache)
                else ->
                    value
            }
        }

        /** Manually put input value to cache key list.
         * Used internally by [use] methods, but may be also used when the inputs do not come from the key system.
         * (Though something like `val envInput = use { readInputFile() }`) would be also valid. */
        fun inputValueUsed(value:Any?) {
            cacheEntry.add(value)
        }

        /** Returns cached value that corresponds to given inputs, or [EvaluatingCached] if no such cached value found. */
        @PublishedApi
        internal fun getCachedResult():Any? {
            for (valueReference in cacheByScope.values) {
                val value = valueReference.get() ?: continue
                if (value == cacheEntry) {
                    activeKeyEvaluationListener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_READ_FROM_CACHE)
                    cacheByScope[scope] = valueReference
                    return value.cachedValue
                }
            }
            return EvaluatingCached
        }

        /** After [cacheResult] returned [EvaluatingCached] and new value was evaluated */
        @PublishedApi
        internal fun cacheResult(newResultToCache:Value) {
            cacheEntry.cachedValue = newResultToCache
            cacheByScope[scope] = SoftReference<CacheEntry<*>>(cacheEntry)
            activeKeyEvaluationListener?.keyEvaluationFeature(WemiKeyEvaluationListener.FEATURE_WRITTEN_TO_CACHE)
        }

        /** Return what [producer] returns, unless it was already evaluated for these inputs,
         * in which case return that previous result and don't evaluate anything. */
        inline fun produce(producer:EvaluatingCached.()->Value):Value {
            val cachedResult = getCachedResult()
            if (cachedResult !== EvaluatingCached) {
                @Suppress("UNCHECKED_CAST")
                return cachedResult as Value
            }

            val newResult = producer.invoke(EvaluatingCached)
            cacheResult(newResult)
            return newResult
        }
    }

    internal class CacheEntry<Value> : ArrayList<Any?>() {
        var cachedValue:Value? = null
    }

    /** Object representing a snapshot of Path, based on time of last modification.  */
    class PathCacheEntry private constructor(val path:Path, val lastModified:Long) {
        constructor (path:Path) : this(path, path.lastModified.toMillis())
        constructor (file:File) : this(file.toPath(), file.lastModified())
        constructor (located:LocatedPath) : this(located.file)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PathCacheEntry

            if (path != other.path) return false
            if (lastModified != other.lastModified) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + lastModified.hashCode()
            return result
        }
    }

    /** Used to prevent accidentally accessing wrong scopes when evaluating cached keys.
     * @see EvaluatingCached */
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
    @DslMarker
    private annotation class CacheDsl

    /**
     * Special receiver used in [CachedEvaluation.produce] to disallow accessing [CachedEvaluation.use] methods.
     */
    /* Also used as an unique value returned by [CachedEvaluation.getCachedResult] to signify that cache is empty. */
    @CacheDsl
    object EvaluatingCached
}
