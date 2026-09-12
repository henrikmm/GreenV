package br.com.greenv.videoapi.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a person may change about an order.
 *
 * <p>Every field is optional and a null one is left alone, which is what makes the route a PATCH.
 * The measured evidence is absent on purpose: it is not editable at all.
 */
public record UpdateServiceOrderRequest(
        String status, String priority, UUID teamId, LocalDate scheduledFor, String notes) {}
