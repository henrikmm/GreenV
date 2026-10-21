package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DepthJobRequest;
import br.com.greenv.videoapi.domain.DepthJobStatus;
import br.com.greenv.videoapi.port.CaptureObjectStorage;
import br.com.greenv.videoapi.port.DepthJobRunner;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A depth job that costs nothing and wakes nothing, for tests and for running the whole demo on
 * one laptop.
 *
 * <p>It walks the same states a RunPod job does — queued, running, completed — one per question,
 * so the pipeline writes {@code waking}, {@code inferring}, {@code inferred} and {@code fetched}
 * exactly as it would against a GPU. On completion it publishes two placeholder artifacts where
 * the handler would ({@code <capture>/depth/<run_id>/}) and answers with a manifest shaped like
 * {@code greenv.depth-job/1}'s example output.
 *
 * <p>The artifacts are filler bytes, not a reconstruction, and the manifest says
 * {@code "mock": true} so that nothing downstream can mistake one for a measurement. Jobs live in
 * this process only: a mock job asked about after a restart answers that it was lost.
 */
@Component
@ConditionalOnExpression("${greenv.demo.enabled:false} and '${greenv.demo.depth-adapter:runpod}' == 'mock'")
public class MockDepthJobAdapter implements DepthJobRunner {

    private static final DateTimeFormatter RUN_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** The example's filler: the same names and sizes the contract's own fixture publishes. */
    private static final Map<String, Filler> ARTIFACTS = Map.of(
            "glb", new Filler("scene.glb", 'g', 48),
            "npz", new Filler("result.npz", 'n', 96));

    private final ObjectMapper objectMapper;
    private final CaptureObjectStorage storage;
    private final Clock clock;
    private final DemoProperties properties;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    public MockDepthJobAdapter(
            ObjectMapper objectMapper,
            CaptureObjectStorage storage,
            Clock clock,
            DemoProperties properties) {
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public DepthJobStatus submit(DepthJobRequest request) {
        if (request.video() == null && request.frames().size() < 2) {
            throw new IllegalArgumentException(
                    "depth inference needs at least 2 frames, got " + request.frames().size());
        }
        String jobId = "mock-" + sequence.incrementAndGet() + "-" + request.captureId();
        jobs.put(jobId, new Job(request, new AtomicInteger()));
        return DepthJobStatus.queued(jobId);
    }

    @Override
    public DepthJobStatus status(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            return DepthJobStatus.failed(jobId, "mock depth job " + jobId + " is not known to this process");
        }
        int asked = job.asked().incrementAndGet();
        if (job.request().video() != null && asked <= 2) {
            // As the handler reports a video job: sampled first, then its frames published.
            return DepthJobStatus.running(jobId, sampledProgress(job.request(), asked == 2));
        }
        if (asked == 1) {
            return DepthJobStatus.running(jobId);
        }
        jobs.remove(jobId);
        return DepthJobStatus.completed(jobId, publish(job.request()));
    }

    @Override
    public void cancel(String jobId) {
        jobs.remove(jobId);
    }

    /** Three filler frames: a mock video job's whole sampling. */
    private static final int MOCK_VIDEO_FRAMES = 3;

    private ObjectNode sampledProgress(DepthJobRequest request, boolean withFrames) {
        ObjectNode progress = objectMapper.createObjectNode();
        progress.put("stage", withFrames ? "frames" : "sampled");
        progress.set("sampling", sampling(request));
        if (withFrames) {
            ArrayNode keys = progress.putArray("frames");
            mockFrameKeys(request).forEach(keys::add);
        } else {
            progress.putNull("frames");
        }
        return progress;
    }

    private ObjectNode sampling(DepthJobRequest request) {
        ObjectNode sampling = objectMapper.createObjectNode();
        sampling.put("frames", MOCK_VIDEO_FRAMES);
        sampling.put("fps", request.video().fps());
        sampling.put("capped", false);
        sampling.put("requested_frames", MOCK_VIDEO_FRAMES);
        sampling.put("width", 576);
        sampling.put("height", 1024);
        sampling.put("duration_s", 0.3);
        ObjectNode seconds = sampling.putObject("seconds");
        seconds.put("fetch_video", 0.1);
        seconds.put("sample", 0.2);
        return sampling;
    }

