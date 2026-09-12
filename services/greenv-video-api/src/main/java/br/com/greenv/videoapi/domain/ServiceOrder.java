package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A decision to send a crew somewhere, and the evidence it was taken on.
 *
 * <p>The measured values are copied in when the order is opened rather than joined at read time.
 * An order is a judgement made against what was known that day; a later pass measuring the same
 * stretch lower must not quietly rewrite why the crew was sent.
 *
 * @param reference what a crew reads out on the radio, e.g. {@code OS-ROÇ-202609-1098}
 * @param targets the measured stretches this order covers, at least one
 * @param history every status the order has had, oldest first
 */
public record ServiceOrder(
        UUID orderId,
        String reference,
        ServiceOrderStatus status,
        ServiceOrderPriority priority,
        UUID teamId,
        LocalDate scheduledFor,
        String notes,
        String equipment,
        Double areaSquareMetres,
        Integer vegetationLevel,
        Double centreLat,
        Double centreLon,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<SegmentReference> targets,
        List<ServiceOrderEvent> history) {

    public ServiceOrder {
        targets = targets == null ? List.of() : List.copyOf(targets);
        history = history == null ? List.of() : List.copyOf(history);
    }
}
