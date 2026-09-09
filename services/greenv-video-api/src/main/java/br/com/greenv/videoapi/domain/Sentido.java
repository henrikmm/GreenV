package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The direction of travel a capture was driven in.
 *
 * <p>Four values and no others. Free text here would put {@code norte}, {@code Norte}, {@code N}
 * and {@code sentido norte} in the same column, and the dashboard joins two passes of the same
 * road by {@code (rodovia, sentido, km)} — a second spelling silently becomes a second road.
 *
 * <p>Cardinal rather than the surveyor's {@code crescente}/{@code decrescente}, because cardinal is
 * the vocabulary already in this repository: the capture screen writes "BR-101 · sentido norte",
 * {@code docs/AUTOMATIC-HEIGHT.md} passes {@code "sentido":"norte"}, and the measurement worker's
 * fixtures use the same. A second vocabulary for one thing is what the language rule forbids.
 */
public enum Sentido {
    NORTE,
    SUL,
    LESTE,
    OESTE;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The value for {@code null}, so an absent sentido stays absent rather than becoming a road. */
    public static Sentido of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (Sentido sentido : values()) {
            if (sentido.wireValue().equals(value.trim().toLowerCase(Locale.ROOT))) {
                return sentido;
            }
        }
        throw new IllegalArgumentException("sentido must be one of norte, sul, leste, oeste");
    }
}