    /** Publishes the filler frames where the handler would, once, and names them. */
    private List<String> mockFrameKeys(DepthJobRequest request) {
        List<String> keys = new ArrayList<>();
        for (int index = 1; index <= MOCK_VIDEO_FRAMES; index++) {
            String key = request.video().framesPrefix() + "/frame-%04d.jpg".formatted(index);
            if (!storage.exists(key)) {
                byte[] bytes = ("mock frame " + index).getBytes(StandardCharsets.UTF_8);
                try (InputStream input = new ByteArrayInputStream(bytes)) {
                    storage.put(key, input, sha256(bytes), bytes.length);
                } catch (IOException exception) {
                    throw new IllegalStateException("could not publish " + key, exception);
                }
            }
            keys.add(key);
        }
        return keys;
    }

    private JsonNode publish(DepthJobRequest request) {
        String runId = RUN_STAMP.format(clock.instant()) + "-mock" + sequence.get();
        String prefix = outputPrefix(request) + "depth/" + runId;
        ObjectNode manifest = template();
        manifest.put("run_id", runId);
        ObjectNode params = (ObjectNode) manifest.get("params");
        params.put("fps", request.fps());
        params.put("process_res", request.processRes());
        params.put("max_frames", request.maxFrames());
        if (request.sourceDurationSeconds() != null) {
            params.put("source_duration_s", request.sourceDurationSeconds());
        }
        int sent = request.video() != null ? MOCK_VIDEO_FRAMES : request.frames().size();
        ObjectNode frames = (ObjectNode) manifest.get("frames");
        frames.put("count", sent);
        frames.put("requested_count", sent);
        frames.put("effective_fps", request.fps());
        ObjectNode published = (ObjectNode) manifest.get("published");
        published.put("prefix", prefix);
        published.put("frame_ceiling", properties.maxFrames());
        if (request.video() != null) {
            ObjectNode sampled = sampling(request);
            ArrayNode keys = sampled.putArray("frame_keys");
            mockFrameKeys(request).forEach(keys::add);
            published.set("sampled", sampled);
        }

        ArrayNode artifacts = manifest.putArray("artifacts");
        for (String kind : new String[] {"glb", "npz"}) {
            Filler filler = ARTIFACTS.get(kind);
            byte[] bytes = new byte[filler.sizeBytes()];
            Arrays.fill(bytes, (byte) filler.fill());
            String key = prefix + "/" + filler.name();
            String sha256 = sha256(bytes);
            try (InputStream input = new ByteArrayInputStream(bytes)) {
                storage.put(key, input, sha256, bytes.length);
            } catch (IOException exception) {
                throw new IllegalStateException("could not publish " + key, exception);
            }
            ObjectNode artifact = artifacts.addObject();
            artifact.put("kind", kind);
            artifact.put("name", filler.name());
            artifact.put("size_bytes", bytes.length);
            artifact.put("sha256", sha256);
            // The handler answers with its own twelve-hour link here; the API never serves it and
            // re-signs from object_key, so the mock leaves it empty rather than inventing one.
            artifact.putNull("url");
            artifact.put("object_key", key);
        }
        return manifest;
    }

    /** {@code handler.py}'s {@code output_prefix}: the frames' directory, minus {@code sampled-frames}. */
    static String outputPrefix(DepthJobRequest request) {
        if (request.video() != null) {
            String video = request.video().key();
            return video.contains("/") ? video.substring(0, video.lastIndexOf('/') + 1) : "";
        }
        String key = request.frames().getFirst().key();
        String parent = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        if (parent.endsWith("/sampled-frames")) {
            parent = parent.substring(0, parent.length() - "/sampled-frames".length());
        }
        return parent.isEmpty() ? "" : parent + "/";
    }

    private ObjectNode template() {
        try (InputStream input = MockDepthJobAdapter.class.getResourceAsStream("/demo/mock-depth-manifest.json")) {
            if (input == null) {
                throw new IllegalStateException("demo/mock-depth-manifest.json is missing from the classpath");
            }
            return (ObjectNode) objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read the mock manifest", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record Job(DepthJobRequest request, AtomicInteger asked) {
    }

    private record Filler(String name, char fill, int sizeBytes) {
    }
}
