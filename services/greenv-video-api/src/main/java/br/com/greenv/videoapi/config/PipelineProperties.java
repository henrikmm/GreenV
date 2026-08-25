package br.com.greenv.videoapi.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("greenv.pipeline")
public record PipelineProperties(
        Path root,
        Path savedRoot,
        long maxFileSizeBytes,
        int transientDays) {

    public PipelineProperties {
        if (root == null || savedRoot == null) {
            throw new IllegalArgumentException("pipeline storage paths are required");
        }
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("max file size must be positive");
        }
        if (transientDays <= 0) {
            throw new IllegalArgumentException("transient retention must be positive");
        }
    }
}
