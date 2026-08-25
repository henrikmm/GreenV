package br.com.greenv.frameextractor.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MediaProbeTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void readsAndNormalizesSideDataRotation() throws Exception {
        var stream = objectMapper.readTree("""
                {"side_data_list":[{"rotation":-90}],"tags":{"rotate":"180"}}
                """);

        assertThat(MediaProbe.rotation(stream)).isEqualTo(270);
    }

    @Test
    void fallsBackToRotationTag() throws Exception {
        var stream = objectMapper.readTree("""
                {"tags":{"rotate":"90"}}
                """);

        assertThat(MediaProbe.rotation(stream)).isEqualTo(90);
    }
}

