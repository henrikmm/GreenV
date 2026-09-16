package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentReference;
import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderEvent;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.domain.ServiceOrderQuery;
import br.com.greenv.videoapi.domain.ServiceOrderStatus;
import br.com.greenv.videoapi.port.ServiceOrderStore;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcServiceOrderStoreAdapter implements ServiceOrderStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcServiceOrderStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Page<ServiceOrder> find(ServiceOrderQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        if (query.status() != null) {
            clauses.add("status = ?");
            arguments.add(query.status().wireValue());
        }
        if (query.priority() != null) {
            clauses.add("priority = ?");
            arguments.add(query.priority().wireValue());
        }
        if (query.teamId() != null) {
            clauses.add("team_id = ?");
            arguments.add(query.teamId());
        }
        if (query.search() != null) {
            clauses.add("(LOWER(reference) LIKE ? OR LOWER(COALESCE(notes, '')) LIKE ?)");
            String pattern = "%" + query.search().toLowerCase() + "%";
            arguments.add(pattern);
            arguments.add(pattern);
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_orders" + where, Long.class, arguments.toArray());

        List<Object> paged = new ArrayList<>(arguments);
        paged.add(query.limit());
        paged.add(query.offset());
        List<ServiceOrder> rows = jdbcTemplate.query(
                "SELECT * FROM service_orders" + where
                        + " ORDER BY created_at DESC, order_id DESC LIMIT ? OFFSET ?",
                JdbcServiceOrderStoreAdapter::mapOrder,
                paged.toArray());

        // Targets and history for the whole page in two queries, not two per row. A page of fifty
        // orders would otherwise be a hundred round trips before anything reaches the screen.
        List<UUID> ids = rows.stream().map(ServiceOrder::orderId).toList();
        Map<UUID, List<SegmentReference>> targets = targetsFor(ids);
        Map<UUID, List<ServiceOrderEvent>> history = historyFor(ids);
        List<ServiceOrder> items = rows.stream()
                .map(order -> withRelations(order, targets, history))
                .toList();
        return new Page<>(items, total == null ? 0 : total, query.limit(), query.offset());
    }

    @Override
    public Optional<ServiceOrder> findById(UUID orderId) {
        Optional<ServiceOrder> found = jdbcTemplate
                .query("SELECT * FROM service_orders WHERE order_id = ?",
                        JdbcServiceOrderStoreAdapter::mapOrder, orderId)
                .stream()
                .findFirst();
        return found.map(order ->
                withRelations(order, targetsFor(List.of(orderId)), historyFor(List.of(orderId))));
    }

    @Override
    @Transactional
    public void insert(ServiceOrder order) {
        jdbcTemplate.update(
                """
                INSERT INTO service_orders (order_id, reference, status, priority, team_id,
                                            scheduled_for, notes, equipment, area_square_metres,
                                            vegetation_level, centre_lat, centre_lon, created_by,
                                            created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                order.orderId(),
                order.reference(),
                order.status().wireValue(),
                order.priority().wireValue(),
                order.teamId(),
                order.scheduledFor() == null ? null : Date.valueOf(order.scheduledFor()),
                order.notes(),
                order.equipment(),
                order.areaSquareMetres(),
                order.vegetationLevel(),
                order.centreLat(),
                order.centreLon(),
                order.createdBy(),
                Timestamp.from(order.createdAt()),
                Timestamp.from(order.updatedAt()));
        for (SegmentReference target : order.targets()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO service_order_segments (order_id, session_id, segment_index,
                                                        window_index)
                    VALUES (?, ?, ?, ?)
                    """,
                    order.orderId(),
                    target.sessionId(),
                    target.segmentIndex(),
                    target.windowIndex());
        }
        for (ServiceOrderEvent event : order.history()) {
            appendEvent(event);
        }
    }

    @Override
    public void update(ServiceOrder order) {
        jdbcTemplate.update(
                """
                UPDATE service_orders
                   SET status = ?, priority = ?, team_id = ?, scheduled_for = ?, notes = ?,
                       updated_at = ?
                 WHERE order_id = ?
                """,
                order.status().wireValue(),
                order.priority().wireValue(),
                order.teamId(),
                order.scheduledFor() == null ? null : Date.valueOf(order.scheduledFor()),
                order.notes(),
                Timestamp.from(order.updatedAt()),
                order.orderId());
    }

    @Override
    public void appendEvent(ServiceOrderEvent event) {
        jdbcTemplate.update(
                """
                INSERT INTO service_order_events (event_id, order_id, status, note, recorded_by,
                                                  recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                event.eventId(),
                event.orderId(),
                event.status().wireValue(),
                event.note(),
                event.recordedBy(),
                Timestamp.from(event.recordedAt()));
    }

    @Override
    public void delete(UUID orderId) {
        jdbcTemplate.update("DELETE FROM service_orders WHERE order_id = ?", orderId);
    }

    @Override
    public long countWithReferencePrefix(String prefix) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_orders WHERE reference LIKE ?", Long.class, prefix + "%");
        return total == null ? 0 : total;
    }

    @Override
    public Map<UUID, Map<String, Long>> countByTeamAndStatus() {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT team_id, status, COUNT(*) AS total FROM service_orders GROUP BY team_id, status",
                result -> {
                    UUID teamId = result.getObject("team_id", UUID.class);
                    counts.computeIfAbsent(teamId, key -> new HashMap<>())
                            .put(result.getString("status"), result.getLong("total"));
                });
        return counts;
    }

    private Map<UUID, List<SegmentReference>> targetsFor(List<UUID> orderIds) {
        Map<UUID, List<SegmentReference>> byOrder = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        jdbcTemplate.query(
                "SELECT * FROM service_order_segments WHERE order_id IN (" + placeholders(orderIds)
                        + ") ORDER BY session_id, segment_index, window_index",
                result -> {
                    UUID orderId = result.getObject("order_id", UUID.class);
                    byOrder.computeIfAbsent(orderId, key -> new ArrayList<>())
                            .add(new SegmentReference(
                                    result.getObject("session_id", UUID.class),
                                    result.getInt("segment_index"),
                                    result.getObject("window_index", Integer.class)));
                },
                orderIds.toArray());
        return byOrder;
    }

    private Map<UUID, List<ServiceOrderEvent>> historyFor(List<UUID> orderIds) {
        Map<UUID, List<ServiceOrderEvent>> byOrder = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        jdbcTemplate.query(
                "SELECT * FROM service_order_events WHERE order_id IN (" + placeholders(orderIds)
                        + ") ORDER BY recorded_at, event_id",
                result -> {
                    UUID orderId = result.getObject("order_id", UUID.class);
                    byOrder.computeIfAbsent(orderId, key -> new ArrayList<>())
                            .add(new ServiceOrderEvent(
                                    result.getObject("event_id", UUID.class),
                                    orderId,
                                    ServiceOrderStatus.of(result.getString("status")),
                                    result.getString("note"),
                                    result.getString("recorded_by"),
                                    instant(result, "recorded_at")));
                },
                orderIds.toArray());
        return byOrder;
    }

    private static String placeholders(List<UUID> ids) {
        return String.join(", ", ids.stream().map(id -> "?").toList());
    }

    private static ServiceOrder withRelations(
            ServiceOrder order,
            Map<UUID, List<SegmentReference>> targets,
            Map<UUID, List<ServiceOrderEvent>> history) {
        return new ServiceOrder(
                order.orderId(),
                order.reference(),
                order.status(),
                order.priority(),
                order.teamId(),
                order.scheduledFor(),
                order.notes(),
                order.equipment(),
                order.areaSquareMetres(),
                order.vegetationLevel(),
                order.centreLat(),
                order.centreLon(),
                order.createdBy(),
                order.createdAt(),
                order.updatedAt(),
                targets.getOrDefault(order.orderId(), List.of()),
                history.getOrDefault(order.orderId(), List.of()));
    }

    static ServiceOrder mapOrder(ResultSet result, int row) throws SQLException {
        Date scheduled = result.getDate("scheduled_for");
        return new ServiceOrder(
                result.getObject("order_id", UUID.class),
                result.getString("reference"),
                ServiceOrderStatus.of(result.getString("status")),
                ServiceOrderPriority.of(result.getString("priority")),
                result.getObject("team_id", UUID.class),
                scheduled == null ? null : scheduled.toLocalDate(),
                result.getString("notes"),
                result.getString("equipment"),
                decimal(result, "area_square_metres"),
                integer(result, "vegetation_level"),
                decimal(result, "centre_lat"),
                decimal(result, "centre_lon"),
                result.getString("created_by"),
                instant(result, "created_at"),
                instant(result, "updated_at"),
                List.of(),
                List.of());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Double decimal(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static Integer integer(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    /** Kept so a caller can build a scheduled date without importing {@code java.sql}. */
    public static LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
