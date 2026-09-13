package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One status an order passed through, and when.
 *
 * <p>Without this row "concluída" is a fact with no date, and no chart of throughput can be drawn
 * from anything but invention.
 */
public record ServiceOrderEvent(
        UUID eventId, UUID orderId, ServiceOrderStatus status, String note, String recordedBy, Instant recordedAt) {}
