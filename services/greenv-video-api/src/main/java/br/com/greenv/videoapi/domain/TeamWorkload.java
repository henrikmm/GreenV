package br.com.greenv.videoapi.domain;

/**
 * A team and how much work it is carrying.
 *
 * <p>The counters travel with the team because the screen that lists teams always shows both, and
 * fetching them separately is a round trip per team per counter.
 */
public record TeamWorkload(Team team, long pending, long inProgress, long completed) {

    public long total() {
        return pending + inProgress + completed;
    }
}
