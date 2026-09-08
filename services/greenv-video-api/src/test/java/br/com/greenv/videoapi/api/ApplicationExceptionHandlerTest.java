package br.com.greenv.videoapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplicationExceptionHandlerTest {

    @Test
    void mapsEveryTransportNeutralFailureKindToHttpAtTheAdapterBoundary() {
        var expectedStatuses = Map.of(
                FailureKind.INVALID_INPUT, 400,
                FailureKind.NOT_FOUND, 404,
                FailureKind.CONFLICT, 409,
                FailureKind.PAYLOAD_TOO_LARGE, 413,
                FailureKind.DEPENDENCY_UNAVAILABLE, 503,
                FailureKind.INTERNAL_ERROR, 500);
        var handler = new ApplicationExceptionHandler();

        expectedStatuses.forEach((kind, expectedStatus) -> {
            var detail = handler.handleApplicationException(
                    new ApplicationException(kind, "example", "example failure"));

            assertThat(detail.getStatus()).isEqualTo(expectedStatus);
            assertThat(detail.getTitle()).isEqualTo("example");
        });
    }
}
