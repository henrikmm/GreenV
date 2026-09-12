package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderEvent;
import br.com.greenv.videoapi.domain.ServiceOrderQuery;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Where service orders are kept. */
public interface ServiceOrderStore {

    Page<ServiceOrder> find(ServiceOrderQuery query);

    Optional<ServiceOrder> findById(UUID orderId);

    void insert(ServiceOrder order);

    void update(ServiceOrder order);

    void appendEvent(ServiceOrderEvent event);

    void delete(UUID orderId);

    /**
     * How many references already start with this prefix, so the next one can continue the run.
     *
     * <p>Two dashboards opening an order in the same instant can compute the same number; the
     * unique index rejects the loser and the caller sees a conflict. That is the right trade for
     * an operations tool with one screen open, and it keeps the reference readable — a crew reads
     * this number out on the radio.
     */
    long countWithReferencePrefix(String prefix);

    /**
     * How many orders each team has, by status.
     *
     * <p>One query rather than one per team: the teams screen draws a row per team with three
     * counters each, and doing that with a round trip per counter is twelve round trips for four
     * teams.
     *
     * @return team id to status to count; the key {@code null} holds unassigned orders
     */
    Map<UUID, Map<String, Long>> countByTeamAndStatus();
}
