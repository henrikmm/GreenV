package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.config.ConditionalOnLocalPipeline;
import br.com.greenv.frameextractor.config.ExtractorProperties;
import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.port.LegacyTaskInbox;
import br.com.greenv.frameextractor.service.ExtractionException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnLocalPipeline
@ConditionalOnProperty(
        name = "greenv.extractor.local-polling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LocalLegacyTaskInboxAdapter implements LegacyTaskInbox {

    private final ObjectMapper objectMapper;
    private final Path tasksRoot;

    public LocalLegacyTaskInboxAdapter(ObjectMapper objectMapper, ExtractorProperties extractorProperties)
            throws IOException {
        this.objectMapper = objectMapper;
        this.tasksRoot = extractorProperties.root().toAbsolutePath().normalize().resolve("tasks");
        for (String state : java.util.List.of("pending", "processing", "done", "failed")) {
            Files.createDirectories(tasksRoot.resolve(state));
        }
    }

    @Override
    public synchronized Optional<ClaimedTask> claim() {
        Path pending = tasksRoot.resolve("pending");
        try (var files = Files.list(pending)) {
            Optional<Path> candidate = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .min(Comparator.comparing(path -> path.getFileName().toString()));
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            Path processing = tasksRoot.resolve("processing").resolve(candidate.get().getFileName());
            move(candidate.get(), processing, false);
            return Optional.of(new ClaimedTask(
                    processing.toString(),
                    objectMapper.readValue(processing.toFile(), FrameExtractionRequest.class)));
        } catch (IOException exception) {
            throw new ExtractionException("task_claim_failed", "could not claim local extraction task", true, exception);
        }
    }

    @Override
    public synchronized void complete(ClaimedTask task) {
        Path taskPath = taskPath(task);
        moveTask(taskPath, tasksRoot.resolve("done").resolve(taskPath.getFileName()));
    }

    @Override
    public synchronized void fail(ClaimedTask task) {
        Path taskPath = taskPath(task);
        moveTask(taskPath, tasksRoot.resolve("failed").resolve(taskPath.getFileName()));
    }

    @Override
    public synchronized void retry(ClaimedTask task) {
        Path taskPath = taskPath(task);
        try {
            objectMapper.writeValue(taskPath.toFile(), task.request().nextAttempt());
            moveTask(taskPath, tasksRoot.resolve("pending").resolve(taskPath.getFileName()));
        } catch (JacksonException exception) {
            throw new ExtractionException("task_retry_failed", "could not requeue extraction task", true, exception);
        }
    }

    private Path taskPath(ClaimedTask task) {
        Path taskPath = Path.of(task.receipt()).toAbsolutePath().normalize();
        if (!taskPath.startsWith(tasksRoot)) {
            throw new ExtractionException("invalid_task_receipt", "task receipt escapes the local inbox", false);
        }
        return taskPath;
    }

    private static void moveTask(Path source, Path destination) {
        try {
            move(source, destination, true);
        } catch (IOException exception) {
            throw new ExtractionException("task_move_failed", "could not update task state", true, exception);
        }
    }

    private static void move(Path source, Path destination, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination);
            }
        }
    }
}
