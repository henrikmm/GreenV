package br.com.greenv.videoapi.service;

public class ApplicationException extends RuntimeException {

    private final FailureKind kind;
    private final String code;

    public ApplicationException(FailureKind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public FailureKind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
