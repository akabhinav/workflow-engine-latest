package io.tranto.plugin.tools.crypto;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Computes a cryptographic digest of a string and returns it as a lowercase hex string.
 *
 * <p>The {@code algorithm} accepts any {@link MessageDigest} name available on the JDK
 * ({@code MD5}, {@code SHA-1}, {@code SHA-256} (default), {@code SHA-384}, {@code SHA-512}).
 * Use it for content checksums, cache keys, idempotency keys and deduplication.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Hash a string into a hex digest",
    examples = @Example(
        title = "Compute a SHA-256 checksum of a rendered value",
        code = {
            "id: checksum",
            "type: io.tranto.plugin.tools.crypto.Hash",
            "from: \"{{ outputs.download.body }}\"",
            "algorithm: SHA-256"
        }
    )
)
public class Hash extends Task implements RunnableTask<Hash.HashOutput> {

    /** The default digest algorithm when none is configured. */
    private static final String DEFAULT_ALGORITHM = "SHA-256";

    /** The value to hash. Pebble-rendered before hashing. */
    @PluginProperty(dynamic = true)
    private String from;

    /**
     * The {@link MessageDigest} algorithm name. Defaults to {@code SHA-256}. Common values:
     * {@code MD5}, {@code SHA-1}, {@code SHA-256}, {@code SHA-384}, {@code SHA-512}.
     */
    @PluginProperty(dynamic = true)
    private String algorithm;

    /** The charset the input is encoded with before hashing. Defaults to {@code UTF-8}. */
    @PluginProperty
    private String charset;

    /** When {@code true}, the hex digest is returned uppercase. Defaults to {@code false}. */
    @PluginProperty
    private Boolean uppercase;

    @Override
    public HashOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Hash requires a non-null 'from' value");
        }

        final String rendered = runContext.render(from);
        final String algo = algorithm != null && !algorithm.isBlank()
            ? runContext.render(algorithm).trim()
            : DEFAULT_ALGORITHM;
        final Charset cs = charset != null && !charset.isBlank()
            ? Charset.forName(charset.trim())
            : StandardCharsets.UTF_8;

        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algo);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unsupported hash algorithm '" + algo + "'", e);
        }

        final byte[] bytes = digest.digest(rendered.getBytes(cs));
        String hex = HexFormat.of().formatHex(bytes);
        if (Boolean.TRUE.equals(uppercase)) {
            hex = hex.toUpperCase(java.util.Locale.ROOT);
        }

        runContext.logger().debug("{} digest computed ({} bytes input)", algo, rendered.length());
        return new HashOutput(hex, algo);
    }

    /**
     * @param digest    the hex-encoded digest
     * @param algorithm the algorithm that produced it
     */
    public record HashOutput(String digest, String algorithm) implements Output {
    }
}
