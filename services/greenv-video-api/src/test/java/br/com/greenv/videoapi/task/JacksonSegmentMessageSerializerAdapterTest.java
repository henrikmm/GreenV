package br.com.greenv.videoapi.task;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonSegmentMessageSerializerAdapterTest {

    @Test
    void serializesTheVersionedProviderNeutralContract() {
        SegmentExtractionRequest request = new SegmentExtractionRequest(
                2,
                0,
                UUID.fromString("2d995d67-dd6f-4792-af22-480c43b37f2f"),
                0,
                "device:session:0",
                "capture-sessions/session/segments/00000000/source.mp4",
                "a".repeat(64),
                "capture-sessions/session/segments/00000000/telemetry.json",
                "b".repeat(64),
                "capture-sessions/session/segments/00000000",
                Instant.parse("2026-08-25T11:59:50Z"),
                10_000,
                Instant.parse("2026-08-25T12:00:00Z"),
                "BR-101",
                "sul");
        var serializer = new JacksonSegmentMessageSerializerAdapter(
                JsonMapper.builder().findAndAddModules().build());

        String message = serializer.serialize(request);

        assertThat(message)
                .contains("\"schemaVersion\":2")
                .contains("\"videoObjectKey\"")
                .doesNotContain("s3://", "file://", "blob.core.windows.net");
    }
}
