package br.com.greenv.videoapi.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A field team, with an identity rather than a name.
 *
 * <p>The demo stores the team on an order as free text, so every aggregation compares strings and
 * renaming a team splits its history in two. Here the order points at {@code teamId} and the name
 * is display only.
 */
public record Team(
        UUID teamId,
        String slug,
        String name,
        String shortName,
        String region,
        String colour,
        String initials,
        TeamStatus status,
        Instant createdAt,
        Instant updatedAt) {}
