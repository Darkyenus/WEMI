![WEMI Build System](logo_doc.svg)  
# General Design

## Tenets
- Strive for simplicity
- Be explicit, do not hide things
- Have speed and low overhead in mind at all times
- Don't introduce dependencies unless necessary, prefer to (down)load them at runtime, when needed
- Don't be afraid of breaking changes, at least during development

## Directory layout
Project backed by Wemi will usually have following directory structure:
```
/wemi                   <- Wemi executable
/src/main/kotlin/
/src/main/resources/
/src/test/java/         <- Production/Test Java/Kotlin sources (Maven convention)
/build/build.kt         <- Build script file (All files with .kt extension in this folder will be used)
/build/logs/            <- Folder with logs of Wemi runs
/build/cache/           <- Folder with internal Wemi cache and compiled classes of source files
/build/artifacts/       <- Folder with assembled artifacts, such as fat-jars, or anything else that you wish to produce
```
Wemi executable is stored directly in the project's root and should be included in version control.
It is deliberately kept as small as possible, for this purpose. Doing it this way has multiple advantages:
builds are reliably kept identical, even after a long time, no complicated installation, simple troubleshooting, etc.

Sources are kept in a Maven-like structure: `/src/<build-type>/<kind>/`. It is possible to fully reconfigure it, if needed.
By default, Wemi is configured to use `test` and `main` build type and looks for sources in `kotlin` and `java` folders.
(Note that you can freely put Kotlin sources in `java` folder and vice-versa, as usual.)
Resources are looked for in `resources`-kind folder.

The `build` folder is where all Wemi-related things are stored. Build script files, that is any files directly under
`build/` with `.kt` extension (and not beginning with a `.`), are automatically detected as such and compiled together.
Compiled scripts are kept, along with other internal cache items in the `cache` directory. This is also where Wemi
stores what compilers produce for the main sources, as configured by the `outputClassesDirectory` key.
When the Wemi is run, all of its output is kept in the `logs` directory (this is usually, but not always, very similar
to what you see in the command line user interface). And in `artifacts`, final products of the project are stored
- if there are any to store.

This all is true for a typical, single-project layout. It is possible to have multiple projects in a single Wemi directory
(or just one with a different root). In that case, all except `src/` would be in the same place. `src/` is, by default,
in the project's root.

## Internal architecture
At the core of WEMI are four intertwined concepts.
- Key
- Scope
- Project
- Configuration

*Projects* and *configurations* hold values addressable by *keys*.
*Scope* is made out of single *project* and an ordered list of zero or more
*configurations* and allows to query values bound to *keys*.

### Keys
Key is represented by an instance of `Key<V>`. Each key type has a different
name, type (generic `V`) and is represented by a single instance.
The key also holds its human readable description.

Keys themselves typically don't hold any values, but are used in `BindingHolder`s, which can be though of as a
maps, with `Key<V>`s for keys and `() -> V` functions for values. These functions are then invoked only when needed.

Examples of `BindingHolder`s are `Project`s and `Configuration`s. When they are defined, it is possible to bind values
in them to arbitrary keys through following syntax:
```kotlin
someKey set { println("evaluating some key"); "some value: " + 1 }
```
Curly braces are Kotlin's syntax for a lambda - the function which is then stored
in the `BindingHolder` and evaluated when needed.

There are two major consequences of this design:
- Both static settings and dynamic tasks have the same syntax. For example, there is a `compile` key, which, when evaluated,
compiles the project, or `projectName`, which returns the project's name (used for example when publishing to Maven repository)
- Keys may refer to many different values, depending on the context in which it is queried.
For example `sourceFiles` may return different set of files, depending on whether tests are being compiled or not.
This is controlled through *configurations*.

Key object itself may also hold a default value, which is used when no other `BindingHolder` in the context (*scope*),
has any value bound to the key. This, however, is always an immutable object set during the declaration of the key.
For example, keys which are of some `Collection` type often have an empty collection as a default value.

