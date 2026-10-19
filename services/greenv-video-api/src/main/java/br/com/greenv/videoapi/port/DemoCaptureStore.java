package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where demo captures and their timelines are kept.
 *
 * <p>The pipeline runs on every replica at once and survives scale-to-zero, so the database is
 * the only place its progress may live. A replica works on a capture only while it holds that
 * capture's lease, and every write it makes is conditional on still holding it: a replica that
 * stalled past its lease finds its writes refused instead of overwriting the one that took over.
 */
public interface DemoCaptureStore {

    void insert(DemoCapture capture);

    Optional<DemoCapture> find(String captureId);

    /** Newest first, each with its whole timeline. */
    List<DemoCapture> latest(int limit);

    /**
     * Moves a capture to {@code uploading} from {@code created}, or from a {@code failed} that
     * never stored a video — an upload that broke off, which the phone retries under the same id.
     * False when it is not waiting for its video, which is how two uploads of the same capture
     * are told apart.
     */
    boolean beginUpload(String captureId, DemoPhase uploading);

    /**
     * Writes the capture's mutable fields and appends {@code phase}, which becomes its state.
     *
     * @param phase null to write the fields alone
     * @param leaseOwner null for writes made outside the pipeline (the upload); otherwise the
     *     write happens only if this owner still holds the lease
     * @return false when the lease had passed to someone else and nothing was written
     */
    boolean save(DemoCapture capture, DemoPhase phase, String leaseOwner);

    /** Captures in one of {@code states} whose lease is free or has run out, oldest first. */
    List<String> claimable(List<String> states, Instant now, int limit);

    /** Takes the lease if it is free or has run out. False when another replica holds it. */
    boolean claim(String captureId, String owner, Instant now, Instant until);

    /**
     * Extends a lease this owner still holds, so a long step is not taken over halfway. False
     * when it has passed to someone else.
     */
    boolean renew(String captureId, String owner, Instant until);

    void release(String captureId, String owner);

    /** Captures announced before {@code cutoff}, oldest first. */
    List<DemoCapture> createdBefore(Instant cutoff, int limit);

    void delete(String captureId);
}
