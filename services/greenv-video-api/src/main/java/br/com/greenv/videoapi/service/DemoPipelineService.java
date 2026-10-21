package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import br.com.greenv.videoapi.domain.DemoPhases;
import br.com.greenv.videoapi.domain.DemoSampling;
import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import br.com.greenv.videoapi.port.DemoFrameSampler;
import br.com.greenv.videoapi.port.DepthJobRunner;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Walks each demo capture from {@code received} to {@code fetched}, writing every phase as it
 * goes.
 *
 * <p>The state machine lives in the database, not in this object. Each tick asks which captures
 * are waiting, takes a lease on one, does one step and writes down where it got to. That is what
 * lets the API run two replicas without doing the work twice, and scale to zero halfway through a
 * job without losing it: whichever replica wakes next reads the state and carries on.
 *
 * <p>Two kinds of step, with two lease lengths. Sampling and uploading the frames is one long
 * step — tens of seconds of ffmpeg on one vCPU — leased for minutes, and a capture whose replica
 * died mid-step is picked up again when the lease runs out, at most {@link #MAX_ATTEMPTS} times.
 * Following a depth job is many short steps, one RunPod status question each, leased briefly.
 *
 * <p>A job that outlives {@code greenv.demo.job-deadline} is cancelled at RunPod before the
 * capture is marked failed, because a serverless worker still running bills whether anybody is
 * waiting for its answer or not.
 */
@Service
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class DemoPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DemoPipelineService.class);

    /** A capture that killed its replica three times will kill the fourth. */
    static final int MAX_ATTEMPTS = 3;

    /**
     * Frames in flight to object storage at once. Each frame is three round trips, and one at a
     * time from Azure's Brazil South to R2 that came to 164 s for 96 frames on 21 Oct 2026
     * (capture cap-20261021-081752-83rl) - long enough for a warm GPU worker to go idle and stop.
     */
    static final int FRAME_UPLOAD_CONCURRENCY = 16;

    private final DemoCaptureStore store;
    private final CaptureObjectStorage storage;
    private final DemoFrameSampler sampler;
    private final DepthJobRunner depth;
    private final Clock clock;
    private final DemoProperties properties;
    /** Who holds a lease: this process, for as long as it lives. */
    private final String owner = "api-" + UUID.randomUUID();
    private final Lane jobLane = new Lane("greenv-demo-jobs");
    private final Lane samplingLane = new Lane("greenv-demo-sampling");
    private volatile boolean stopping;

    public DemoPipelineService(
            DemoCaptureStore store,
            CaptureObjectStorage storage,
            DemoFrameSampler sampler,
            DepthJobRunner depth,
            Clock clock,
            DemoProperties properties) {
        this.store = store;
        this.storage = storage;
        this.sampler = sampler;
        this.depth = depth;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Hands the work to the demo's own two threads and returns at once.
     *
     * <p>Spring runs every {@code @Scheduled} method of this API on one shared thread, and the
     * field pipeline's queue poller is one of them: a sampling step of a few minutes run here
     * would stall measurement results for as long. The two lanes are separate for the same
     * reason inside the demo — a second visitor's video being sampled must not freeze the first
     * one's job at {@code waking}. A lane still busy from the last tick is skipped, not queued.
     */
    @Scheduled(
            initialDelayString = "${greenv.demo.poll-initial-delay-ms:5000}",
            fixedDelayString = "${greenv.demo.poll-delay-ms:2000}")
    public void tick() {
        jobLane.offer(this::followJobs);
        samplingLane.offer(this::sampleNext);
    }

    void followJobs() {
        for (String captureId : store.claimable(DemoPhases.WITH_JOB, clock.instant(), 10)) {
            guarded(captureId, () -> followJob(captureId));
        }
    }

    void sampleNext() {
        for (String captureId : store.claimable(DemoPhases.BEFORE_JOB, clock.instant(), 1)) {
            guarded(captureId, () -> sampleAndSubmit(captureId));
        }
    }

    /**
     * Stops both lanes. A step cut short here is not marked failed: the process is going away,
     * not the capture, and the lease it leaves behind lets the next replica carry on.
     */
    @PreDestroy
    void stop() {
        stopping = true;
        jobLane.interrupt();
        samplingLane.interrupt();
        // Long enough for an interrupted step to give its lease back before the database goes.
        jobLane.await(Duration.ofSeconds(10));
        samplingLane.await(Duration.ofSeconds(10));
    }

    private void guarded(String captureId, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException unexpected) {
            log.error("[demo] {} step failed unexpectedly", captureId, unexpected);
        }
    }

    // -----------------------------------------------------------------------------------------
    // received → extracting → extracted → uploading-frames → waking | inferring

    void sampleAndSubmit(String captureId) {
        Instant now = clock.instant();
        if (!store.claim(captureId, owner, now, now.plus(properties.extractionLease()))) {
            return;
        }
        Path work = null;
        try {
            DemoCapture capture = store.find(captureId).orElse(null);
            if (capture == null || !DemoPhases.BEFORE_JOB.contains(capture.state()) || capture.video() == null) {
                return;
            }
            if (capture.attempts() >= MAX_ATTEMPTS) {
                fail(capture, "sampling was started " + capture.attempts()
                        + " times and never finished; giving up");
                return;
            }
            capture = write(capture.withAttempts(capture.attempts() + 1), DemoPhases.EXTRACTING, null);

            work = Files.createTempDirectory("greenv-demo-" + captureId + "-");
            Path video = work.resolve(capture.video().objectKey().substring(
                    capture.video().objectKey().lastIndexOf('/') + 1));
            try (CaptureObjectStorage.ObjectContent content =
                    storage.open(capture.video().objectKey(), properties.maxVideoBytes())) {
                Files.copy(content.stream(), video);
            }
            // The lease is renewed before each long part of the step, so a slow download, ffmpeg
            // or frame upload does not let a second replica start the same capture alongside.
            hold(captureId, properties.extractionLease());
            DemoSampling sampling = sampler.sample(
                    video, work.resolve("frames"), requestedFps(capture.meta()),
                    maxFrames(capture.meta()), properties.longEdge());
            capture = capture.withVideo(capture.video().withProbe(sampling.probe()));
            Map<String, Object> extracted = new LinkedHashMap<>();
            extracted.put("frames", sampling.frames().size());
            extracted.put("fps", round(sampling.effectiveFps()));
            extracted.put("capped", sampling.capped());
            extracted.put("requestedFrames", sampling.requestedCount());
            extracted.put("width", sampling.width());
            extracted.put("height", sampling.height());
            if (sampling.probe() != null && sampling.probe().durationS() != null) {
                extracted.put("durationS", round(sampling.probe().durationS()));
            }
            capture = write(capture, DemoPhases.EXTRACTED, extracted);

            long bytes = 0;
            for (Path frame : sampling.frames()) {
                bytes += Files.size(frame);
            }
            capture = write(capture, DemoPhases.UPLOADING_FRAMES,
                    Map.of("frames", sampling.frames().size(), "bytes", bytes));
            hold(captureId, properties.extractionLease());
            List<String> keys = uploadFrames(capture.id(), sampling.frames());
            capture = capture.withFrameKeys(keys);
            if (!store.save(capture, null, owner)) {
                throw new LeaseLost();
            }

            DepthJobRequest request = new DepthJobRequest(
                    capture.id(),
                    keys.stream()
                            .map(key -> new DepthJobRequest.Frame(key.substring(key.lastIndexOf('/') + 1), key))
                            .toList(),
                    round(sampling.effectiveFps()),
                    properties.processRes(),
                    properties.maxFrames(),
                    sampling.probe() == null ? null : sampling.probe().durationS());
            // Last chance to find the capture taken over before a GPU is woken for it: a job
            // submitted by a replica that then cannot record it is a job billed twice.
            hold(captureId, properties.extractionLease());
            DepthJobStatus started = depth.submit(request);
            capture = capture.withDepthJob(started.jobId(), clock.instant());
            switch (started.state()) {
                case QUEUED -> write(capture, DemoPhases.WAKING, Map.of("jobId", started.jobId()));
                // A warm worker can take the job before /run even answers. Either way the next
                // tick asks for its status, which is where a completed job is collected.
                case RUNNING, COMPLETED -> write(capture, DemoPhases.INFERRING, Map.of("jobId", started.jobId()));
                case FAILED -> {
                    capture = write(capture, DemoPhases.WAKING, Map.of("jobId", started.jobId()));
                    fail(capture, started.error());
                }
            }
        } catch (LeaseLost lost) {
            log.warn("[demo] {} lease passed to another replica mid-step; leaving it to them", captureId);
        } catch (IOException | RuntimeException exception) {
            if (stopping) {
                log.info("[demo] {} interrupted by shutdown; the next replica carries on", captureId);
                return;
            }
            log.warn("[demo] {} failed: {}", captureId, exception.getMessage());
            store.find(captureId).ifPresent(current -> fail(current, message(exception)));
        } finally {
            deleteTree(work);
            store.release(captureId, owner);
        }
    }

    /**
     * Puts each frame under {@code demo/<id>/sampled-frames/}, by the name ffmpeg gave it, several
     * at a time. A frame already there is left alone: it was written by an earlier attempt at this
     * same capture, from the same video, at the same position. The keys come back in frame order.
     */
    private List<String> uploadFrames(String captureId, List<Path> frames) throws IOException {
        ExecutorService uploads = Executors.newFixedThreadPool(
                Math.max(1, Math.min(FRAME_UPLOAD_CONCURRENCY, frames.size())));
        try {
            List<Future<String>> pending = new ArrayList<>(frames.size());
            for (Path frame : frames) {
                pending.add(uploads.submit(() -> uploadFrame(captureId, frame)));
            }
            List<String> keys = new ArrayList<>(frames.size());
            for (Future<String> upload : pending) {
                keys.add(upload.get());
            }
            return keys;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("frame upload interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) {
                throw io;
            }
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(failed.getCause());
        } finally {
            uploads.shutdownNow();
        }
    }

    private String uploadFrame(String captureId, Path frame) throws IOException {
        String key = DemoCapture.framesPrefix(captureId) + frame.getFileName();
        if (!storage.exists(key)) {
            String sha256 = sha256(frame);
            try (InputStream input = Files.newInputStream(frame)) {
                storage.put(key, input, sha256, Files.size(frame));
            }
        }
        return key;
    }

    // -----------------------------------------------------------------------------------------
    // waking → inferring → inferred → fetched

    void followJob(String captureId) {
        Instant now = clock.instant();
        if (!store.claim(captureId, owner, now, now.plus(properties.pollLease()))) {
            return;
        }
        try {
            DemoCapture capture = store.find(captureId).orElse(null);
            if (capture == null || !DemoPhases.WITH_JOB.contains(capture.state())) {
                return;
            }
            if (DemoPhases.INFERRED.equals(capture.state())) {
                publish(capture);
                return;
            }
            if (capture.depthJobId() == null) {
                fail(capture, "no depth job was recorded for this capture");
                return;
            }
            Instant submitted = capture.depthJobSubmittedAt() == null ? capture.updatedAt() : capture.depthJobSubmittedAt();
            if (now.isAfter(submitted.plus(properties.jobDeadline()))) {
                depth.cancel(capture.depthJobId());
                fail(capture, "the depth job did not finish within "
                        + properties.jobDeadline().toMinutes() + " min and was cancelled");
                return;
            }

            DepthJobStatus status;
            try {
                status = depth.status(capture.depthJobId());
            } catch (RuntimeException unreachable) {
                // Asking failed, not the job. The deadline above still bounds how long this goes on.
                log.warn("[demo] {} could not ask for job {}: {}",
                        captureId, capture.depthJobId(), unreachable.getMessage());
                return;
            }
            switch (status.state()) {
                case QUEUED -> {
                    // Still waiting for a worker; nothing new to say.
                }
                case RUNNING -> {
                    if (DemoPhases.WAKING.equals(capture.state())) {
                        write(capture, DemoPhases.INFERRING, null);
                    }
                }
                case COMPLETED -> publish(record(capture, status.output()));
                case FAILED -> fail(capture, status.error());
            }
        } catch (LeaseLost lost) {
            log.warn("[demo] {} lease passed to another replica mid-step; leaving it to them", captureId);
        } catch (RuntimeException exception) {
            if (stopping) {
                log.info("[demo] {} interrupted by shutdown; the next replica carries on", captureId);
                return;
            }
            log.warn("[demo] {} failed: {}", captureId, exception.getMessage());
            store.find(captureId).ifPresent(current -> fail(current, message(exception)));
        } finally {
            store.release(captureId, owner);
        }
    }

    /** Keeps the handler's manifest and writes {@code inferred} with what the phone shows of it. */
    private DemoCapture record(DemoCapture capture, JsonNode manifest) {
        String gpu = text(manifest.path("vram").path("device_name"));
        Double gpuSeconds = number(manifest.path("timing").path("gpu_seconds"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("gpu", gpu);
        JsonNode frames = manifest.path("frames").path("count");
        detail.put("frames", frames.isNumber() ? frames.asInt() : null);
        detail.put("gpuSeconds", gpuSeconds);
        detail.put("serverWallSeconds", number(manifest.path("timing").path("wall_seconds")));
        detail.put("mock", manifest.path("mock").asBoolean(false));
        return write(capture.withResult(manifest, gpu, gpuSeconds), DemoPhases.INFERRED, detail);
    }

    /**
     * {@code fetched}: every artifact the handler named is in this capture's prefix and in storage,
     * so the site can be handed a signed link to it. Checked rather than assumed, because a
     * handler that published nowhere still answers {@code COMPLETED}.
     */
    private void publish(DemoCapture capture) {
        JsonNode artifacts = capture.manifest() == null ? null : capture.manifest().path("artifacts");
        if (artifacts == null || !artifacts.isArray() || artifacts.isEmpty()) {
            fail(capture, "the depth job finished without naming any artifact");
            return;
        }
        String prefix = DemoCapture.prefix(capture.id());
        int published = 0;
        for (JsonNode artifact : artifacts) {
            String key = artifact.path("object_key").asString("");
            if (!key.startsWith(prefix) || key.contains("..")) {
                fail(capture, "artifact " + artifact.path("name").asString("?")
                        + " was not published under " + prefix);
                return;
            }
            if (!storage.exists(key)) {
                fail(capture, "artifact " + key + " is not in storage");
                return;
            }
            published++;
        }
        Instant inferredAt = capture.phases().stream()
                .filter(phase -> DemoPhases.INFERRED.equals(phase.phase()))
                .map(DemoPhase::at)
                .reduce((first, second) -> second)
                .orElse(clock.instant());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ms", Math.max(0, clock.instant().toEpochMilli() - inferredAt.toEpochMilli()));
        detail.put("artifacts", published);
        write(capture, DemoPhases.FETCHED, detail);
        log.info("[demo] {} fetched: {} artifact(s) on {}", capture.id(), published, capture.gpu());
    }

    // -----------------------------------------------------------------------------------------

    private DemoCapture write(DemoCapture capture, String phase, Map<String, Object> detail) {
        DemoPhase line = DemoPhase.of(phase, capture.createdAt(), clock.instant(), detail);
        if (!store.save(capture, line, owner)) {
            throw new LeaseLost();
        }
        return capture.append(line, line.at());
    }

    private void hold(String captureId, Duration lease) {
        if (!store.renew(captureId, owner, clock.instant().plus(lease))) {
            throw new LeaseLost();
        }
    }

    /** {@code failed}, naming the phase that was in progress — the last one written. */
    private void fail(DemoCapture capture, String error) {
        if (DemoPhases.FAILED.equals(capture.state()) || DemoPhases.FETCHED.equals(capture.state())) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("error", error == null || error.isBlank() ? "failed" : error);
        detail.put("failedIn", capture.state());
        DemoPhase line = DemoPhase.of(DemoPhases.FAILED, capture.createdAt(), clock.instant(), detail);
        if (!store.save(capture, line, owner)) {
            log.warn("[demo] {} could not be marked failed: its lease passed to another replica", capture.id());
        }
    }

    /** The phone's own sampling wins for its own capture, within what the endpoint holds. */
    private double requestedFps(JsonNode meta) {
        double asked = meta == null ? 0 : meta.path("sampling").path("fps").asDouble(0);
        return asked > 0 && asked <= 60 ? asked : properties.defaultFps();
    }

    private int maxFrames(JsonNode meta) {
        int asked = meta == null ? 0 : meta.path("sampling").path("maxFrames").asInt(0);
        return asked >= 2 ? Math.min(asked, properties.maxFrames()) : properties.maxFrames();
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String text(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            log.warn("could not remove demo work directory {}: {}", path, exception.getMessage());
        }
    }

    /**
     * One background thread that runs at most one piece of work at a time. Work offered while it
     * is busy is dropped: the next tick offers it again with fresher state.
     */
    private static final class Lane {
        private final ExecutorService executor;
        private final AtomicBoolean busy = new AtomicBoolean();

        Lane(String name) {
            this.executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name).daemon().factory());
        }

        void offer(Runnable work) {
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        work.run();
                    } catch (RuntimeException unexpected) {
                        log.error("[demo] {} failed unexpectedly", Thread.currentThread().getName(), unexpected);
                    } finally {
                        busy.set(false);
                    }
                });
            } catch (RejectedExecutionException shuttingDown) {
                busy.set(false);
            }
        }

        /** Interrupts a running step, which also kills an ffmpeg it is waiting on. */
        void interrupt() {
            executor.shutdownNow();
        }

        void await(Duration timeout) {
            try {
                executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Thrown when a write is refused because another replica now holds the capture. */
    private static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super("lease lost", null, false, false);
        }
    }
}
