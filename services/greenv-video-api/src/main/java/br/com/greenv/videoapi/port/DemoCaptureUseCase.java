package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoRun;
import br.com.greenv.videoapi.domain.DemoUploadReceipt;
import java.io.InputStream;
import java.util.List;

/**
 * The FIAP Next demo: a phone sends a short video, the server turns it into a point cloud, and a
 * public site shows every step.
 *
 * <p>Writes take the pairing code exactly as the {@code Authorization} header carried it, so the
 * check — present, well formed, equal in constant time — happens in one place. Reads are public:
 * the site is public and read-only, and a capture holds nothing but the demo video.
 */
public interface DemoCaptureUseCase {

    /** Whether this {@code Authorization} header carries the pairing code. Never throws. */
    boolean authorised(String authorization);

    /** Records the phone's metadata ({@code verge-phone-capture/1}, at most 64 KiB). */
    DemoCapture announce(String authorization, InputStream metadata);

    /**
     * Streams a capture's video to storage.
     *
     * @param contentLength what the request declared; a negative value means it declared none
     */
    DemoUploadReceipt receiveVideo(
            String authorization,
            String captureId,
            String contentType,
            long contentLength,
            InputStream body);

    DemoCapture capture(String captureId);

    /** The newest captures first. */
    List<DemoCapture> latest(int limit);

    DemoRun run(String captureId);

    /**
     * One of the demo's own objects, for deployments whose storage has no signed URLs of its own.
     * Refuses any key outside {@code demo/}.
     */
    CaptureObjectStorage.ObjectContent openObject(String objectKey);
}
