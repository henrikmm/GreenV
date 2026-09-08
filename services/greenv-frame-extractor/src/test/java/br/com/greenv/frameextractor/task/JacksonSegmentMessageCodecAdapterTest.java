package br.com.greenv.frameextractor.task;

import static br.com.greenv.frameextractor.task.CloudSegmentWorkQueueAdaptersTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.greenv.frameextractor.service.ExtractionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JacksonSegmentMessageCodecAdapterTest {

    private final JacksonSegmentMessageCodecAdapter codec = new JacksonSegmentMessageCodecAdapter(
            JsonMapper.builder().findAndAddModules().build());

    @Test
    void roundTripsTheVersionedSegmentContract() {
        var request = request();

        assertThat(codec.decode(codec.encode(request))).isEqualTo(request);
    }

    @Test
    void classifiesMalformedMessagesAsTerminal() {
        assertThatThrownBy(() -> codec.decode("not-json"))
                .isInstanceOf(ExtractionException.class)
                .satisfies(exception -> assertThat(((ExtractionException) exception).retryable()).isFalse());
    }
}
