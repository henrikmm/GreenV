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

    /** GPU sampling, with the handler's progress visible while the job runs. */
    @Test
    void sendsTheVideoAndFollowsTheGpusSamplingAsItIsReported() throws Exception {
        CaptureObjectStorage storage = localStorage();
        receivedWithVideo(storage);
        DemoPipelineService pipeline = new DemoPipelineService(
                store, storage, (video, out, fps, max, edge) -> {
                    throw new AssertionError("the API does not sample in gpu mode");
                }, depth, clock, gpuProperties());

        pipeline.sampleAndSubmit(ID);
        DepthJobRequest sent = depth.submitted.getFirst();
        assertThat(sent.video().key()).isEqualTo("demo/" + ID + "/video.mp4");
        assertThat(sent.video().framesPrefix()).isEqualTo("demo/" + ID + "/sampled-frames");
        assertThat(sent.frames()).isEmpty();
        assertThat(store.get().state()).isEqualTo(DemoPhases.WAKING);

        depth.script.add(DepthJobStatus.running("job-1"));
        pipeline.followJob(ID);
        assertThat(store.get().state()).isEqualTo(DemoPhases.SAMPLING);

        depth.script.add(DepthJobStatus.running("job-1", json(
                "{\"stage\":\"sampled\",\"frames\":null,\"sampling\":{\"frames\":96,\"fps\":10,\"capped\":false,"
                        + "\"requested_frames\":96,\"width\":576,\"height\":1024,\"duration_s\":9.66,"
                        + "\"seconds\":{\"fetch_video\":0.4,\"sample\":0.9}}}")));
        pipeline.followJob(ID);
        List<String> phases = store.get().phases().stream().map(DemoPhase::phase).toList();
        assertThat(phases).endsWith(DemoPhases.WAKING, DemoPhases.SAMPLING, DemoPhases.SAMPLED, DemoPhases.INFERRING);
        DemoPhase sampled = store.get().phases().get(phases.indexOf(DemoPhases.SAMPLED));
        assertThat(sampled.detail()).containsEntry("frames", 96).containsEntry("onGpu", true)
                .containsEntry("sampleSeconds", 0.9).doesNotContainKey("reportedAtEnd");
        assertThat(store.get().frameKeys()).isEmpty();

        String frame = "demo/" + ID + "/sampled-frames/frame-0001.jpg";
        depth.script.add(DepthJobStatus.running("job-1", json(
                "{\"stage\":\"frames\",\"sampling\":{\"frames\":96},\"frames\":[\"" + frame + "\"]}")));
        pipeline.followJob(ID);
        assertThat(store.get().frameKeys()).containsExactly(frame);
        assertThat(store.get().state()).isEqualTo(DemoPhases.INFERRING);
    }

    /** GPU sampling when RunPod never shows the progress: the finished job's record fills it in. */
    @Test
    void fillsInTheGpusSamplingFromTheFinishedJobWhenNoProgressWasSeen() throws Exception {
        CaptureObjectStorage storage = localStorage();
        receivedWithVideo(storage);
        String artifact = "demo/" + ID + "/depth/r1/scene.glb";
        byte[] glb = "glb".getBytes(StandardCharsets.UTF_8);
        storage.put(artifact, new ByteArrayInputStream(glb), sha256(glb), 1024);
        DemoPipelineService pipeline = new DemoPipelineService(
                store, storage, (video, out, fps, max, edge) -> {
                    throw new AssertionError("the API does not sample in gpu mode");
                }, depth, clock, gpuProperties());
        pipeline.sampleAndSubmit(ID);

        String frame = "demo/" + ID + "/sampled-frames/frame-0001.jpg";
        depth.script.add(DepthJobStatus.completed("job-1", json(
                "{\"run_id\":\"r1\",\"frames\":{\"count\":96},\"vram\":{\"device_name\":\"NVIDIA H100\"},"
                        + "\"timing\":{\"gpu_seconds\":6.2},\"artifacts\":[{\"name\":\"scene.glb\",\"object_key\":\""
                        + artifact + "\"}],\"published\":{\"sampled\":{\"frames\":96,\"seconds\":{\"sample\":0.9},"
                        + "\"frame_keys\":[\"" + frame + "\"]}}}")));
        pipeline.followJob(ID);

        List<String> phases = store.get().phases().stream().map(DemoPhase::phase).toList();
        assertThat(phases).endsWith(
                DemoPhases.WAKING, DemoPhases.SAMPLING, DemoPhases.SAMPLED, DemoPhases.INFERRING,
                DemoPhases.INFERRED, DemoPhases.FETCHED);
        assertThat(store.get().phases().get(phases.indexOf(DemoPhases.SAMPLED)).detail())
                .containsEntry("reportedAtEnd", true).containsEntry("sampleSeconds", 0.9);
        assertThat(store.get().frameKeys()).containsExactly(frame);
    }

    private void receivedWithVideo(CaptureObjectStorage storage) throws Exception {
        byte[] bytes = "video".getBytes(StandardCharsets.UTF_8);
        storage.put("demo/" + ID + "/video.mp4", new ByteArrayInputStream(bytes), sha256(bytes), 1024);
        store.put(capture(DemoPhases.RECEIVED)
                .withVideo(new DemoVideo("demo/" + ID + "/video.mp4", "video/mp4", bytes.length, sha256(bytes), 10, null)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static tools.jackson.databind.JsonNode json(String text) {
        return JsonMapper.builder().build().readTree(text);
    }

    private static DemoProperties gpuProperties() {
        return new DemoProperties(
                true, "pairing-code", 1024, 112, 10, 1024, 504, "mock",
                Duration.ofMinutes(20), Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofHours(1),
                3, "http://127.0.0.1:8080", "ffmpeg", "ffprobe", null, "gpu");
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
        /** What status() answers next, in order; queued once it runs out. */
        final java.util.ArrayDeque<DepthJobStatus> script = new java.util.ArrayDeque<>();

        @Override
        public DepthJobStatus submit(DepthJobRequest request) {
            submitted.add(request);
            return DepthJobStatus.queued("job-" + submitted.size());
        }

        @Override
        public DepthJobStatus status(String jobId) {
            return script.isEmpty() ? DepthJobStatus.queued(jobId) : script.poll();
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
