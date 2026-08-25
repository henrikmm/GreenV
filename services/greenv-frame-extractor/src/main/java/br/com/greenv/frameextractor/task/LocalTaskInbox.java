package br.com.greenv.frameextractor.task;

import br.com.greenv.frameextractor.domain.FrameExtractionRequest;
import br.com.greenv.frameextractor.service.ExtractionException;
import br.com.greenv.frameextractor.storage.LocalPipelineStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalTaskInbox {

    private final ObjectMapper objectMapper;
    private final Path tasksRoot;

    public LocalTaskInbox(ObjectMapper objectMapper, LocalPipelineStore store) {
        this.objectMapper = objectMapper;
        this.tasksRoot = store.tasksRoot();
    }

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
                    processing,
                    objectMapper.readValue(processing.toFile(), FrameExtractionRequest.class)));
        } catch (IOException exception) {
            throw new ExtractionException("task_claim_failed", "could not claim local extraction task", true, exception);
        }
    }

    public synchronized void complete(ClaimedTask task) {
        moveTask(task.path(), tasksRoot.resolve("done").resolve(task.path().getFileName()));
    }

    public synchronized void fail(ClaimedTask task) {
        moveTask(task.path(), tasksRoot.resolve("failed").resolve(task.path().getFileName()));
    }

    public synchronized void retry(ClaimedTask task) {
        try {
            objectMapper.writeValue(task.path().toFile(), task.request().nextAttempt());
            moveTask(task.path(), tasksRoot.resolve("pending").resolve(task.path().getFileName()));
        } catch (JacksonException exception) {
            throw new ExtractionException("task_retry_failed", "could not requeue extraction task", true, exception);
        }
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

    public record ClaimedTask(Path path, FrameExtractionRequest request) {
    }
}
