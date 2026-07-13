# Tranto — Plugin Ecosystem & the Stability Contract

> **The platform will host 500+ plugins, each in its own repo (like Kestra).** Therefore the
> plugin-facing contract MUST be stable: adding, building, or shipping a plugin must NEVER
> require changing the platform, and a plugin built today must keep working as the engine
> evolves. This doc defines the contract that guarantees that. Dash diagrams only.

---

## 1. The requirement

```
   500+ plugin repos  ---- compile against ---->  a STABLE published SDK artifact
        (separate)                                (semver, frozen per major version)
           |                                              ^
           | package as JAR                               | the ENGINE may change freely
           v                                               | as long as the SDK stays compatible
   drop JAR in plugins/ dir  ---- ServiceLoader ---->  Tranto runtime loads it
```

Two independent release cadences:
- **Platform/engine** — evolves fast (perf, internals, new services). Private.
- **Plugin SDK** — evolves slowly, backward-compatible within a major. Public API.

If those two are not separated, every engine change risks breaking 500 repos. So we separate
them by construction.

---

## 2. How Kestra does it (our reference)

```
   Kestra plugin build.gradle:
     compileOnly  io.kestra:core:${kestraVersion}      <- whole 'core' as compile-only
     -> plugin JAR does NOT bundle core (runtime provides it)
     -> plugin deps are classloader-isolated (no version clashes)
   Each plugin group = its own repo (from plugin-template), package io.kestra.plugin.<group>
```

Good: `compileOnly` + classloader isolation + separate repos + ServiceLoader. **We keep all of that.**

Weak spot: plugins compile against the *entire* `core` module — thousands of classes, engine
internals included. A plugin author can accidentally couple to something internal, and any
`core` change is a potential break. **We fix this** (see §4, divergence D11).

---

## 3. The Tranto stability boundary

We split the codebase into two hard zones with a wall between them:

```
  +===========================  PUBLIC (stable, semver) ==========================+
  |  tranto-model        annotations (@Plugin, @PluginProperty) + enums           |
  |  tranto-plugin-sdk   the interfaces a plugin implements/uses:                  |
  |                        Plugin, Task, RunnableTask, FlowableTask,               |
  |                        ExecutableTask, Trigger, Condition, TaskRunner,         |
  |                        RunContext (interface), Property, Output, Input types,  |
  |                        Storage (interface), and stable utility interfaces      |
  +==============================  THE WALL  =====================================+
  |  tranto-core         ENGINE: DefaultRunContext, VariableRenderer(Pebble),      |
  |  executor/scheduler/   PluginRegistry, PluginScanner, queue/repo/storage impls,|
  |  worker/webserver/...   the state machine — all IMPLEMENTATION.                |
  +===========================  PRIVATE (free to change) =========================+

  RULE:  plugins depend on  model + plugin-sdk  ONLY  (scope: provided/compileOnly).
  RULE:  plugins can NEVER see tranto-core or any service module.
  RULE:  the engine implements the SDK interfaces; it may change however it likes,
         as long as it still honours the SDK contract.
```

A plugin's whole world is `model` + `plugin-sdk`. That's the API surface we promise to keep
stable. Everything behind the wall is ours to rewrite.

---

## 4. Divergence D11 — a thin Plugin SDK (not the whole engine)

```
   Kestra:  plugin compileOnly -> io.kestra:core   (the entire engine)
   Tranto:  plugin provided    -> io.tranto:tranto-plugin-sdk + tranto-model  (interfaces only)

   WHY BETTER:
     - tiny, stable surface -> engine refactors don't break plugins
     - plugin authors can't accidentally couple to internals (they aren't on the classpath)
     - the SDK jar is small -> faster plugin builds, clearer docs
     - we can semver the SDK independently and loudly
   COST:
     - discipline: any class a plugin needs must be promoted to the SDK as an interface.
       That is the point — it forces a deliberate, reviewed public API.
```

The SDK is an **interface + value-type** module. Concrete behaviour (rendering, storage I/O,
metrics) is reached only through SDK interfaces that the engine injects at runtime via
`RunContext`.

---

## 5. What goes in the SDK (the promised surface)

