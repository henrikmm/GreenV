package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.domain.Team;
import br.com.greenv.videoapi.domain.TeamStatus;
import br.com.greenv.videoapi.port.TeamStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "greenv.adapters.database", havingValue = "jdbc", matchIfMissing = true)
public class JdbcTeamStoreAdapter implements TeamStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTeamStoreAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Team> findAll() {
        return jdbcTemplate.query("SELECT * FROM teams ORDER BY name", JdbcTeamStoreAdapter::mapTeam);
    }

    @Override
    public Optional<Team> findById(UUID teamId) {
        return jdbcTemplate
                .query("SELECT * FROM teams WHERE team_id = ?", JdbcTeamStoreAdapter::mapTeam, teamId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Team> findBySlug(String slug) {
        return jdbcTemplate
                .query("SELECT * FROM teams WHERE slug = ?", JdbcTeamStoreAdapter::mapTeam, slug)
                .stream()
                .findFirst();
    }

    /**
     * Update first, insert if it hit nothing.
     *
     * <p>Not {@code ON CONFLICT DO UPDATE}: the contract test runs against H2 in PostgreSQL mode,
     * which accepts {@code DO NOTHING} but not the update form, and a store that only works on
     * one engine is a store the tests cannot exercise.
     */
    @Override
    public void save(Team team) {
        int updated = jdbcTemplate.update(
                """
                UPDATE teams
                   SET slug = ?, name = ?, short_name = ?, region = ?, colour = ?, initials = ?,
                       status = ?, updated_at = ?
                 WHERE team_id = ?
                """,
                team.slug(),
                team.name(),
                team.shortName(),
                team.region(),
                team.colour(),
                team.initials(),
                team.status().wireValue(),
                Timestamp.from(team.updatedAt()),
                team.teamId());
        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO teams (team_id, slug, name, short_name, region, colour, initials,
                                       status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    team.teamId(),
                    team.slug(),
                    team.name(),
                    team.shortName(),
                    team.region(),
                    team.colour(),
                    team.initials(),
                    team.status().wireValue(),
                    Timestamp.from(team.createdAt()),
                    Timestamp.from(team.updatedAt()));
        }
    }

    static Team mapTeam(ResultSet result, int row) throws SQLException {
        return new Team(
                result.getObject("team_id", UUID.class),
                result.getString("slug"),
                result.getString("name"),
                result.getString("short_name"),
                result.getString("region"),
                result.getString("colour"),
                result.getString("initials"),
                TeamStatus.of(result.getString("status")),
                instant(result, "created_at"),
                instant(result, "updated_at"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
