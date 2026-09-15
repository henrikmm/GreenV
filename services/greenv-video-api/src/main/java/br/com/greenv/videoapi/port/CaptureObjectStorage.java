package br.com.greenv.videoapi.port;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Durable binary/object storage addressed only by provider-neutral object keys. */
public interface CaptureObjectStorage {

    StoredObject put(
            String objectKey,
            InputStream input,
            String expectedSha256,
            long maximumBytes);

    boolean exists(String objectKey);

    byte[] read(String objectKey, long maximumBytes);

    /**
     * The same bytes as {@link #read}, without holding them all at once.
     *
     * <p>Every other object this API serves is a JPEG or a JSON packet, tens of kilobytes to a
     * few megabytes, and a byte array is the simplest thing that works. A reconstruction is not:
     * one segment of 13 September 2026 kept a 15.4 MB mesh and a 53.1 MB point cloud, and the
     * API runs on one vCPU and 2 GiB. Reading those into the heap to write them straight back
     * out costs the whole file per concurrent download for nothing.
     *
     * <p>The caller closes the content. {@code maximumBytes} is checked against the object's
     * declared length before a stream is opened, so an object larger than the caller will accept
     * fails before any bytes move.
     */
    ObjectContent open(String objectKey, long maximumBytes);

    record StoredObject(String objectKey, String sha256, long bytes) {
    }

    /** An object opened for reading: how long it is, and a stream the caller must close. */
    record ObjectContent(String objectKey, long bytes, InputStream stream) implements Closeable {
        @Override
        public void close() throws IOException {
            stream.close();
        }
    }
}
