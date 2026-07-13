package io.tranto.core.storages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Filesystem-backed {@link StorageInterface}: every logical key maps to a file under a base
 * directory, and object URIs use the {@code tranto:///key} scheme. This is the storage used by the
 * standalone runtime; a distributed deployment swaps in an S3/GCS/Azure implementation behind the
 * same interface with no task changes.
 */
public class LocalStorage implements StorageInterface {

    private static final String SCHEME = "tranto";

    private final Path base;

    public LocalStorage(final Path base) {
        this.base = base.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.base);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage base " + this.base, e);
        }
    }

    @Override
    public InputStream get(final URI uri) throws IOException {
        return Files.newInputStream(resolve(uri));
    }

    @Override
    public boolean exists(final URI uri) {
        return Files.exists(resolve(uri));
    }

    @Override
    public URI put(final String key, final InputStream data) throws IOException {
        String normalized = normalize(key);
        Path target = base.resolve(normalized);
        Files.createDirectories(target.getParent());
        try (data) {
            Files.copy(data, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return uriFor(normalized);
    }

    @Override
    public boolean delete(final URI uri) throws IOException {
        return Files.deleteIfExists(resolve(uri));
    }

    @Override
    public List<URI> list(final String prefix) throws IOException {
        Path root = base.resolve(normalize(prefix));
        Path searchRoot = Files.isDirectory(root) ? root : base;
        if (!Files.exists(searchRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(searchRoot)) {
            return walk.filter(Files::isRegularFile)
                .map(p -> uriFor(base.relativize(p).toString().replace('\\', '/')))
                .toList();
        }
    }

    @Override
    public long size(final URI uri) throws IOException {
        return Files.size(resolve(uri));
    }

    private Path resolve(final URI uri) {
        String key = uri.getScheme() == null ? uri.getPath() : uri.getSchemeSpecificPart();
        Path resolved = base.resolve(normalize(key)).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Path traversal outside storage base: " + uri);
        }
        return resolved;
    }

    private static String normalize(final String key) {
        String k = key == null ? "" : key.replace('\\', '/');
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return k;
    }

    private static URI uriFor(final String key) {
        return URI.create(SCHEME + ":///" + key);
    }
}
