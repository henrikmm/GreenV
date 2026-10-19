package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.DemoCapture;
import br.com.greenv.videoapi.domain.DemoPhase;
import br.com.greenv.videoapi.domain.DemoPhases;
import br.com.greenv.videoapi.domain.DemoVideo;
import br.com.greenv.videoapi.domain.DemoVideoProbe;
import br.com.greenv.videoapi.port.DemoCaptureStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDemoCaptureStoreAdapter implements DemoCaptureStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Object>> DETAIL = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcDemoCaptureStoreAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void insert(DemoCapture capture) {
        jdbcTemplate.update(
                """
                INSERT INTO demo_captures (capture_id, state, meta_json, rodovia, sentido, km,
                                           capturado_em, attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                capture.id(),
                capture.state(),
                json(capture.meta()),
                capture.rodovia(),
                capture.sentido(),
                capture.km(),
                Timestamp.from(capture.capturadoEm()),
                capture.attempts(),
                Timestamp.from(capture.createdAt()),
                Timestamp.from(capture.updatedAt()));
        for (DemoPhase phase : capture.phases()) {
            appendPhase(capture.id(), phase);
        }
    }

    @Override
    public Optional<DemoCapture> find(String captureId) {
        return jdbcTemplate
                .query("SELECT * FROM demo_captures WHERE capture_id = ?", this::mapCapture, captureId)
                .stream()
                .findFirst()
                .map(capture -> withPhases(capture, phasesFor(List.of(captureId))));
    }

    @Override
    public List<DemoCapture> latest(int limit) {
        List<DemoCapture> rows = jdbcTemplate.query(
                "SELECT * FROM demo_captures ORDER BY created_at DESC, capture_id DESC LIMIT ?",
                this::mapCapture,
                limit);
        // Every timeline on the page in one query, not one per capture: the site asks for this
        // list every two seconds.
        Map<String, List<DemoPhase>> phases = phasesFor(rows.stream().map(DemoCapture::id).toList());
        return rows.stream().map(capture -> withPhases(capture, phases)).toList();
    }

    @Override
    @Transactional
    public boolean beginUpload(String captureId, DemoPhase uploading) {
        // A failed capture with no video is one whose upload broke off; it takes the retry.
        int updated = jdbcTemplate.update(
                """
                UPDATE demo_captures SET state = ?, updated_at = ?
                 WHERE capture_id = ?
                   AND (state = ? OR (state = ? AND video_object_key IS NULL))
                """,
                uploading.phase(),
                Timestamp.from(uploading.at()),
                captureId,
                DemoPhases.CREATED,
                DemoPhases.FAILED);
        if (updated == 0) {
            return false;
        }
        appendPhase(captureId, uploading);
        return true;
    }

    @Override
    @Transactional
    public boolean save(DemoCapture capture, DemoPhase phase, String leaseOwner) {
        DemoVideo video = capture.video();
        List<Object> arguments = new ArrayList<>();
        arguments.add(phase == null ? capture.state() : phase.phase());
        arguments.add(video == null ? null : video.objectKey());
        arguments.add(video == null ? null : video.contentType());
        arguments.add(video == null ? null : video.bytes());
        arguments.add(video == null ? null : video.sha256());
        arguments.add(video == null ? null : video.transferMs());
        arguments.add(video == null || video.probe() == null ? null : json(video.probe()));
        arguments.add(capture.frameKeys().isEmpty() ? null : json(capture.frameKeys()));
        arguments.add(capture.depthJobId());
        arguments.add(capture.depthJobSubmittedAt() == null ? null : Timestamp.from(capture.depthJobSubmittedAt()));
        arguments.add(capture.manifest() == null ? null : json(capture.manifest()));
        arguments.add(capture.gpu());
        arguments.add(capture.gpuSeconds());
        arguments.add(capture.attempts());
        arguments.add(Timestamp.from(phase == null ? capture.updatedAt() : phase.at()));
        arguments.add(capture.id());
        String guard = "";
        if (leaseOwner != null) {
            guard = " AND lease_owner = ?";
            arguments.add(leaseOwner);
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE demo_captures
                   SET state = ?, video_object_key = ?, video_content_type = ?, video_bytes = ?,
                       video_sha256 = ?, video_transfer_ms = ?, video_probe_json = ?,
                       frame_keys_json = ?, depth_job_id = ?, depth_job_submitted_at = ?,
                       manifest_json = ?, gpu = ?, gpu_seconds = ?, attempts = ?, updated_at = ?
                 WHERE capture_id = ?
                """ + guard,
                arguments.toArray());
        if (updated == 0) {
            return false;
        }
        if (phase != null) {
            appendPhase(capture.id(), phase);
        }
        return true;
    }

    @Override
    public List<String> claimable(List<String> states, Instant now, int limit) {
        if (states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(states.size(), "?"));
        List<Object> arguments = new ArrayList<>(states);
        arguments.add(Timestamp.from(now));
        arguments.add(limit);
        return jdbcTemplate.queryForList(
                "SELECT capture_id FROM demo_captures WHERE state IN (" + placeholders + ")"
                        + " AND (lease_until IS NULL OR lease_until < ?)"
                        + " ORDER BY created_at, capture_id LIMIT ?",
                String.class,
                arguments.toArray());
    }

    @Override
    public boolean claim(String captureId, String owner, Instant now, Instant until) {
        // One statement, so two replicas racing for the same capture cannot both see it free: the
        // database lets exactly one of these updates match.
        return jdbcTemplate.update(
                        """
                        UPDATE demo_captures SET lease_owner = ?, lease_until = ?
                         WHERE capture_id = ? AND (lease_until IS NULL OR lease_until < ?)
                        """,
                        owner,
                        Timestamp.from(until),
                        captureId,
                        Timestamp.from(now))
                == 1;
    }

    @Override
    public boolean renew(String captureId, String owner, Instant until) {
        return jdbcTemplate.update(
                        "UPDATE demo_captures SET lease_until = ? WHERE capture_id = ? AND lease_owner = ?",
                        Timestamp.from(until),
                        captureId,
                        owner)
                == 1;
    }

    @Override
    public void release(String captureId, String owner) {
        jdbcTemplate.update(
                "UPDATE demo_captures SET lease_owner = NULL, lease_until = NULL"
                        + " WHERE capture_id = ? AND lease_owner = ?",
                captureId,
                owner);
    }

    @Override
    public List<DemoCapture> createdBefore(Instant cutoff, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM demo_captures WHERE created_at < ? ORDER BY created_at LIMIT ?",
                this::mapCapture,
                Timestamp.from(cutoff),
                limit);
    }

    @Override
    public void delete(String captureId) {
        // The timeline goes with it: ON DELETE CASCADE.
        jdbcTemplate.update("DELETE FROM demo_captures WHERE capture_id = ?", captureId);
    }

    private void appendPhase(String captureId, DemoPhase phase) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), -1) + 1 FROM demo_capture_phases WHERE capture_id = ?",
                Integer.class,
                captureId);
        jdbcTemplate.update(
                """
                INSERT INTO demo_capture_phases (capture_id, seq, phase, occurred_at, t_ms, detail_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                captureId,
                next == null ? 0 : next,
                phase.phase(),
                Timestamp.from(phase.at()),
                phase.tMs(),
                phase.detail() == null ? null : json(phase.detail()));
    }

    private Map<String, List<DemoPhase>> phasesFor(List<String> captureIds) {
        Map<String, List<DemoPhase>> phases = new LinkedHashMap<>();
        if (captureIds.isEmpty()) {
            return phases;
        }
        String placeholders = String.join(", ", Collections.nCopies(captureIds.size(), "?"));
        jdbcTemplate.query(
                "SELECT * FROM demo_capture_phases WHERE capture_id IN (" + placeholders + ")"
                        + " ORDER BY capture_id, seq",
                result -> {
                    phases.computeIfAbsent(result.getString("capture_id"), ignored -> new ArrayList<>())
                            .add(new DemoPhase(
                                    result.getString("phase"),
                                    instant(result, "occurred_at"),
                                    result.getLong("t_ms"),
                                    detail(result.getString("detail_json"))));
                },
                captureIds.toArray());
        return phases;
    }

    private static DemoCapture withPhases(DemoCapture capture, Map<String, List<DemoPhase>> phases) {
        return new DemoCapture(
                capture.id(), capture.state(), capture.meta(), capture.rodovia(), capture.sentido(),
                capture.km(), capture.capturadoEm(), capture.createdAt(), capture.updatedAt(),
                capture.video(), capture.frameKeys(), capture.depthJobId(),
                capture.depthJobSubmittedAt(), capture.manifest(), capture.gpu(),
                capture.gpuSeconds(), capture.attempts(),
                phases.getOrDefault(capture.id(), List.of()));
    }

    private DemoCapture mapCapture(ResultSet result, int row) throws SQLException {
        String videoKey = result.getString("video_object_key");
        DemoVideo video = videoKey == null
                ? null
                : new DemoVideo(
                        videoKey,
                        result.getString("video_content_type"),
                        result.getLong("video_bytes"),
                        result.getString("video_sha256"),
                        result.getLong("video_transfer_ms"),
                        probe(result.getString("video_probe_json")));
        String frameKeys = result.getString("frame_keys_json");
        String manifest = result.getString("manifest_json");
        return new DemoCapture(
                result.getString("capture_id"),
                result.getString("state"),
                objectMapper.readTree(result.getString("meta_json")),
                result.getString("rodovia"),
                result.getString("sentido"),
                nullableDouble(result, "km"),
                instant(result, "capturado_em"),
                instant(result, "created_at"),
                instant(result, "updated_at"),
                video,
                frameKeys == null ? List.of() : objectMapper.readValue(frameKeys, STRING_LIST),
                result.getString("depth_job_id"),
                instant(result, "depth_job_submitted_at"),
                manifest == null ? null : objectMapper.readTree(manifest),
                result.getString("gpu"),
                nullableDouble(result, "gpu_seconds"),
                result.getInt("attempts"),
                List.of());
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private DemoVideoProbe probe(String json) {
        return json == null ? null : objectMapper.readValue(json, DemoVideoProbe.class);
    }

    private Map<String, Object> detail(String json) {
        return json == null ? null : objectMapper.readValue(json, DETAIL);
    }

    private String json(Object value) {
        return value instanceof JsonNode node ? node.toString() : objectMapper.writeValueAsString(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
