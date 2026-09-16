package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.SegmentReference;
import br.com.greenv.videoapi.domain.ServiceOrderDraft;
import br.com.greenv.videoapi.domain.ServiceOrderPriority;
import br.com.greenv.videoapi.domain.ServiceOrderQuery;
import br.com.greenv.videoapi.domain.ServiceOrderStatus;
import br.com.greenv.videoapi.port.OperationsUseCase;
import br.com.greenv.videoapi.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Service orders: opening one, listing them, and recording what happened to it. */
@RestController
@RequestMapping("/v2/service-orders")
public class ServiceOrderController {

    private final OperationsUseCase operations;

    public ServiceOrderController(OperationsUseCase operations) {
        this.operations = operations;
    }

    @GetMapping
    PageResponse<ServiceOrderResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var query = new ServiceOrderQuery(
                ServiceOrderStatus.of(status),
                ServiceOrderPriority.of(priority),
                teamId,
                search,
                limit,
                offset);
        return PageResponse.from(operations.listOrders(query), ServiceOrderResponse::from);
    }

    @GetMapping("/{orderId}")
    ServiceOrderResponse get(@PathVariable UUID orderId) {
        return ServiceOrderResponse.from(operations.order(orderId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ServiceOrderResponse open(
            @Valid @RequestBody CreateServiceOrderRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        var draft = new ServiceOrderDraft(
                ServiceOrderPriority.of(request.priority()),
                request.teamId(),
                request.scheduledFor(),
                request.notes(),
                request.targets().stream()
                        .map(target -> new SegmentReference(
                                target.sessionId(), target.segmentIndex(), target.windowIndex()))
                        .toList());
        return ServiceOrderResponse.from(operations.openOrder(draft, nameOf(principal)));
    }

    @PatchMapping("/{orderId}")
    ServiceOrderResponse amend(
            @PathVariable UUID orderId,
            @RequestBody UpdateServiceOrderRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return ServiceOrderResponse.from(operations.amendOrder(
                orderId,
                ServiceOrderStatus.of(request.status()),
                ServiceOrderPriority.of(request.priority()),
                request.teamId(),
                request.scheduledFor(),
                request.notes(),
                nameOf(principal)));
    }

    /**
     * Cancels the order. It is not removed: an order that was opened is a decision someone took,
     * and the record of that decision is most of the value of having orders at all.
     */
    @DeleteMapping("/{orderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable UUID orderId, @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        operations.cancelOrder(orderId, nameOf(principal));
    }

    /** The static capture token identifies no person, and an order may honestly say so. */
    private static String nameOf(AuthenticatedPrincipal principal) {
        return principal == null ? null : principal.displayName();
    }
}
