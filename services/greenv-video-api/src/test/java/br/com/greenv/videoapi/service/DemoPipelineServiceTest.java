package br.com.greenv.videoapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import br.com.greenv.videoapi.domain.DemoPhases;
import br.com.greenv.videoapi.domain.DemoVideo;
import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import br.com.greenv.videoapi.port.DepthJobRunner;
import br.com.greenv.videoapi.storage.LocalCaptureObjectStorageAdapter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The two ways a capture ends badly that the integration test cannot provoke cheaply. */
class DemoPipelineServiceTest {

    private static final String ID = "cap-20261024-143000-a1b2";
    private static final Instant START = Instant.parse("2026-10-24T14:30:00Z");

    @TempDir
    Path temporaryDirectory;

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryStore store = new InMemoryStore();
    private final RecordingDepth depth = new RecordingDepth();

    @Test
    void cancelsAJobThatOutlivesItsDeadlineAndSaysWhereItFailed() {
        store.put(capture(DemoPhases.WAKING).withDepthJob("job-1", START));
        DemoPipelineService pipeline = pipeline((video, out, fps, max, edge) -> {
            throw new AssertionError("not sampled again");
        });

        clock.now = START.plus(Duration.ofMinutes(5));
        pipeline.followJob(ID);
        assertThat(store.get().state()).isEqualTo(DemoPhases.WAKING);
        assertThat(depth.cancelled).isEmpty();

        clock.now = START.plus(Duration.ofMinutes(21));
        pipeline.followJob(ID);

        assertThat(depth.cancelled).containsExactly("job-1");
        DemoPhase failed = store.get().phases().getLast();
        assertThat(failed.phase()).isEqualTo(DemoPhases.FAILED);
        assertThat(failed.detail()).containsEntry("failedIn", DemoPhases.WAKING);
        assertThat((String) failed.detail().get("error")).contains("cancelled");
        assertThat(store.leaseOwner).as("the lease is given back").isNull();
    }

    @Test
    void marksTheCaptureFailedInExtractingWhenSamplingBreaks() throws Exception {
        CaptureObjectStorage storage = localStorage();
        byte[] bytes = "video".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        storage.put("demo/" + ID + "/video.mp4", new ByteArrayInputStream(bytes), sha, 1024);
        store.put(capture(DemoPhases.RECEIVED)
                .withVideo(new DemoVideo("demo/" + ID + "/video.mp4", "video/mp4", bytes.length, sha, 10, null)));
        DemoPipelineService pipeline = new DemoPipelineService(
                store, storage,
                (video, out, fps, max, edge) -> {
                    throw new IllegalStateException("ffprobe rejected the video");
                },
                depth, clock, properties());

        pipeline.sampleAndSubmit(ID);

        List<String> phases = store.get().phases().stream().map(DemoPhase::phase).toList();
        assertThat(phases).containsExactly(
                DemoPhases.CREATED, DemoPhases.RECEIVED, DemoPhases.EXTRACTING, DemoPhases.FAILED);
        assertThat(store.get().phases().getLast().detail())
                .containsEntry("failedIn", DemoPhases.EXTRACTING)
                .containsEntry("error", "ffprobe rejected the video");
        assertThat(depth.submitted).isEmpty();
    }

    /** A replica that lost the capture mid-step leaves it to the new holder: no job, no failure. */
    @Test
    void submitsNoJobOnceAnotherReplicaHasTakenTheCapture() throws Exception {
        CaptureObjectStorage storage = localStorage();
        byte[] bytes = "video".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        storage.put("demo/" + ID + "/video.mp4", new ByteArrayInputStream(bytes), sha, 1024);
        store.put(capture(DemoPhases.RECEIVED)
                .withVideo(new DemoVideo("demo/" + ID + "/video.mp4", "video/mp4", bytes.length, sha, 10, null)));
        // The download outlasted the lease and another replica claimed the capture meanwhile.
        store.takenOverBeforeRenewal = true;
        DemoPipelineService pipeline = pipeline((video, out, fps, max, edge) -> {
            throw new AssertionError("not sampled by a replica that lost the lease");
        });

        pipeline.sampleAndSubmit(ID);

        assertThat(depth.submitted).isEmpty();
        assertThat(store.get().state()).as("left to the new holder, not failed").isEqualTo(DemoPhases.EXTRACTING);
    }