Key definition looks like this:
```kotlin
val compile by key<Path>("Compile sources and return the result")
```
Here variable `compile` is of type `Key<Path>` and holds a reference to the created key.
`compile` is also used as the name of the key and would be used to invoke this key from the user interface.
Key's description is also specified during declaration and so can be the default value,
custom output printer and other miscellaneous options.

*Key declaration leverages Kotlin's delegate system (through `KeyDelegate<Value>` object) which the reason for the
`by` syntax and a mechanism through which Wemi can assign the variable name to the key, for its invocation from outside.*

Wemi provided keys are defined in `wemi.Keys` object. When defining custom keys in your build script,
do so at the top level, as they must all be known after the build script is loaded
 (i.e. not in `Project` specification, not in functions, etc.).

Key evaluation can be triggered either from the outside (as a command line parameter or through command line interface),
or as a part of evaluation of the keys value:
```kotlin
compile set {
	val sources = sourceFiles.get() // `sourceFiles` and `javaCompiler` are keys
	val compiler = javaCompiler.get()
	
	compiler.compile(sources)
}
```
`Key.get()` evaluates the key in the same context (*scope*) in which the current value is being evaluated, and
**it is available only when declaring a value for some key** (i.e. not from arbitrary functions).
Evaluation triggered from the outside are referred to as **top-level evaluations**.

Bound values (i.e. functions) are evaluated only when needed and when it can be assumed, that their output has changed.
The values returned by a key invocation are cached (at most for the duration of *wemi* instance lifetime).
The logic behind the caching is: When a key, which has already been evaluated in the same context (*scope*) is evaluated:
1. If the last evaluation happened during this top-level evaluation, it is always kept
2. If any key which was used during the last evaluation has changed, it is re-evaluated
3. If any user-specified trigger reports that it should be, it is re-evaluated

Example of user-specified triggers:
```kotlin
notCachedKey set {
	val path:Path = doSomeOperationWhichShouldNotBeCached()

	expiresNow() // Never cached
	expiresWhen { someCondition() } // Cache is invalidated when someCondition() is true
	expiresWith(path) // Cache is invalidated when path file is modified from the current state 
}
```

### Projects & Configurations
Build script will typically define one or more projects and may define any number of configurations, if needed.
Projects and configurations are the only objects capable of holding values bound to keys.
These two objects are very similar in their usage. Bound values are set during their declaration and can't be changed,
added nor removed later. Through these objects, bound values are typically only set, but not read/queried.
*Values are bound to projects and configurations, but queried through **scopes**.*

Configurations may be derived from each other. It is then said that the configuration has a parent.
The parent configuration is then used during the value *querying*.

Project definition may look like this:
```kotlin
val calculator by project {
    // Initializer
    projectGroup set {"com.example"}
    projectName set {"scientific-calculator"}
    projectVersion set {"3.14"}

    libraryDependencies add { dependency("com.example:math:6.28") }

    mainClass set { "com.example.CalculatorMainKt" }
}
```
Like the key, project and configuration (see below) is also declared using a `by` syntax.
Above example declares a project named `calculator`, which is for later reference in code stored
in the `calculator` variable. Rest of the example shows how the keys are bound.

When in the project or configuration initializer, write `<key> set { <value> }`. This works only in the scope of
project or configuration and the braces around value are mandatory and intentional - they signify that the `<value>` is not
evaluated immediately, but later and possibly multiple times. Keys of type `Collection` can be also set using the `add` command,
more on that in the section about *scopes*.

Configuration definition may look like this:
```kotlin
val compilingJava by configuration("Configuration used when compiling Java sources") {
	sourceRoots set { 
		val base = sourceBase.get()
		listOf(base / "kotlin", base / "java")
	}
}
```
As you can see, it is very similar to the project definition, and the similarity is internal as well.
The main difference to notice is that configuration has a description, like keys.

It is a soft convention to name configurations with the description of the activity in which the configuration will be used,
with the verb in *present participle* (ending with *-ing*). If the configuration is not used for an activity,
any descriptive name is fine.

Standard configurations are defined in `wemi.Configurations` object.

