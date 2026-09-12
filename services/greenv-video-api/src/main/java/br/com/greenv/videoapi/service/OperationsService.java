package br.com.greenv.videoapi.service;

import br.com.greenv.videoapi.domain.CaptureSegmentDocument;
import br.com.greenv.videoapi.domain.MeasurementProjection;
import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.SegmentReference;
import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderDraft;
import br.com.greenv.videoapi.domain.ServiceOrderEvent;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.domain.ServiceOrderQuery;
import br.com.greenv.videoapi.domain.ServiceOrderStatus;
import br.com.greenv.videoapi.domain.Team;
import br.com.greenv.videoapi.domain.TeamWorkload;
import br.com.greenv.videoapi.port.CaptureSessionStore;
import br.com.greenv.videoapi.port.IdentifierGenerator;
import br.com.greenv.videoapi.port.OperationsUseCase;
import br.com.greenv.videoapi.port.ServiceOrderStore;
import br.com.greenv.videoapi.port.TeamStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orders and teams: the half of the system where a person decides something.
 *
 * <p>The rule that shapes this class is that an order carries its own evidence. The area, the
 * level and the centre are read from the measured segments at the moment the order is opened and
 * then never touched again, so a later pass that measures the same verge lower cannot rewrite the
 * reason a crew was sent. Everything else on an order is editable, because everything else is a
 * plan rather than a measurement.
 */
@Service
public class OperationsService implements OperationsUseCase {

    /** The five metres either side of the track that the grid actually measures. */
    private static final double BAND_WIDTH_METRES = 10.0;

    /** What a segment covers along the road when its own length was not recorded. */
    private static final double ASSUMED_SEGMENT_LENGTH_METRES = 150.0;

