package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentMeasurementAnnouncement;
import br.com.greenv.videoapi.port.MeasurementAnnouncementReader;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Only the identity is read off the wire.
 *
 * <p>The packet itself stays in object storage under a key the service derives for itself, so a
 * malformed or hostile announcement cannot point a later read somewhere else. Everything the
 * envelope also carries — the height grid, the per-frame positions, the depth provenance — is
 * already addressable and copying it here would duplicate a document.
 */
@Component
public class JacksonMeasurementAnnouncementReaderAdapter implements MeasurementAnnouncementReader {

    private final ObjectMapper objectMapper;

    public JacksonMeasurementAnnouncementReaderAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SegmentMeasurementAnnouncement read(String payload) {
        JsonNode root = objectMapper.readTree(payload);
        return new SegmentMeasurementAnnouncement(
                UUID.fromString(root.get("sessionId").asString()),
                root.get("segmentIndex").asInt(),
                text(root, "runId"),
                root.path("mock").asBoolean(false),
                Instant.parse(root.get("measuredAt").asString()));
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }
}
