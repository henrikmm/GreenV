package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.Page;
import br.com.greenv.videoapi.domain.ServiceOrder;
import br.com.greenv.videoapi.domain.ServiceOrderDraft;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.domain.ServiceOrderQuery;
import br.com.greenv.videoapi.domain.ServiceOrderStatus;
import br.com.greenv.videoapi.domain.Team;
import br.com.greenv.videoapi.domain.TeamWorkload;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Turning measurements into work: who goes where, and what happened.
 *
 * <p>Separate from {@link CaptureSessionUseCase} because the two answer different questions and
 * age differently. Capture is about what the pipeline produced and is append-only; this is about
 * decisions people take on top of it, and every field here is something a person can change.
 */
public interface OperationsUseCase {

    List<TeamWorkload> listTeams();

    Team team(UUID teamId);

    Page<ServiceOrder> listOrders(ServiceOrderQuery query);

    ServiceOrder order(UUID orderId);

    /**
     * Opens an order against measured stretches.
     *
     * <p>The area, the level and the centre are read from those stretches rather than taken from
     * the caller: an order has to be able to say what evidence justified it.
     */
    ServiceOrder openOrder(ServiceOrderDraft draft, String openedBy);

    /**
     * Changes what a person is allowed to change. A null argument leaves that field alone, which
     * is what makes this a PATCH rather than a PUT.
     */
    ServiceOrder amendOrder(
            UUID orderId,
            ServiceOrderStatus status,
            ServiceOrderPriority priority,
            UUID teamId,
            LocalDate scheduledFor,
            String notes,
            String amendedBy);

    void cancelOrder(UUID orderId, String cancelledBy);
}
