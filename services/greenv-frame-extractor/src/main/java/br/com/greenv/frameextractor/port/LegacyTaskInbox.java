package br.com.greenv.frameextractor.port;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import java.util.Optional;

/** Pull-based delivery boundary for the deprecated whole-video workflow. */
public interface LegacyTaskInbox {

    Optional<ClaimedTask> claim();

    void complete(ClaimedTask task);

    void fail(ClaimedTask task);

    void retry(ClaimedTask task);

    /** The receipt is opaque to the application and interpreted only by the queue adapter. */
    record ClaimedTask(String receipt, FrameExtractionRequest request) {
    }
}
