package br.com.greenv.videoapi.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a caller supplies to open an order.
 *
 * <p>Deliberately short. The reference, the area, the level and the position are derived from the
 * segments by the service: a caller that could set them could open an order claiming a height
 * nobody measured.
 */
public record CreateServiceOrderRequest(
        @NotNull String priority,
        UUID teamId,
        LocalDate scheduledFor,
        String notes,
        @NotEmpty List<Target> targets) {

    /**
     * One stretch the order covers.
     *
     * @param windowIndex which window of that segment, or null for the whole segment. A segment is
     *     cut into windows of about 25 m and each is measured on its own, so a target that names
     *     one is asking for that window's evidence — its height, its level, its position and its
     *     length — and not for the segment's summary of all of them
     */
    public record Target(
            @NotNull UUID sessionId, @NotNull Integer segmentIndex, Integer windowIndex) {}
}
