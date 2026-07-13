package io.tranto.plugin.tools;

import io.tranto.core.models.Plugin;
import io.tranto.plugin.tools.crypto.Hash;
import io.tranto.plugin.tools.csv.Read;
import io.tranto.plugin.tools.datetime.Format;
import io.tranto.plugin.tools.encoding.Base64Decode;
import io.tranto.plugin.tools.encoding.Base64Encode;
import io.tranto.plugin.tools.file.Write;
import io.tranto.plugin.tools.json.Parse;
import io.tranto.plugin.tools.text.RegexExtract;
import io.tranto.plugin.tools.text.Replace;

import java.util.List;

/**
 * The catalogue of the ten local-first utility tasks shipped by this module. Listed explicitly for
 * convenient in-process registration (mirroring {@code CorePlugins}); the engine can equally
 * discover them via the ServiceLoader manifest the annotation processor writes at build time.
 */
public final class ToolPlugins {

    private ToolPlugins() {
    }

    /** @return every task class shipped in this module. */
    public static List<Class<? extends Plugin>> all() {
        return List.of(
            // crypto & encoding
            Hash.class,
            Base64Encode.class,
            Base64Decode.class,
            // structured data
            Parse.class,
            Read.class,
            // text
            RegexExtract.class,
            Replace.class,
            // files
            Write.class,
            io.tranto.plugin.tools.file.Read.class,
            // date-time
            Format.class
        );
    }
}