    private static final DateTimeFormatter REFERENCE_MONTH =
            DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);

    private final ServiceOrderStore orders;
    private final TeamStore teams;
    private final CaptureSessionStore captures;
    private final IdentifierGenerator identifiers;
    private final Clock clock;

    public OperationsService(
            ServiceOrderStore orders,
            TeamStore teams,
            CaptureSessionStore captures,
            IdentifierGenerator identifiers,
            Clock clock) {
        this.orders = orders;
        this.teams = teams;
        this.captures = captures;
        this.identifiers = identifiers;
        this.clock = clock;
    }

    @Override
    public List<TeamWorkload> listTeams() {
        Map<UUID, Map<String, Long>> counts = orders.countByTeamAndStatus();
        List<TeamWorkload> workloads = new ArrayList<>();
        for (Team team : teams.findAll()) {
            Map<String, Long> byStatus = counts.getOrDefault(team.teamId(), Map.of());
            workloads.add(new TeamWorkload(
                    team,
                    byStatus.getOrDefault(ServiceOrderStatus.PENDENTE.wireValue(), 0L),
                    byStatus.getOrDefault(ServiceOrderStatus.EM_ANDAMENTO.wireValue(), 0L),
                    byStatus.getOrDefault(ServiceOrderStatus.CONCLUIDA.wireValue(), 0L)));
        }
        return List.copyOf(workloads);
    }

    @Override
    public Team team(UUID teamId) {
        return teams.findById(teamId)
                .orElseThrow(() -> new ApplicationException(
                        FailureKind.NOT_FOUND, "team-not-found", "No team with id " + teamId));
    }

    @Override
    public Page<ServiceOrder> listOrders(ServiceOrderQuery query) {
        return orders.find(query);
    }

    @Override
    public ServiceOrder order(UUID orderId) {
        return orders.findById(orderId)
                .orElseThrow(() -> new ApplicationException(
                        FailureKind.NOT_FOUND, "order-not-found", "No service order with id " + orderId));
    }

    @Override
    public ServiceOrder openOrder(ServiceOrderDraft draft, String openedBy) {
        if (draft.targets().isEmpty()) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT,
                    "order-needs-a-target",
                    "A service order must cover at least one measured segment");
        }
        if (draft.priority() == null) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "order-needs-a-priority", "priority is required");
        }
        if (draft.teamId() != null) {
            team(draft.teamId());
        }

        List<CaptureSegmentDocument> segments = new ArrayList<>();
        for (SegmentReference target : draft.targets()) {
            CaptureSegmentDocument segment = captures
                    .findSegment(target.sessionId(), target.segmentIndex())
                    .orElseThrow(() -> new ApplicationException(
                            FailureKind.NOT_FOUND,
                            "segment-not-found",
                            "No segment " + target.segmentIndex() + " in session " + target.sessionId()));
            if (!segment.isMeasured()) {
                throw new ApplicationException(
                        FailureKind.CONFLICT,
                        "segment-not-measured",
                        "Segment " + target.segmentIndex() + " of session " + target.sessionId()
                                + " has no measurement to justify an order");
            }
            segments.add(segment);
        }

        Instant now = clock.instant();
        UUID orderId = identifiers.next();
        ServiceOrderEvent opened = new ServiceOrderEvent(
                identifiers.next(), orderId, ServiceOrderStatus.PENDENTE, null, openedBy, now);
        ServiceOrder order = new ServiceOrder(
                orderId,
                nextReference(now),
                ServiceOrderStatus.PENDENTE,
                draft.priority(),
                draft.teamId(),
                draft.scheduledFor(),
                draft.notes(),
                equipmentFor(segments),
                areaOf(segments),
                worstLevel(segments),
                averageOf(segments, true),
                averageOf(segments, false),
                openedBy,
                now,
                now,
                draft.targets(),
                List.of(opened));
        orders.insert(order);
        return order(orderId);
    }

    @Override
    public ServiceOrder amendOrder(
            UUID orderId,
            ServiceOrderStatus status,
            ServiceOrderPriority priority,
            UUID teamId,
            LocalDate scheduledFor,
            String notes,
            String amendedBy) {
        ServiceOrder existing = order(orderId);
        if (teamId != null) {
            team(teamId);
        }
        Instant now = clock.instant();
        ServiceOrderStatus nextStatus = status == null ? existing.status() : status;
        ServiceOrder amended = new ServiceOrder(
                existing.orderId(),
                existing.reference(),
                nextStatus,
                priority == null ? existing.priority() : priority,
                teamId == null ? existing.teamId() : teamId,
                scheduledFor == null ? existing.scheduledFor() : scheduledFor,
                notes == null ? existing.notes() : notes,
                existing.equipment(),
                existing.areaSquareMetres(),
                existing.vegetationLevel(),
                existing.centreLat(),
                existing.centreLon(),
                existing.createdBy(),
                existing.createdAt(),
                now,
                existing.targets(),
                existing.history());
        orders.update(amended);
        // Only a change of status is worth a row: the history is what a throughput chart reads,
        // and an entry per typo in the notes would make "when did this finish" unanswerable.
        if (nextStatus != existing.status()) {
            orders.appendEvent(new ServiceOrderEvent(
                    identifiers.next(), orderId, nextStatus, null, amendedBy, now));
        }
        return order(orderId);
    }

    @Override
    public void cancelOrder(UUID orderId, String cancelledBy) {
        // Cancelled, not deleted. An order that was opened is a decision someone took, and the
        // record of it is the point.
        amendOrder(orderId, ServiceOrderStatus.CANCELADA, null, null, null, null, cancelledBy);
    }

    private String nextReference(Instant now) {
        String prefix = "OS-ROÇ-" + REFERENCE_MONTH.format(now) + "-";
        return prefix + String.format("%04d", 1001 + orders.countWithReferencePrefix(prefix));
    }

    /**
     * The area a crew will cut, from the band the grid measured rather than from a polygon.
     *
     * <p>This system has no parcel geometry: the only footprint it knows is the five metres either
     * side of the track. Segment length is not recorded, so this is an estimate and is documented
     * as one rather than dressed up as a survey.
     */
    private static Double areaOf(List<CaptureSegmentDocument> segments) {
        return segments.size() * BAND_WIDTH_METRES * ASSUMED_SEGMENT_LENGTH_METRES;
    }

    private static Integer worstLevel(List<CaptureSegmentDocument> segments) {
        Integer worst = null;
        for (CaptureSegmentDocument segment : segments) {
            Integer level = projection(segment).level();
            if (level != null && (worst == null || level > worst)) {
                worst = level;
            }
        }
        return worst;
    }

    private static Double averageOf(List<CaptureSegmentDocument> segments, boolean latitude) {
        double sum = 0;
        int counted = 0;
        for (CaptureSegmentDocument segment : segments) {
            MeasurementProjection projection = projection(segment);
            Double value = latitude ? projection.trackCenterLat() : projection.trackCenterLon();
            if (value != null) {
                sum += value;
                counted++;
            }
        }
        return counted == 0 ? null : sum / counted;
    }

    /**
     * What a crew needs to take, from the height that was measured.
     *
     * <p>The demo reads this off the polygon's own name, which came from the concession's
     * spreadsheet. Nothing here has that, so it is derived from the level, and the rule is the
     * plain one: taller vegetation needs a bigger machine.
     */
    private static String equipmentFor(List<CaptureSegmentDocument> segments) {
        Integer level = worstLevel(segments);
        if (level == null) {
            return null;
        }
        return switch (level) {
            case 3 -> "Trator com braço articulado";
            case 2 -> "Spider, Giro-Zero ou Trator com trincheira";
            default -> "Apenas manual";
        };
    }

    private static MeasurementProjection projection(CaptureSegmentDocument segment) {
        return Optional.ofNullable(segment.measurement()).orElse(MeasurementProjection.EMPTY);
    }
}
