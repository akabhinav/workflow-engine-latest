package io.tranto.cli;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * CI tripwire for the stability wall (docs/08): built-in plugins compile against the SDK only, and
 * must never reach into engine internals. Maven's {@code provided} scope enforces this at build
 * time; this test enforces it structurally, so a future accidental dependency fails the build with a
 * clear message instead of silently coupling plugins to the engine.
 */
class ArchitectureTest {

    private static final JavaClasses PLUGIN_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("io.tranto.plugin");

    @Test
    void pluginsDoNotDependOnEngineInternals() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("io.tranto.plugin..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.tranto.core.queues..",
                "io.tranto.core.repositories..",
                "io.tranto.core.serializers..",
                "io.tranto.core.plugins..",
                "io.tranto.core.schedulers..",
                "io.tranto.core.jdbc.."
            )
            .because("built-in plugins must depend on the SDK only, never the engine (the stability wall)");

        rule.check(PLUGIN_CLASSES);
    }
}
