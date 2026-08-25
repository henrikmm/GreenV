package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Retention {
    TRANSIENT,
    SAVED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Retention fromWireValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
