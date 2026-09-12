package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Where a service order has got to.
 *
 * <p>Four values and no others, in the Portuguese an operations team actually says. The dashboard
 * keys its badge colour off this exact string, so a fifth spelling renders as an unstyled label
 * and no test anywhere fails.
 */
public enum ServiceOrderStatus {
    PENDENTE,
    EM_ANDAMENTO,
    CONCLUIDA,
    CANCELADA;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ServiceOrderStatus of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ServiceOrderStatus status : values()) {
            if (status.wireValue().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return status;
            }
        }
        throw new IllegalArgumentException(
                "status must be one of pendente, em_andamento, concluida, cancelada");
    }
}
