package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Whether a field team can take work right now. */
public enum TeamStatus {
    EM_CAMPO,
    DISPONIVEL,
    FORA_SERVICO;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TeamStatus of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TeamStatus status : values()) {
            if (status.wireValue().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "status must be one of em_campo, disponivel, fora_servico");
    }
}
