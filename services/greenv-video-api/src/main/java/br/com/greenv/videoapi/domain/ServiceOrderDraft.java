package br.com.greenv.videoapi.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything a caller supplies to open an order.
 *
 * <p>Separate from {@link ServiceOrder} because the two are genuinely different: the identifier,
 * the reference, the timestamps and the measured evidence are all derived by the service from the
 * targets, and a caller that could set them could open an order claiming a height nobody measured.
 */
public record ServiceOrderDraft(
        ServiceOrderPriority priority,
        UUID teamId,
        LocalDate scheduledFor,
        String notes,
        List<SegmentReference> targets) {

    public ServiceOrderDraft {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
