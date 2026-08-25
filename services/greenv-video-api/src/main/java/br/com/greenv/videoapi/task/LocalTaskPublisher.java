package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.api.ApiException;
import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.FrameExtractionRequest;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalTaskPublisher {

    private final ObjectMapper objectMapper;
    private final Path tasksRoot;

    public LocalTaskPublisher(ObjectMapper objectMapper, PipelineProperties properties) throws IOException {
        this.objectMapper = objectMapper;
        this.tasksRoot = properties.root().toAbsolutePath().normalize().resolve("tasks");
        for (String state : List.of("pending", "processing", "done", "failed")) {
            Files.createDirectories(tasksRoot.resolve(state));
        }
    }

    public synchronized void publish(FrameExtractionRequest request) {
        String fileName = request.jobId() + "-" + request.sourceGeneration() + ".json";
        for (String state : List.of("pending", "processing", "done", "failed")) {
            if (Files.exists(tasksRoot.resolve(state).resolve(fileName))) {
                return;
            }
        }

        Path destination = tasksRoot.resolve("pending").resolve(fileName);
        try {
            Path temporary = Files.createTempFile(tasksRoot.resolve("pending"), fileName, ".tmp");
            objectMapper.writeValue(temporary.toFile(), request);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "task_publish_failed",
                    "could not publish frame extraction task: " + exception.getMessage());
        }
    }
}
