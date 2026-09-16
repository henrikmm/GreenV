package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.domain.ServiceOrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ServiceOrderResponse(
        int schemaVersion,
        UUID orderId,
        String reference,
        ServiceOrderStatus status,
        ServiceOrderPriority priority,
        UUID teamId,
        LocalDate scheduledFor,
        String notes,
        String equipment,
        // Estimated from the measured band, not surveyed. See OperationsService for the arithmetic
        // and why no polygon exists to measure instead.
        Double areaSquareMetres,
        Integer vegetationLevel,
        Double centreLat,
        Double centreLon,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<Target> targets,
        List<Event> history) {

    /** {@code windowIndex} is null when the order covers a segment measured whole. */
    public record Target(UUID sessionId, int segmentIndex, Integer windowIndex) {}

    public record Event(ServiceOrderStatus status, String note, String recordedBy, Instant recordedAt) {}

    public static ServiceOrderResponse from(ServiceOrder order) {
        return new ServiceOrderResponse(
                1,
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
                order.targets().stream()
                        .map(target -> new Target(
                                target.sessionId(), target.segmentIndex(), target.windowIndex()))
                        .toList(),
                order.history().stream()
                        .map(event -> new Event(
                                event.status(), event.note(), event.recordedBy(), event.recordedAt()))
                        .toList());
    }
}