#### Archetypes
Each project, even when it is not specified explicitly, has an *Archetype*. Archetype is essentially a *configuration*,
that is applied *before* Project's own key bindings. Archetype usually defines default values for keys,
and which programming languages project can use. It can be specified when defining a new project.
Default archetype declares support for Java and Kotlin languages.

Standard archetypes are defined in `wemi.Archetypes` object.

### Scopes
Scopes are the heart of the *key evaluation* mechanism. They are not declared, but created at runtime, on demand.
They are **composed** out of one *project* and zero or more *configurations* in some order.

This reflects in the key invocation syntax, which is used in the user interface to query/invoke key values.
(Querying and invocation is the same operation, but it makes more sense to talk about querying for keys that bind settings
and about invocation for keys that bind tasks, but this is just a convention and there is no real distinction between the two.)
The basic syntax is as follows:
```regexp
(<project>"/")?(<config>":")*<key>
```
Where `<project>` is a name of some defined project, `<config>` is a name of some defined configuration and `<key>`
is a name of some defined key. For more information, see section `Query language` below.
For example `calculator/compiling:clean` would be a query to value bound to key `clean`,
in one configuration `compiling` and in the project `calculator`. While the project part is defined in the syntax as optional, each scope **must**
always have a project. When the project part is missing, the default project of the user interface is automatically used.

A key binding defined in code, i.e. `sourceRoots set { /* this */ }`, is always executed in some implicit scope, only
through which it is possible to query values of other keys - in that same scope. It is impossible (well, at least not advisable) to escape that scope.
For example, when the key binding is being evaluated in scope `myProject/testing:compiling:` it can't drop the `compiling:`
nor any other part of the scope stack. However, it can add more configurations on top of it, by wrapping the key evaluation in
`using (configuration) {}` clause. For example, given following key binding:
```kotlin
compile set {
	val comp = using (compiling) { compiler.get() }
	val compOptions = compileOptions.get()
	comp.compileWithOptions(compOptions)
}
```
evaluating `myProject/foo:compile` will then evaluate, in order tasks `myProject/foo:compiling:compiler` and
`myProject/foo:compileOptions`. `using` clauses can be freely nested.

Note that you can push only a configuration on the stack, it is not possible to change the project with it.
To see how to evaluate the key in an arbitrary configuration, you can check [how it is implemented in CLI](../src/main/kotlin/wemi/boot/CLI.kt).

### Configuration extensions
While the scope system outlined above is powerful, some things are still not possible to do easily in it.
For example, the `compile` key depends on two keys: `compiler` and `compileOptions`.
`compileOptions` is queried directly, but `compiler` is queried through `compiling`.
It is possible to override used `compileOptions` by overriding it in the project (when no other configuration is used).
But to override `compiler` one would have to put the override in the definition of `compiling` configuration, but that is not possible,
because configuration definitions are immutable. This is where configuration extensions come in to play.

To fix this, you can use `extend (<configuration>) {}` clause, when defining key bindings. For example, when overriding
`compiling:compiler` as seen in previous example, one would type:
```kotlin
extend (compiling) {
    compiler set { MyJavaCompiler() }
}
```
This will change the querying order when evaluating `compiling:compiler` to first check the new extension configuration
and only then, if the key is not bound, check the original configuration.
`extend`s, like `using`s, can be freely nested for even more specific overrides.

Specific semantics of this are subtle, but usually intuitive.

#### Querying order
When key in scope is queried, configurations and project in the scope is searched for the value binding in a specified order.
This order is created iteratively, each added layer (such as another `configuration:`) adds more binding holders
to be considered before falling back to the order before layer was added (`Scope` has `parent` parameter).
Root scope is always a scope of `Project`, constructed by:
1. Bindings of project itself
2. Bindings of topmost archetype, and others lower in priority, down to the base archetype
	1. Bindings of archetype's parent and then its parent etc.

Additional order after adding a configuration is:
1. Check if the added configuration contains any extensions of configurations already in `Scope`
	1. First recursively check if the extensions themselves have any applicable extensions and use them first
2. Traverse down the existing scope, looking for extensions targeting currently added configuration
	1. If there are any, search them for more specific extensions and add them as well, like in previous step
	2. Repeat for added configuration's parents
