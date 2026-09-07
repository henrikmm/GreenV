package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.SegmentExtractionRequest;
import br.com.greenv.frameextractor.port.SegmentMessageCodec;
import br.com.greenv.frameextractor.service.ExtractionException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JacksonSegmentMessageCodecAdapter implements SegmentMessageCodec {

    private final ObjectMapper objectMapper;

    public JacksonSegmentMessageCodecAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String encode(SegmentExtractionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new ExtractionException(
                    "segment_message_serialization_failed",
                    "could not serialize the segment extraction request",
                    false,
                    exception);
        }
    }

    @Override
    public SegmentExtractionRequest decode(String message) {
        try {
            return objectMapper.readValue(message, SegmentExtractionRequest.class);
        } catch (JacksonException exception) {
            throw new ExtractionException(
                    "invalid_segment_message",
                    "queue message is not a segment extraction request",
                    false,
                    exception);
        }
    }
}
