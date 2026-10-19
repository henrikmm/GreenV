package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.domain.DemoPhase;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/** One line of a demo capture's timeline: {@code {phase, at, tMs, detail?}}, as contract v1 has it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DemoPhaseResponse(String phase, Instant at, long tMs, Map<String, Object> detail) {

    public static DemoPhaseResponse from(DemoPhase phase) {
        return new DemoPhaseResponse(phase.phase(), phase.at(), phase.tMs(), phase.detail());
    }
}
