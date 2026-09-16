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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * The fallback when a segment produced no drawable track, and nothing better than a guess.
     *
     * <p>It used to be the whole calculation, and it is wrong by more than an order of magnitude
     * for what has been captured: measured from the tracks on file, a ten-second walking segment
     * covers 7.7 to 11.7 metres and the one car segment covers 83.2. Every order came out a
     * multiple of 1500 m² because of it.
     */
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

        // Deduplicated because the evidence is summed: the same window named twice would double
        // the area the order claims, and because the target table no longer has a key to refuse
        // it. See V15 for why that key had to go.
        List<SegmentReference> targets = new ArrayList<>(new LinkedHashSet<>(draft.targets()));
        List<CaptureSegmentDocument> segments = new ArrayList<>();
        for (SegmentReference target : targets) {
            CaptureSegmentDocument segment = captures
                    .findSegment(target.sessionId(), target.segmentIndex())
                    .orElseThrow(() -> new ApplicationException(
                            FailureKind.NOT_FOUND,
                            "segment-not-found",
                            "No segment " + target.segmentIndex() + " in session " + target.sessionId()));
            // A target that names a window is answered by that window's own reading. The
            // segment's is a rollup of every window it has, and opening an order against it would
            // send a crew to two hundred metres of road on the evidence of the worst twenty-five.
            CaptureSegmentDocument reading = target.windowIndex() == null
                    ? segment
                    : captures.findWindow(target.sessionId(), target.segmentIndex(), target.windowIndex())
                            .orElse(null);
            if (reading == null || !reading.isMeasured()) {
                throw new ApplicationException(
                        FailureKind.CONFLICT,
                        "segment-not-measured",
                        describe(target) + " has no measurement to justify an order");
            }
            segments.add(reading);
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
                targets,
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

    /** The stretch a refusal is about, in the words the request used. */
    private static String describe(SegmentReference target) {
        String segment = "Segment " + target.segmentIndex() + " of session " + target.sessionId();
        return target.windowIndex() == null
                ? segment
                : "Window " + target.windowIndex() + " of " + segment.toLowerCase(Locale.ROOT);
    }

    private String nextReference(Instant now) {
        String prefix = "OS-ROÇ-" + REFERENCE_MONTH.format(now) + "-";
        return prefix + String.format("%04d", 1001 + orders.countWithReferencePrefix(prefix));
    }

    /**
     * The area a crew will cut: the band the grid measured, run along the distance the camera
     * actually travelled.
     *
     * <p>This system still has no parcel geometry, and this is still an estimate. What changed is
     * that the length is now measured rather than assumed — the track is the path the phone
     * recorded, and its length is how far that stretch runs. Each segment is counted on its own,
     * so a combined order over a walk and a drive no longer pretends the two are the same size.
     *
     * <p>A segment with fewer than two distinct fixes has no length, and only those fall back to
     * the assumption. Mixing the two is honest as long as nothing here claims it is a survey; the
     * screen calls it an estimate and this returns one number, not a false precision.
     */
    private static Double areaOf(List<CaptureSegmentDocument> segments) {
        double metres = 0;
        for (CaptureSegmentDocument segment : segments) {
            Double length = projection(segment).trackLengthM();
            if (length != null && length > 0) {
                metres += length;
                continue;
            }
            // A window with no drawable track still knows how far along the segment it runs,
            // because the extractor cut it to a distance. That is a measured length and not an
            // assumption, so only a whole segment ever falls back to the guess below.
            Double extent = extentOf(segment);
            metres += extent == null ? ASSUMED_SEGMENT_LENGTH_METRES : extent;
        }
        return metres * BAND_WIDTH_METRES;
    }

    /** How far a window runs along its segment's camera path, when both ends were recorded. */
    private static Double extentOf(CaptureSegmentDocument segment) {
        if (segment.windowStartMeters() == null || segment.windowEndMeters() == null) {
            return null;
        }
        double extent = segment.windowEndMeters() - segment.windowStartMeters();
        return extent > 0 ? extent : null;
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
