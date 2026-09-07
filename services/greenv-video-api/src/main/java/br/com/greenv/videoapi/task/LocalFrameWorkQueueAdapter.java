package br.com.greenv.videoapi.task;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.FrameExtractionRequest;
import br.com.greenv.videoapi.port.FrameWorkQueue;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalFrameWorkQueueAdapter implements FrameWorkQueue {

    private final ObjectMapper objectMapper;
    private final Path tasksRoot;

    public LocalFrameWorkQueueAdapter(ObjectMapper objectMapper, PipelineProperties pipelineProperties)
            throws IOException {
        this.objectMapper = objectMapper;
        this.tasksRoot = pipelineProperties.root().toAbsolutePath().normalize().resolve("tasks");
        for (String state : List.of("pending", "processing", "done", "failed")) {
            Files.createDirectories(tasksRoot.resolve(state));
        }
    }

    @Override
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
            throw new ApplicationException(
                    FailureKind.INTERNAL_ERROR,
                    "task_publish_failed",
                    "could not publish frame extraction task: " + exception.getMessage());
        }
    }
}