    private DemoPipelineService pipeline(br.com.greenv.videoapi.port.DemoFrameSampler sampler) {
        return new DemoPipelineService(store, localStorage(), sampler, depth, clock, properties());
    }

    private CaptureObjectStorage localStorage() {
        return new LocalCaptureObjectStorageAdapter(new PipelineProperties(
                temporaryDirectory.resolve("pipeline"), temporaryDirectory.resolve("saved"), 1024, 3));
    }

    private static DemoProperties properties() {
        return new DemoProperties(
                true, "pairing-code", 1024, 112, 10, 1024, 504, "mock",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe", null);
    }

    private static DemoCapture capture(String state) {
        DemoCapture capture = DemoCapture.announced(
                ID,
                JsonMapper.builder().build().readTree("{\"schema\":\"verge-phone-capture/1\"}"),
                START,
                START);
        return capture.append(DemoPhase.of(state, START, START, null), START);
    }

    private static final class RecordingDepth implements DepthJobRunner {
        final List<DepthJobRequest> submitted = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();

        @Override
        public DepthJobStatus submit(DepthJobRequest request) {
            submitted.add(request);
            return DepthJobStatus.queued("job-" + submitted.size());
        }

        @Override
        public DepthJobStatus status(String jobId) {
            return DepthJobStatus.queued(jobId);
        }

        @Override
        public void cancel(String jobId) {
            cancelled.add(jobId);
        }
    }

    /** One capture, one lease: enough to watch the pipeline's writes. */
    private static final class InMemoryStore implements DemoCaptureStore {
        private DemoCapture capture;
        String leaseOwner;
        boolean takenOverBeforeRenewal;
        private Instant leaseUntil;

        void put(DemoCapture value) {
            capture = value;
        }

        DemoCapture get() {
            return capture;
        }

        @Override
        public void insert(DemoCapture value) {
            capture = value;
        }

        @Override
        public Optional<DemoCapture> find(String captureId) {
            return Optional.ofNullable(capture).filter(found -> found.id().equals(captureId));
        }

        @Override
        public List<DemoCapture> latest(int limit) {
            return capture == null ? List.of() : List.of(capture);
        }

        @Override
        public boolean beginUpload(String captureId, DemoPhase uploading) {
            return false;
        }

        @Override
        public boolean save(DemoCapture value, DemoPhase phase, String owner) {
            if (owner != null && !owner.equals(leaseOwner)) {
                return false;
            }
            capture = phase == null ? value : value.append(phase, phase.at());
            return true;
        }

        @Override
        public List<String> claimable(List<String> states, Instant now, int limit) {
            return capture != null && states.contains(capture.state()) ? List.of(capture.id()) : List.of();
        }

        @Override
        public boolean claim(String captureId, String owner, Instant now, Instant until) {
            if (leaseUntil != null && !leaseUntil.isBefore(now)) {
                return false;
            }
            leaseOwner = owner;
            leaseUntil = until;
            return true;
        }

        @Override
        public boolean renew(String captureId, String owner, Instant until) {
            if (takenOverBeforeRenewal) {
                leaseOwner = "replica-b";
            }
            if (!owner.equals(leaseOwner)) {
                return false;
            }
            leaseUntil = until;
            return true;
        }

        @Override
        public void release(String captureId, String owner) {
            if (owner.equals(leaseOwner)) {
                leaseOwner = null;
                leaseUntil = null;
            }
        }

        @Override
        public List<DemoCapture> createdBefore(Instant cutoff, int limit) {
            return List.of();
        }

        @Override
        public void delete(String captureId) {
            capture = null;
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
