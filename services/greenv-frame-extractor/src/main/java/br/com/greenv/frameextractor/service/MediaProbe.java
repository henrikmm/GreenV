package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.MediaProbeResult;
import br.com.greenv.frameextractor.port.VideoProbe;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MediaProbe implements VideoProbe {

    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final ExtractorProperties extractorProperties;

    public MediaProbe(
            CommandRunner commandRunner,
            ObjectMapper objectMapper,
            ExtractorProperties extractorProperties) {
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.extractorProperties = extractorProperties;
    }

    @Override
    public MediaProbeResult probe(Path source) {
        var result = commandRunner.run(List.of(
                extractorProperties.ffprobe(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries",
                "stream=width,height,avg_frame_rate:stream_side_data=rotation:stream_tags=rotate:format=duration",
                "-of", "json",
                source.toString()), Duration.ofMinutes(2));
        if (result.exitCode() != 0) {
            throw new ExtractionException("invalid_video", message("ffprobe rejected the video", result.stderr()), false);
        }

        try {
            JsonNode root = objectMapper.readTree(result.stdout());
            JsonNode stream = root.path("streams").path(0);
            if (stream.isMissingNode()) {
                throw new ExtractionException("no_video_stream", "the source has no video stream", false);
            }
            double duration = root.path("format").path("duration").asDouble(Double.NaN);
            int storedWidth = stream.path("width").asInt(0);
            int storedHeight = stream.path("height").asInt(0);
            if (!Double.isFinite(duration) || duration <= 0 || storedWidth <= 0 || storedHeight <= 0) {
                throw new ExtractionException("invalid_video_metadata", "could not determine video duration or dimensions", false);
            }
            int rotation = rotation(stream);
            boolean swapsAxes = rotation % 180 == 90;
            return new MediaProbeResult(
                    duration,
                    rational(stream.path("avg_frame_rate").asString("0/1")),
                    swapsAxes ? storedHeight : storedWidth,
                    swapsAxes ? storedWidth : storedHeight,
                    rotation,
                    storedWidth,
                    storedHeight);
        } catch (ExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ExtractionException("invalid_probe_output", "could not parse ffprobe output", false, exception);
        }
    }

    static int rotation(JsonNode stream) {
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
        double denominator = Double.parseDouble(parts[1]);
        return denominator > 0 ? Double.parseDouble(parts[0]) / denominator : 0;
    }

    private static String message(String prefix, String detail) {
        String trimmed = detail == null ? "" : detail.trim();
        return trimmed.isEmpty() ? prefix : prefix + ": " + trimmed;
    }
}
