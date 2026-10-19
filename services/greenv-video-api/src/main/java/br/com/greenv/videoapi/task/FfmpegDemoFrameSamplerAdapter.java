package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.DemoProperties;
import br.com.greenv.videoapi.domain.DemoSampling;
import br.com.greenv.videoapi.domain.DemoVideoProbe;
import br.com.greenv.videoapi.port.DemoFrameSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Samples a demo video with ffmpeg: an even rate over the whole clip, scaled to a long edge.
 *
 * <p>The probe, the plan and the command are copied from {@code services/greenv-frame-extractor}
 * ({@code MediaProbe}, {@code SamplingPlanner} and {@code FfmpegExtractor.extract}), not imported:
 * the extractor is another deployable and this must not depend on its release. What differs is
 * only what a demo needs — one clip, no telemetry, no road windows.
 *
 * <p>When the clip is longer than the frame budget at the asked rate, the rate is lowered so the
 * budget covers the whole clip. Spending it on the first seconds instead would reconstruct the
 * start of the orbit and nothing of the object's far side.
 *
 * <p>The size is planned from the displayed dimensions, rotation applied, because ffmpeg rotates a
 * phone's portrait video before any filter runs; planning from the stored dimensions would squash
 * every portrait clip.
 */
@Component
@ConditionalOnProperty(name = "greenv.demo.enabled", havingValue = "true")
public class FfmpegDemoFrameSamplerAdapter implements DemoFrameSampler {

    private final CommandRunner commandRunner = new CommandRunner();
    private final ObjectMapper objectMapper;
    private final String ffmpeg;
    private final String ffprobe;
    private final Duration timeout;

    public FfmpegDemoFrameSamplerAdapter(ObjectMapper objectMapper, DemoProperties properties) {
        this.objectMapper = objectMapper;
        this.ffmpeg = properties.ffmpeg();
        this.ffprobe = properties.ffprobe();
        // Inside the lease, so a stuck ffmpeg is killed before another replica takes the capture.
        Duration lease = properties.extractionLease();
        this.timeout = lease.compareTo(Duration.ofMinutes(1)) > 0 ? lease.minusSeconds(30) : lease;
    }

