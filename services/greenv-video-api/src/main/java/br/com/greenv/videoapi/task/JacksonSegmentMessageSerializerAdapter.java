package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.domain.SegmentExtractionRequest;
import br.com.greenv.videoapi.port.SegmentMessageSerializer;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class JacksonSegmentMessageSerializerAdapter implements SegmentMessageSerializer {

    private final ObjectMapper objectMapper;

    public JacksonSegmentMessageSerializerAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(SegmentExtractionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "segment_message_serialization_failed",
                    "could not serialize segment extraction request");
        }
    }
}
