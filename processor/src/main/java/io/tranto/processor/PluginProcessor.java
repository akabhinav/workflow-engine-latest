package io.tranto.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Build-time processor that finds every concrete {@code @Plugin} class and emits a
 * {@code ServiceLoader} manifest so the runtime discovers plugins without classpath reflection.
 *
 * <p>This is what makes an external plugin JAR "just work": drop this processor on its annotation
 * path, and its {@code @Plugin} tasks/triggers register themselves via {@code META-INF/services}.
 * Abstract bases (Task, AbstractTrigger) are skipped — only instantiable classes become entries.</p>
 */
@SupportedAnnotationTypes("io.tranto.core.models.annotations.Plugin")
public class PluginProcessor extends AbstractProcessor {

    private static final String SERVICE_FILE = "META-INF/services/io.tranto.core.models.Plugin";

    private final Set<String> discovered = new LinkedHashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.CLASS) {
                    continue;
                }
                TypeElement type = (TypeElement) element;
                if (type.getModifiers().contains(Modifier.ABSTRACT)
                    || type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
                    continue; // abstract bases and non-static inner classes can't be ServiceLoader providers
                }
                discovered.add(processingEnv.getElementUtils().getBinaryName(type).toString());
            }
        }

        if (roundEnv.processingOver() && !discovered.isEmpty()) {
            writeServiceFile();
        }
        return false; // don't claim the annotation; let other processors (e.g. Lombok) see it too
    }

    private void writeServiceFile() {
        try {
            FileObject file = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", SERVICE_FILE);
            try (Writer writer = new BufferedWriter(file.openWriter())) {
                for (String className : discovered) {
                    writer.write(className);
                    writer.write('\n');
                }
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Tranto: wrote " + discovered.size() + " plugin(s) to " + SERVICE_FILE);
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Tranto: failed to write plugin service file: " + e.getMessage());
        }
    }
}
