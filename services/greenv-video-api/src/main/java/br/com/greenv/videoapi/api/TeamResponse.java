package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.TeamStatus;
import br.com.greenv.videoapi.domain.TeamWorkload;
import java.util.UUID;

/**
 * A team as the dashboard draws it, with the three counters it always shows beside the name.
 */
public record TeamResponse(
        int schemaVersion,
        UUID teamId,
        String slug,
        String name,
        String shortName,
        String region,
        String colour,
        String initials,
        TeamStatus status,
        long pendingOrders,
        long inProgressOrders,
        long completedOrders,
        long totalOrders) {

    public static TeamResponse from(TeamWorkload workload) {
        var team = workload.team();
        return new TeamResponse(
                1,
                team.teamId(),
                team.slug(),
                team.name(),
                team.shortName(),
                team.region(),
                team.colour(),
                team.initials(),
                team.status(),
                workload.pending(),
                workload.inProgress(),
                workload.completed(),
                workload.total());
    }
}