3. Search the added configuration itself
4. Search parent scope, while there is a parent scope
5. Check key's default value
6. Fail

When the binding is encountered, it is evaluated, returned and no more items in the order are checked.
There are multiple methods to query the value, they differ in the behavior of the last step, when no value is found.
Remember that the value bound is not directly the value, but a function that produces the value, from the scope.

For example, in project defined by this:
```kotlin
val color by key<String>("Color of an animal")

val arctic by configuration("When in snowy regions") {
	color set {"White"}
}

val wonderland by configuration("When in wonderland") {
    color set {"Rainbow"}
    
    extend (arctic) {
        color set {"Transparent"}
    }
}

val heaven by configuration("Like wonderland, but better", wonderland) {
    // Here heaven extends wonderland
    foxColor set { "Octarine" }
}

val fox by project {
	color set {"Red"}
}
```
| Query                | Will evaluate to |
|----------------------|------------------|
| `fox/color`         | Red          |
| `fox/arctic:color`         | White          |
| `fox/wonderland:color`         | Rainbow          |
| `fox/wonderland:arctic:color`         | Transparent          |
| `fox/arctic:wonderland:color`         | Rainbow          |
| `fox/heaven:color`         | Octarine          |
| `fox/heaven:arctic:color`         | Transparent          |

As mentioned above, there is one more feature, modifying, in our case appending, which is achieved using method `add` instead of `set`.
This is useful when you want to add more elements to a `Collection` bound to a key.
The querying for these keys is similar to how standard keys are used, but when the value bound by `set` is reached,
the order is queried from that point backwards and all `add` added elements are added to the resulting collection.
Note that there must be a point with some concrete set value, if all binding holders have only addition and none has
concrete binding, the query will fail.
Appending however is just a special case of modifying, which allows to arbitrarily modify the value returned by
querying deeper scope. To do that, use method `modify` - the previous value will be available as an argument to the key-setting function.

