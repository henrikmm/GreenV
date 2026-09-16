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
    public List<SampledFrame> read(byte[] manifest, List<byte[]> resultPackets) {
        if (manifest == null || manifest.length == 0) {
            return List.of();
        }
        Map<Integer, Located> positions = positionsByFrame(resultPackets);
        JsonNode root = objectMapper.readTree(new String(manifest, StandardCharsets.UTF_8));
        List<SampledFrame> frames = new ArrayList<>();
        for (JsonNode published : root.path("sampledFrames")) {
            String fileName = published.path("fileName").asString("");
            if (fileName.isBlank()) {
                continue;
            }
            int canonical = canonicalFrameOf(fileName);
            Located located = positions.get(canonical);
            JsonNode position = located == null ? objectMapper.nullNode() : located.position();
            frames.add(new SampledFrame(
                    fileName,
                    canonical,
                    published.path("sizeBytes").isNumber() ? published.path("sizeBytes").asLong() : null,
                    instant(position, "capturedAtUtc"),
                    decimal(position, "latitude"),
                    decimal(position, "longitude"),
                    decimal(position, "horizontalAccuracyMeters"),
                    text(position, "locationQuality"),
                    located == null ? null : located.windowIndex()));
        }
        return List.copyOf(frames);
    }

    /** A camera position, and the window whose packet recorded it. */
    private record Located(JsonNode position, Integer windowIndex) {}

    /**
     * Every position any of the packets knows, by frame.
     *
     * <p>The packets of one segment describe disjoint sets of frames — a photograph was
     * reconstructed in exactly one window — so the order they are read in never decides anything.
     * A packet that cannot be read costs its own frames their coordinates and not the list.
     *
     * <p>Each packet names its own window, so the frame inherits it: that is how a screen showing
     * one 25 m stretch tells its photographs from its neighbour's, without the browser guessing
     * from coordinates which line a picture was taken nearest to.
     */
    private Map<Integer, Located> positionsByFrame(List<byte[]> resultPackets) {
        if (resultPackets == null || resultPackets.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Located> byFrame = new HashMap<>();
        for (byte[] resultPacket : resultPackets) {
            if (resultPacket == null || resultPacket.length == 0) {
                continue;
            }
            try {
                JsonNode packet = objectMapper.readTree(new String(resultPacket, StandardCharsets.UTF_8));
                JsonNode window = packet.path("windowIndex");
                Integer windowIndex = window.isNumber() ? window.asInt() : null;
                for (JsonNode position : packet.path("positions")) {
                    if (position.path("canonicalFrame").isNumber()) {
                        byFrame.put(
                                position.path("canonicalFrame").asInt(),
                                new Located(position, windowIndex));
                    }
                }
            } catch (RuntimeException unreadable) {
                // The frames are still listable without their coordinates, and saying so is
                // better than refusing the whole list over a packet the map only decorates.
            }
        }
        return byFrame;
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
