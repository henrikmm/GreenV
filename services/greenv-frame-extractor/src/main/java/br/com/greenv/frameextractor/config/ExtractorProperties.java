package br.com.greenv.frameextractor.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.extractor")
public record ExtractorProperties(
        Path root,
        String ffmpeg,
        String ffprobe,
        double maxDurationSeconds,
        int maxAttempts,
        long pollDelayMs,
        boolean localPollingEnabled) {

    public ExtractorProperties {
        if (root == null || ffmpeg == null || ffprobe == null) {
            throw new IllegalArgumentException("extractor root and FFmpeg binaries are required");
        }
        if (maxDurationSeconds <= 0 || maxAttempts <= 0 || pollDelayMs <= 0) {
            throw new IllegalArgumentException("extractor limits must be positive");
        }
    }
}
