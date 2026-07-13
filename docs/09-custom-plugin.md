# Writing & running a custom plugin

Tranto is extended entirely through **plugins** — a plugin is just a class that implements one of the
SDK's capability interfaces (`RunnableTask`, `FlowableTask`, `AbstractTrigger`, `TaskRunner`, …).
Because of the **stability wall** (docs/08), a plugin compiles against `tranto-plugin-sdk` *only* —
it never sees the engine, so adding plugins can't destabilise the platform.

This guide builds a real, third-party task and runs it. A complete, tested copy lives in the
[`plugin-example/`](../plugin-example) module (`com.acme.tranto.text.Slugify`) — proven end-to-end by
`SlugifyPluginTest` and runnable from the CLI.

---

## 1. Create a Maven module

A plugin JAR depends on the SDK with scope **`provided`** (the runtime supplies it) and puts the
Tranto **annotation processor** on its compiler path. That processor writes the ServiceLoader
manifest at build time, so the plugin is discovered at runtime with zero engine changes.

```xml
<dependencies>
  <dependency>
    <groupId>io.tranto</groupId>
    <artifactId>tranto-plugin-sdk</artifactId>
    <scope>provided</scope>              <!-- the ONLY compile dependency you need -->
  </dependency>
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>
</dependencies>

<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
        <annotationProcessorPaths>
          <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>
          <path><groupId>io.tranto</groupId><artifactId>tranto-processor</artifactId><version>${project.version}</version></path>
        </annotationProcessorPaths>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## 2. Write the plugin

Extend `Task`, implement `RunnableTask<YourOutput>`, annotate with `@Plugin`. Render dynamic
properties through the `RunContext`, and return a typed `Output`. Note the `com.acme.*` package: the
plugin genuinely cannot import anything from the engine.

```java
package com.acme.tranto.text;

import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import lombok.Getter; import lombok.NoArgsConstructor; import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true) @Getter @NoArgsConstructor
@Plugin(title = "Turn text into a URL-safe slug")
public class Slugify extends Task implements RunnableTask<Slugify.SlugOutput> {

    @PluginProperty(dynamic = true)      // "dynamic" => supports {{ expressions }}
    private String text;

    @Override
    public SlugOutput run(final RunContext runContext) throws Exception {
        String rendered = runContext.render(text);
        String slug = rendered.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        runContext.logger().info("slugified '{}' -> '{}'", rendered, slug);
        return new SlugOutput(slug);
    }

    public record SlugOutput(String slug) implements Output {}
}
```

## 3. Build it

```
mvn -pl plugin-example -am install
```

The processor emits `META-INF/services/io.tranto.core.models.Plugin` containing your class:

```
com.acme.tranto.text.Slugify
```

That manifest is what makes the plugin auto-discoverable — no registration code anywhere.

## 4. Use it in a flow

Reference the plugin by its fully-qualified class name in `type:`; its output is available to
downstream tasks as `{{ outputs.<id>.<field> }}`.

```yaml
id: slug_demo
namespace: demo
inputs:
  - id: title
    type: STRING
    defaults: "Hello, World! Tranto 2026"
tasks:
  - id: make_slug
    type: com.acme.tranto.text.Slugify
    text: "{{ inputs.title }}"
  - id: show
    type: io.tranto.plugin.core.log.Log
    message: "slug is: {{ outputs.make_slug.slug }}"
```

## 5. Run it

**Via the CLI** — drop the plugin JAR into a directory and point `--plugins` at it. Each JAR is
loaded in an isolated, child-first `PluginClassLoader` and discovered via ServiceLoader:

```
cp plugin-example/target/tranto-plugin-example-*.jar examples/plugins/
java -jar cli/target/tranto.jar run examples/slug-demo.yaml --plugins examples/plugins
```

```
Plugins   : 1 external plugin(s) loaded from examples/plugins
Flow      : demo.slug_demo
State     : SUCCESS
   - make_slug -> SUCCESS
   - show -> SUCCESS
Outputs   : {slug=hello-world-tranto-2026}
```

**Programmatically** — scan the classpath and run on the engine (see `SlugifyPluginTest`):

```java
PluginRegistry registry = new SimplePluginRegistry();
new PluginScanner().scanAndRegister(registry, Thread.currentThread().getContextClassLoader());
Flow flow = new YamlFlowParser(new JacksonMapper(registry)).parse(yaml);
try (StandaloneEngine engine = new StandaloneEngine()) {
    Execution ex = engine.run(flow, Map.of(), Duration.ofSeconds(10));   // SUCCESS
}
```

---

## What just happened (the extensibility loop)

1. **Compile** against the SDK only → the stability wall guarantees isolation.
2. **Annotation processor** lists your `@Plugin` class in `META-INF/services`.
3. **`PluginScanner`** (ServiceLoader) discovers it at runtime — no engine change.
4. **`PluginClassLoader`** isolates the JAR (shared SDK types delegate to the parent, so
   `instanceof Task` still holds across the boundary).
5. **The engine** resolves `type:` → your class, renders its properties, runs it, and captures its
   typed output.

Triggers (`AbstractTrigger`), flowables (`FlowableTask`), and task runners (`TaskRunner`) are authored
the same way — implement the interface, annotate with `@Plugin`, ship the JAR.
