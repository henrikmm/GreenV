package br.com.greenv.videoapi.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum JobState {
    CREATED,
    UPLOADING,
    QUEUED,
    EXTRACTING,
    FRAMES_READY,
    FAILED;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static JobState fromWireValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
