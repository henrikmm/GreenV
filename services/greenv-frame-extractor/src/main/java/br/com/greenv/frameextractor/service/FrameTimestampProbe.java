package br.com.greenv.frameextractor.service;

import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.EncodedFrameTimestamp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FrameTimestampProbe {

    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;
    private final ExtractorProperties properties;

    public FrameTimestampProbe(
            CommandRunner commandRunner,
            ObjectMapper objectMapper,
            ExtractorProperties properties) {
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<EncodedFrameTimestamp> probe(Path source) {
        var result = commandRunner.run(List.of(
                properties.ffprobe(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_frames",
                "-show_entries", "frame=best_effort_timestamp_time,key_frame",
                "-of", "json",
                source.toString()), Duration.ofMinutes(2));
        if (result.exitCode() != 0) {
            throw new ExtractionException("frame_probe_failed", "ffprobe could not enumerate video frames", false);
        }
        try {
            var frames = objectMapper.readTree(result.stdout()).path("frames");
            if (!frames.isArray() || frames.isEmpty()) {
                throw new ExtractionException("frame_probe_empty", "video contains no decodable frames", false);
            }
            List<EncodedFrameTimestamp> output = new ArrayList<>(frames.size());
            long previous = -1;
            for (int index = 0; index < frames.size(); index++) {
                var frame = frames.get(index);
                String raw = frame.path("best_effort_timestamp_time").asString();
                long nanos;
                try {
                    nanos = new java.math.BigDecimal(raw)
                            .movePointRight(9)
                            .setScale(0, java.math.RoundingMode.HALF_UP)
                            .longValueExact();
                } catch (RuntimeException exception) {
                    throw new ExtractionException(
                            "invalid_frame_timestamp", "ffprobe returned an invalid frame timestamp", false, exception);
                }
                if (nanos < previous) {
                    throw new ExtractionException(
                            "non_monotonic_frame_timestamp", "video frame timestamps move backwards", false);
                }
                output.add(new EncodedFrameTimestamp(index, nanos, frame.path("key_frame").asInt() == 1));
                previous = nanos;
            }
            long origin = output.getFirst().presentationTimeNanos();
            return output.stream()
                    .map(frame -> new EncodedFrameTimestamp(
                            frame.index(), frame.presentationTimeNanos() - origin, frame.keyFrame()))
                    .toList();
        } catch (JacksonException exception) {
            throw new ExtractionException("frame_probe_parse_failed", "could not parse ffprobe frame output", true, exception);
        }
    }
}
