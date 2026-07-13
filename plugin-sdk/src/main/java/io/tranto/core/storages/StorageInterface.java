package io.tranto.core.storages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * The internal storage abstraction: where task inputs/outputs, files and large payloads live. A
 * task never touches the filesystem directly — it reads and writes through here, so the same plugin
 * works whether storage is the local FS, S3, GCS or Azure Blob.
 *
 * <p>Keys are namespaced logical paths (e.g. {@code /dev/myflow/abc/output.txt}); {@code put}
 * returns a {@code kestra://}-style {@link URI} that later resolves back to the stored bytes. This
 * is a stable SDK surface: implementations live in the engine or in storage plugins.</p>
 */
public interface StorageInterface {

    /** @return an input stream over the object at {@code uri}. */
    InputStream get(URI uri) throws IOException;

    /** @return true if an object exists at {@code uri}. */
    boolean exists(URI uri);

    /**
     * Store {@code data} under a logical {@code key}, returning the URI that addresses it.
     *
     * @param key  the logical path (leading slash optional)
     * @param data the bytes to store (consumed and closed)
     * @return the storage URI of the written object
     */
    URI put(String key, InputStream data) throws IOException;

    /** Delete the object at {@code uri}. @return true if something was deleted. */
    boolean delete(URI uri) throws IOException;

    /** @return the URIs of objects whose key starts with {@code prefix}. */
    List<URI> list(String prefix) throws IOException;

    /** @return the size in bytes of the object at {@code uri}. */
    long size(URI uri) throws IOException;
}
