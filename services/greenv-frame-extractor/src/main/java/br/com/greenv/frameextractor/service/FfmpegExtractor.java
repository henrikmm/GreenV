package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.SamplingPlan;
import br.com.greenv.frameextractor.domain.ScalePlan;
import br.com.greenv.frameextractor.port.FrameSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class FfmpegExtractor implements FrameSampler {

    private final CommandRunner commandRunner;
    private final ExtractorProperties extractorProperties;

    public FfmpegExtractor(CommandRunner commandRunner, ExtractorProperties extractorProperties) {
        this.commandRunner = commandRunner;
        this.extractorProperties = extractorProperties;
    }

    @Override
    public List<Path> extract(
            Path source,
            Path outputDirectory,
            SamplingPlan sampling,
            ScalePlan scale) {
        deleteTree(outputDirectory);
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new ExtractionException("output_create_failed", "could not create frame output", true, exception);
        }

        List<String> filters = new ArrayList<>();
        filters.add("fps=" + String.format(Locale.ROOT, "%.9f", sampling.effectiveFps()));
        if (scale.scaled()) {
            filters.add("scale=" + scale.width() + ":" + scale.height());
        }

        List<String> command = List.of(
                extractorProperties.ffmpeg(),
                "-nostdin",
                "-v", "error",
                "-threads", "2",
                "-i", source.toString(),
                "-vf", String.join(",", filters),
                "-frames:v", Integer.toString(sampling.count()),
                "-q:v", "2",
                outputDirectory.resolve("frame-%04d.jpg").toString());
        var result = commandRunner.run(command, Duration.ofMinutes(15));
        if (result.exitCode() != 0) {
            throw new ExtractionException(
                    "ffmpeg_failed",
                    result.stderr().isBlank() ? "ffmpeg could not extract frames" : result.stderr().trim(),
                    false);
        }

        try (var files = Files.list(outputDirectory)) {
            List<Path> frames = files
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
            if (frames.size() < 2) {
                throw new ExtractionException(
                        "insufficient_frames",
                        "extraction produced " + frames.size() + " frame(s); multiple views are required",
                        false);
            }
            return frames;
        } catch (IOException exception) {
            throw new ExtractionException("frame_list_failed", "could not list extracted frames", true, exception);
        }
    }

    @Override
    public List<Path> extractExact(
            Path source, Path outputDirectory, List<Integer> frameIndices, ScalePlan scale) {
        if (frameIndices == null || frameIndices.size() < 2) {
            throw new ExtractionException("insufficient_frames", "multiple views are required", false);
        }
        deleteTree(outputDirectory);
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new ExtractionException(
                    "output_create_failed", "could not create frame output", true, exception);
        }

        List<String> filters = new ArrayList<>();
        filters.add(SelectExpression.forIndices(frameIndices));
        if (scale.scaled()) {
            // After select, so only the frames being kept are scaled.
            filters.add("scale=" + scale.width() + ":" + scale.height());
        }

        List<String> command = List.of(
                extractorProperties.ffmpeg(),
                "-nostdin",
                "-v", "error",
                "-threads", "2",
                "-i", source.toString(),
                // The probe measured v:0; default selection takes the highest-resolution stream,
                // which is not necessarily the same one.
                "-map", "0:v:0",
                "-vf", String.join(",", filters),
                // Mandatory, not tidiness. The image2 muxer is not AVFMT_VARIABLE_FPS, so the
                // default sync mode is constant frame rate and it FILLS THE GAPS between sparse
                // selected frames with copies. Measured on ffmpeg 8.1.2: selecting frames 0/10/20
                // without this wrote three copies of frame 0, and with `-frames:v` that would be a
                // full set of duplicates and a manifest that looked correct. `-vsync 0` does the
                // same job but was removed in ffmpeg 8, so it would break where this is developed.
                "-fps_mode", "passthrough",
                "-q:v", "2",
                outputDirectory.resolve("frame-%04d.jpg").toString());
        var result = commandRunner.run(command, Duration.ofMinutes(15));
        if (result.exitCode() != 0) {
            throw new ExtractionException(
                    "ffmpeg_failed",
                    result.stderr().isBlank() ? "ffmpeg could not extract frames" : result.stderr().trim(),
                    false);
        }

        try (var files = Files.list(outputDirectory)) {
            List<Path> frames = files
                    .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .toList();
            // Exact, not a lower bound: anything else means the selection and the probe disagreed
            // about which frames exist, and a plausible-looking short set is the failure worth
            // catching rather than publishing.
            if (frames.size() != frameIndices.size()) {
                throw new ExtractionException(
                        "frame_selection_mismatch",
                        "asked for " + frameIndices.size() + " frames and got " + frames.size(),
                        false);
            }
            return frames;
        } catch (IOException exception) {
            throw new ExtractionException("frame_list_failed", "could not list extracted frames", true, exception);
        }
    }

    static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            throw new ExtractionException("cleanup_failed", "could not clean extraction output", true, exception);
        }
    }
}
