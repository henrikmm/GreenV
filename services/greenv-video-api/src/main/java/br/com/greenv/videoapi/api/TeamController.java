package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.port.OperationsUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The field teams, each with the work it is carrying.
 *
 * <p>Read-only. Teams are created by whoever administers the concession, not by the dashboard,
 * and inventing a create route before anyone asked for one is a route nobody maintains.
 */
@RestController
@RequestMapping("/v2/teams")
public class TeamController {

    private final OperationsUseCase operations;

    public TeamController(OperationsUseCase operations) {
        this.operations = operations;
    }

    @GetMapping
    List<TeamResponse> list() {
        return operations.listTeams().stream().map(TeamResponse::from).toList();
    }
}
