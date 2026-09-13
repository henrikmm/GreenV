package br.com.greenv.videoapi.port;

import br.com.greenv.videoapi.domain.Team;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where field teams are kept. */
public interface TeamStore {

    List<Team> findAll();

    Optional<Team> findById(UUID teamId);

    Optional<Team> findBySlug(String slug);

    void save(Team team);
}
