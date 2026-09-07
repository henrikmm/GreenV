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