## Build script
All of the build definitions are inside a build script. Build script is a file, typically in a root directory of a project,
with `.kt` extension. The usual name is `build.kt`.
Build script is written entirely in [Kotlin](http://kotlinlang.org) and is also compiled as such,
no pre-processing is done to modify its text. Anything that is a valid Kotlin is allowed.

The build file can declare its own library dependencies, through specially formatted lines, *directives*,
which are detected by the loader but are considered to be comments in Kotlin. These library dependencies fulfill
the work of plugins in other build systems, but there is nothing special about them, they are just standard
Java/Kotlin/JVM libraries.

### Build script directives
Build script can start with a number of directives, in a form of file annotations.
Note that since they are not parsed by the Kotlin compiler, be conservative in their syntax (keep them on single line).

#### Repository directive
```kotlin
@file:BuildDependencyRepository("<repo-name>", "<url>")
```
Given maven2 repository will be used when searching for libraries using the *library dependency directive*.
This is not the same as repositories of the compiled project, see `repositories` key for that.

#### Library dependency directive
```kotlin
@file:BuildDependency("<group>:<name>:<version>")
or
@file:BuildDependency("<group>", "<name>", "<version>")
```
Add library as a dependency for this build script. The library is directly available in the whole build script.
Library is searched for in the repositories specified by *repository directive*.
This is not the same as library dependencies of the compiled project, see `libraryDependencies` key for that.

#### Classpath dependency directive
```kotlin
@file:BuildClasspathDependency("<jar path>")
```
Add specified jar as a dependency for this build script. Unmanaged version of *library dependency directive*.
The path may be absolute, or relative to the project's root directory.
This is not the same as unmanaged dependencies of the compiled project, see `unmanagedDependencies` key for that.

## Input system
A small but important part of the Wemi's functionality is its input system. Most keys take their inputs by evaluating
other keys, but sometimes it is more beneficial to ask the user. For example the `runMain` key can be used
to execute arbitrary main class from the project's classpath, interactively, without having to change the `mainClass`
key.

Input system can be queried through the [`read`](../src/main/kotlin/wemi/Input.kt) method.
It can be asked to provide arbitrary type of value for given input query. Input query consists of three parts:
1. *Input key* - is any simple string, which specifies what is the query about.
    For example `main` when asking about main class. The string should be a valid Java identifier.
2. *Prompt* - arbitrary human readable string that will be shown to the user when asked to provide the input text.
    It should explain the purpose and form of the collected input text. 
3. *Validator* - a function that converts the string given by the user (or stored in `input`, more on that later)
    and returns either arbitrary object to be used as the user's response, or an error message to be shown to
    the user.

This will typically ask the user interactively, but sometimes, it is more beneficial to supply the answer to a prompt
even before the question is asked. The *input key* is used to specify what question is being answered in advance.
For example, to start the project's `foo.bar.Main` class from the shell, use `$ ./wemi "runMain main=foo.bar.Main"`.
(More on this syntax in the section **Query language**.)

However, often it is known beforehand that the key will ask only a single question. For example, the default
implementation of `runMain` key will only ever ask about `main` input key. So it is possible to omit the `main=`
part of the query and use simply `"runMain foo.bar.Main"`.

To specify the inputs from code, supply them as a parameters to `get()`. For example:
```kotlin
{
    // With free input
    runMain.get("" to "foo.bar.Main")
    
    // With named input
    runMain.get("main" to "foo.bar.Main")
}
```

When there is multiple stored inputs, first the named ones are tried, then the free ones, then the user is asked.
If named input does not satisfy the validator, it is skipped and the user is notified.
Free inputs added together are considered only after the one before them is consumed.
Consumed *free* inputs are never considered again. However it is not advised to use more than one free input.

## Command line user interface
WEMI features a simple interactive user interface in a form of Read-Evaluate-Print-Loop, which is launched by default.
It accepts key queries, which are then evaluated, and a few commands. Refer to the `help` command.
To exit, use the `exit` command, or simply EOF (Ctrl-D).

REPL launches by default only when there is no query string present. To request interactivity with initial query,
use the `-i` (`-interactive`) flag, e.g. `$ ./wemi -i run`.

### Query language
Queries form the main entry point to any Wemi functionality invocation and basic examples of this simple language
can be seen throughout this document. A single query fits on a line, with following grammar:
```
<query> := <command>
        | <query> ';' <command>

<command> := <scoped-task> <input>*

<scoped-task> := (<project> '/')? (<configuration> ':')* <task>

<input> := <named-input> | <free-input>
<named-input> := <input-key> '=' <input-text>
<free-input> := <input-text>

<project>, <configuration>, <task>, <input-key> := Valid Java identifier
<input-text> := Arbitrary text
```
(See [TaskParser](../src/main/kotlin/wemi/boot/TaskParser.kt) for more information about the grammar.)

Project, configuration and task items were explained in the section `Scope`.
Named input and free input items allow to specify input text to use when the evaluated keys require input.
For more information about input, see section `Input` above.

Examples of valid queries (one per line):
```
run
test:run
game/run
application/test:run
runMain com.example.Main
runMain main=com.example.Main
packResources; run
```
Separators (i.e. `/`, `:`, `=`, `;`, and whitespace, though technically any character) may be escaped with
backslash (`\`) to treat them as a regular letter character. Note that this is useful only for input text,
as other parts of the query do not support these characters anyway.

When in REPL, it is also possible to quote text with double quotes (`"quoted"`) to automatically escape every control
character, except for `\` and `"` itself, so it is still possible to use escapes and escape `"` to include it in the input text.

Initial query passed to Wemi through process arguments is parsed very similarly, but whitespace is not treated as
a separator and argument boundaries are used instead. That way it is possible to leverage shell's own quoting rules.

### Debugging tools
To see which keys does some key depend on, prefix its invocation with `trace `, for example `./wemi trace run`.

## Distribution and installation
WEMI is distributed as a runnable .jar file called `wemi`, which is also a valid `.sh` file.
This file should be as small as possible, as it will be checked into the version control system of the project,
like wrappers of other build systems. This should ensure that building and updating of the build system and its
files will be as painless as possible.

The prepended `.sh` file conforms to the standard `sh` shell, for maximum compatibility.
