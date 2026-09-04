package br.com.greenv.videoapi.api;

import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApplicationExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    ProblemDetail handleApplicationException(ApplicationException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status(exception.kind()), exception.getMessage());
        detail.setType(URI.create("urn:greenv:error:" + exception.code()));
        detail.setTitle(exception.code());
        return detail;
    }

    private static HttpStatus status(FailureKind kind) {
        return switch (kind) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PAYLOAD_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request validation failed");
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
        detail.setType(URI.create("urn:greenv:error:invalid_request"));
        detail.setTitle("invalid_request");
        return detail;
    }
}
