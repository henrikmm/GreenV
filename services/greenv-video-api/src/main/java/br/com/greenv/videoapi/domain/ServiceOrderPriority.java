package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How soon a service order needs a crew. The dashboard sorts and colours by it. */
public enum ServiceOrderPriority {
    BAIXA,
    MEDIA,
    ALTA,
    URGENTE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ServiceOrderPriority of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ServiceOrderPriority priority : values()) {
            if (priority.wireValue().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return priority;
            }
        }
        throw new IllegalArgumentException("priority must be one of baixa, media, alta, urgente");
    }
}
