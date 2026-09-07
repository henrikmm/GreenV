package br.com.greenv.videoapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.capture")
public record CaptureProperties(
        int segmentSeconds,
        long maxSegmentBytes,
        long maxTelemetryBytes,
        int transientDays) {

    public CaptureProperties {
        if (segmentSeconds <= 0 || maxSegmentBytes <= 0 || maxTelemetryBytes <= 0 || transientDays <= 0) {
            throw new IllegalArgumentException("capture limits must be positive");
        }
    }
}