```
  IMPLEMENTED BY PLUGINS (they write these):
    interface Plugin                         marker
    abstract  Task            (+ @Plugin)    id/type/timeout/retry/...
    interface RunnableTask<O extends Output> O run(RunContext)          -> worker
    interface FlowableTask<O extends Output> resolveNexts/resolveState  -> executor
    interface ExecutableTask<O>              subflow spawning
    abstract  Trigger         (+ subtypes: Polling/Realtime/Schedulable)
    interface Condition
    abstract  TaskRunner                     script execution backends
    interface Output                          typed task output (records)

  USED BY PLUGINS (the engine hands these in):
    interface RunContext    render(...), storage(), logger(), metric(), kv(), secret()
    class     Property<T>   a lazily-rendered typed value
    class     Input<T> + subtypes (STRING, INT, FILE, ...)
    interface Storage       kestra:// file ops (for storage plugins)
    utils:    small stable helper interfaces (no engine types leak)
```

Anything a plugin touches is here, as an interface or an immutable value type. Nothing else.

---

## 6. Plugin project shape (each repo / module)

```
  plugin-<group>/                 e.g. plugin-http, plugin-aws, plugin-postgres
    pom.xml
      <dependency> io.tranto:tranto-plugin-sdk   scope=provided   (compile-only, not bundled)
      <dependency> io.tranto:tranto-model         scope=provided
      <dependency> ...the plugin's OWN libs...    scope=compile    (bundled + isolated at runtime)
    src/main/java/io/tranto/plugin/<group>/
      package-info.java           @PluginSubGroup(title=..., categories=...)
      SomeTask.java               @Plugin ... implements RunnableTask<SomeTask.Output>
      SomeTrigger.java            @Plugin ... extends Trigger
    -> mvn package -> plugin-<group>.jar  (a "fat-ish" jar with its own deps, minus SDK)
    -> ServiceLoader entry written at build time by tranto-processor
```

- **One repo can host many plugins** (a "group"); packages are sub-groups. Same as Kestra.
- The plugin JAR bundles its own dependencies; at runtime a child-first `PluginClassLoader`
  isolates them so two plugins can use conflicting library versions safely.
- The core is `provided` → not bundled → no duplicate engine on the classpath.

---

## 7. The stability guarantees (what we promise plugin authors)

```
  WITHIN A MAJOR VERSION (e.g. sdk 1.x):
    - no SDK interface method is removed or changed incompatibly
    - new methods added to SDK interfaces come with DEFAULT implementations
      (so existing plugins still compile & run)
    - a plugin built against sdk 1.0 runs on any engine that ships sdk 1.x
  ACROSS MAJORS (1.x -> 2.0):
    - breaking changes allowed, batched, documented with a migration guide
    - engine supports the previous SDK major for a deprecation window
  ENFORCED BY:
    - japicmp (or revapi) API-diff check in CI -> a breaking SDK change FAILS the build
    - ArchUnit: no plugin-sdk class may import a tranto-core/engine class
    - a "sample plugins" repo compiled in CI against the current SDK as a canary
```

The engine team can refactor with confidence because the SDK check is the tripwire.

---

## 8. Updated module map (the wall made concrete)

```
  platform (BOM)
  model                <-- SDK (stable)
  plugin-sdk           <-- SDK (stable)   [NEW: the plugin contract]
  processor            <-- build-time ServiceLoader generation (+ future schema gen)
     |
     |  ================ THE WALL ================
     v
  core (engine)  depends on model + plugin-sdk, IMPLEMENTS the interfaces
  queue-jdbc / jdbc(+dialects) / storage-local
  executor / scheduler / worker / controller / indexer
  webserver / cli
  plugin-core          <-- our built-in plugins, dogfooding the SDK exactly like external ones
  script               <-- exec/TaskRunner, also just a plugin group on the SDK
```

**plugin-core is built ON the SDK, exactly like an external plugin.** That is the proof the SDK
is sufficient: if our own 100+ built-in tasks only need `model` + `plugin-sdk`, so will everyone
else's 500.

---

## 9. Why this directly answers "the platform should not change"

```
  Add a new plugin       -> new repo/module on the SDK. Platform untouched.  ✅
  Ship 500 plugins       -> 500 JARs. Platform untouched.  ✅
  Refactor the engine    -> SDK unchanged -> all 500 plugins still build & run.  ✅
  Grow the SDK           -> only additive (default methods) within a major.  ✅
```

The platform code is stable *with respect to plugins* by construction: plugins can't even see
it. The only shared surface is a small, semver-locked SDK with a CI tripwire.

Sources: Kestra plugin developer guide + plugin-template (compileOnly on core, separate repos,
classloader isolation, ServiceLoader discovery).
