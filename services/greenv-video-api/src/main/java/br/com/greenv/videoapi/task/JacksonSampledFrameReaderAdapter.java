package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SampledFrame;
import br.com.greenv.videoapi.port.SampledFrameReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Joins the manifest's file list to the packet's camera positions, by canonical frame number.
 *
 * <p>The manifest leads. It is the publication record, so a frame it does not list is a frame that
 * was planned and never written, and offering it would hand out a 404 from object storage instead
 * of an honest absence here. Positions are attached where they match and left null where they do
 * not.
 */
@Component
public class JacksonSampledFrameReaderAdapter implements SampledFrameReader {

    private final ObjectMapper objectMapper;

    public JacksonSampledFrameReaderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SampledFrame> read(byte[] manifest, byte[] resultPacket) {
        if (manifest == null || manifest.length == 0) {
            return List.of();
        }
        Map<Integer, JsonNode> positions = positionsByFrame(resultPacket);
        JsonNode root = objectMapper.readTree(new String(manifest, StandardCharsets.UTF_8));
        List<SampledFrame> frames = new ArrayList<>();
        for (JsonNode published : root.path("sampledFrames")) {
            String fileName = published.path("fileName").asString("");
            if (fileName.isBlank()) {
                continue;
            }
            int canonical = canonicalFrameOf(fileName);
            JsonNode position = positions.getOrDefault(canonical, objectMapper.nullNode());
            frames.add(new SampledFrame(
                    fileName,
                    canonical,
                    published.path("sizeBytes").isNumber() ? published.path("sizeBytes").asLong() : null,
                    instant(position, "capturedAtUtc"),
                    decimal(position, "latitude"),
                    decimal(position, "longitude"),
                    decimal(position, "horizontalAccuracyMeters"),
                    text(position, "locationQuality")));
        }
        return List.copyOf(frames);
    }

    private Map<Integer, JsonNode> positionsByFrame(byte[] resultPacket) {
        if (resultPacket == null || resultPacket.length == 0) {
            return Map.of();
        }
        try {
            JsonNode packet = objectMapper.readTree(new String(resultPacket, StandardCharsets.UTF_8));
            Map<Integer, JsonNode> byFrame = new HashMap<>();
            for (JsonNode position : packet.path("positions")) {
                if (position.path("canonicalFrame").isNumber()) {
                    byFrame.put(position.path("canonicalFrame").asInt(), position);
                }
            }
            return byFrame;
        } catch (RuntimeException unreadable) {
            // The frames are still listable without their coordinates, and saying so is better
            // than refusing the whole list over a packet the map only decorates.
            return Map.of();
        }
    }

    /** {@code frame-0042.jpg} is canonical frame 42, which is how a position names it. */
    private static int canonicalFrameOf(String fileName) {
        StringBuilder digits = new StringBuilder();
        for (char character : fileName.toCharArray()) {
            if (Character.isDigit(character)) {
                digits.append(character);
            }
        }
        return digits.isEmpty() ? -1 : Integer.parseInt(digits.toString());
    }

    private static Double decimal(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isString() ? value.asString() : null;
    }

    private static Instant instant(JsonNode parent, String field) {
        String value = text(parent, field);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException unparseable) {
            return null;
        }
    }
}