    @Override
    public DemoSampling sample(Path video, Path outputDirectory, double fps, int maxFrames, int longEdge) {
        DemoVideoProbe probe = probe(video);
        if (probe.durationS() == null || !(probe.durationS() > 0)
                || probe.displayWidth() <= 0 || probe.displayHeight() <= 0) {
            throw new IllegalStateException("could not determine the video's duration or dimensions");
        }
        Plan plan = plan(fps, probe.durationS(), maxFrames);
        Size size = scale(probe.displayWidth(), probe.displayHeight(), longEdge);

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("could not create the frame directory", exception);
        }
        List<String> filters = new ArrayList<>();
        filters.add("fps=" + String.format(Locale.ROOT, "%.9f", plan.effectiveFps()));
        if (size.scaled()) {
            filters.add("scale=" + size.width() + ":" + size.height());
        }
        var result = commandRunner.run(List.of(
                ffmpeg,
                "-nostdin",
                "-v", "error",
                "-threads", "2",
                "-i", video.toString(),
                "-map", "0:v:0",
                "-vf", String.join(",", filters),
                "-frames:v", Integer.toString(plan.count()),
                "-q:v", "2",
                outputDirectory.resolve("frame-%04d.jpg").toString()), timeout);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    result.stderr().isBlank() ? "ffmpeg could not extract frames" : "ffmpeg: " + result.stderr().trim());
        }

        try (var files = Files.list(outputDirectory)) {
            List<Path> frames = files
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
            if (frames.size() < 2) {
                // One view has no cross-view attention to do: a single frame is a wrong answer,
                // not a small one.
                throw new IllegalStateException(
                        "extraction produced " + frames.size() + " frame(s); at least 2 views are required");
            }
            return new DemoSampling(
                    frames, plan.effectiveFps(), plan.capped(), plan.requestedCount(),
                    size.width(), size.height(), probe);
        } catch (IOException exception) {
            throw new IllegalStateException("could not list the extracted frames", exception);
        }
    }

    DemoVideoProbe probe(Path video) {
        var result = commandRunner.run(List.of(
                ffprobe,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries",
                "stream=codec_name,width,height,avg_frame_rate,bit_rate,color_transfer"
                        + ":stream_side_data=rotation:stream_tags=rotate:format=duration",
                "-of", "json",
                video.toString()), Duration.ofMinutes(2));
        if (result.exitCode() != 0) {
            String detail = result.stderr() == null ? "" : result.stderr().trim();
            throw new IllegalStateException(
                    detail.isEmpty() ? "ffprobe rejected the video" : "ffprobe rejected the video: " + detail);
        }
        return parseProbe(objectMapper.readTree(result.stdout()));
    }

    static DemoVideoProbe parseProbe(JsonNode root) {
        JsonNode stream = root.path("streams").path(0);
        if (stream.isMissingNode()) {
            throw new IllegalStateException("the video has no video stream");
        }
        String transfer = text(stream, "color_transfer");
        double duration = root.path("format").path("duration").asDouble(Double.NaN);
        double fps = rational(stream.path("avg_frame_rate").asString("0/1"));
        long bitRate = stream.path("bit_rate").asLong(0);
        int width = stream.path("width").asInt(0);
        int height = stream.path("height").asInt(0);
        return new DemoVideoProbe(
                text(stream, "codec_name"),
                width > 0 ? width : null,
                height > 0 ? height : null,
                rotation(stream),
                fps > 0 ? Math.round(fps * 100) / 100.0 : null,
                bitRate > 0 ? bitRate : null,
                "smpte2084".equals(transfer) || "arib-std-b67".equals(transfer),
                transfer,
                Double.isFinite(duration) && duration > 0 ? duration : null);
    }

    /** {@code SamplingPlanner.sampling}, unchanged. */
    static Plan plan(double requestedFps, double durationSeconds, int maxFrames) {
        if (!(requestedFps > 0) || !(durationSeconds > 0) || maxFrames < 2) {
            throw new IllegalArgumentException("sampling inputs must be positive");
        }
        int requestedCount = Math.max(1, (int) Math.floor(requestedFps * durationSeconds));
        if (requestedCount <= maxFrames) {
            return new Plan(requestedCount, requestedFps, false, requestedCount);
        }
        return new Plan(maxFrames, maxFrames / durationSeconds, true, requestedCount);
    }

    /** {@code SamplingPlanner.scale}, unchanged: even sides, never enlarged. */
    static Size scale(int width, int height, int longEdge) {
        int longest = Math.max(width, height);
        if (longEdge <= 0 || longest <= longEdge) {
            return new Size(width, height, false);
        }
        double factor = (double) longEdge / longest;
        return new Size(even(width, factor), even(height, factor), true);
    }

    private static int even(int value, double factor) {
        return Math.max(2, (int) Math.round((value * factor) / 2.0) * 2);
    }

    /** {@code MediaProbe.rotation}: side data first, the legacy tag second. */
    private static int rotation(JsonNode stream) {
        JsonNode sideData = stream.path("side_data_list");
        if (sideData.isArray()) {
            for (JsonNode entry : sideData) {
                if (entry.has("rotation")) {
                    return normalize(entry.path("rotation").asInt(0));
                }
            }
        }
        return normalize(stream.path("tags").path("rotate").asInt(0));
    }

    private static int normalize(int degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    private static double rational(String value) {
        String[] parts = value.split("/", 2);
        if (parts.length != 2) {
            return 0;
        }
        try {
            double denominator = Double.parseDouble(parts[1]);
            return denominator > 0 ? Double.parseDouble(parts[0]) / denominator : 0;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    record Plan(int count, double effectiveFps, boolean capped, int requestedCount) {
    }

    record Size(int width, int height, boolean scaled) {
    }
}
