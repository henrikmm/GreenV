package br.com.greenv.videoapi.storage;

import br.com.greenv.videoapi.config.PipelineProperties;
import br.com.greenv.videoapi.domain.JobDocument;
import br.com.greenv.videoapi.domain.JobState;
import br.com.greenv.videoapi.domain.Retention;
import br.com.greenv.videoapi.port.LegacyJobStore;
import br.com.greenv.videoapi.service.ApplicationException;
import br.com.greenv.videoapi.service.FailureKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalLegacyJobStoreAdapter implements LegacyJobStore {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final PipelineProperties pipelineProperties;
    private final Path root;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    public LocalLegacyJobStoreAdapter(ObjectMapper objectMapper, PipelineProperties pipelineProperties)
            throws IOException {
        this.objectMapper = objectMapper;
        this.pipelineProperties = pipelineProperties;
        this.root = pipelineProperties.root().toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("jobs"));
        Files.createDirectories(root.resolve("tasks/pending"));
    }

    public JobDocument create(JobDocument job) {
        synchronized (lock(job.jobId())) {
            Path status = statusPath(job.jobId());
            if (Files.exists(status)) {
                throw new ApplicationException(FailureKind.CONFLICT, "job_exists", "job already exists");
            }
            write(status, job);
            return job;
        }
    }

    public JobDocument get(UUID jobId) {
        synchronized (lock(jobId)) {
            Path status = statusPath(jobId);
            if (!Files.isRegularFile(status)) {
                throw new ApplicationException(FailureKind.NOT_FOUND, "job_not_found", "job does not exist");
            }
            try {
                return objectMapper.readValue(status.toFile(), JobDocument.class);
            } catch (JacksonException exception) {
                throw storageFailure("could not read job status", exception);
            }
        }
    }

    public JobDocument update(UUID jobId, UnaryOperator<JobDocument> updater) {
        synchronized (lock(jobId)) {
            JobDocument updated = updater.apply(get(jobId));
            write(statusPath(jobId), updated);
            return updated;
        }
    }

    public JobDocument storeSource(UUID jobId, InputStream input, Instant now) {
        synchronized (lock(jobId)) {
            JobDocument job = get(jobId);
            if (job.state() != JobState.CREATED && job.state() != JobState.UPLOADING) {
                throw invalidState(job, "accept a source upload");
            }
            if (job.sourceGeneration() != null) {
                throw new ApplicationException(
                        FailureKind.CONFLICT, "source_exists", "source upload is already complete");
            }

            write(statusPath(jobId), job.withState(JobState.UPLOADING, now));
            Path source = sourcePath(jobId);
            Path temporary = source.resolveSibling("source.uploading");
            try {
                Files.createDirectories(source.getParent());
                Files.deleteIfExists(temporary);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long bytes = copyBounded(input, temporary, digest);
                if (bytes != job.declaredSizeBytes()) {
                    throw new ApplicationException(
                            FailureKind.INVALID_INPUT,
                            "size_mismatch",
                            "uploaded bytes do not match the declared file size");
                }
                moveAtomically(temporary, source, true);
                String generation = HexFormat.of().formatHex(digest.digest());
                JobDocument uploaded = job.withUpload(bytes, source.toUri().toString(), generation, now);
                write(statusPath(jobId), uploaded);
                return uploaded;
            } catch (ApplicationException exception) {
                deleteQuietly(temporary);
                write(statusPath(jobId), job.withError(exception.code(), exception.getMessage(), now));
                throw exception;
            } catch (IOException | NoSuchAlgorithmException exception) {
                deleteQuietly(temporary);
                throw storageFailure("could not store source video", exception);
            }
        }
    }

    public Path jobDirectory(UUID jobId) {
        return insideRoot(root.resolve("jobs").resolve(jobId.toString()));
    }

    public Path statusPath(UUID jobId) {
        return jobDirectory(jobId).resolve("status.json");
    }

    public Path sourcePath(UUID jobId) {
        return jobDirectory(jobId).resolve("source/video");
    }

    public Path manifestPath(UUID jobId) {
        return jobDirectory(jobId).resolve("manifest.json");
    }

    @Override
    public String statusReference(UUID jobId) {
        return statusPath(jobId).toUri().toString();
    }

    @Override
    public String outputPrefixReference(UUID jobId) {
        return jobDirectory(jobId).toUri().toString();
    }

    @Override
    public boolean manifestExists(UUID jobId) {
        return Files.isRegularFile(manifestPath(jobId));
    }

    @Override
    public byte[] readManifest(UUID jobId) {
        try {
            return Files.readAllBytes(manifestPath(jobId));
        } catch (IOException exception) {
            throw storageFailure("could not read frame manifest", exception);
        }
    }

    public Path pendingTasksDirectory() {
        return insideRoot(root.resolve("tasks/pending"));
    }

    public List<JobDocument> listJobs() {
        Path jobs = root.resolve("jobs");
        try (var directories = Files.list(jobs)) {
            return directories
                    .filter(Files::isDirectory)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .map(value -> {
                        try {
                            return get(UUID.fromString(value));
                        } catch (RuntimeException ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException exception) {
            throw storageFailure("could not list jobs", exception);
        }
    }

    public void deleteJob(UUID jobId) {
        synchronized (lock(jobId)) {
            deleteTree(jobDirectory(jobId));
            deleteTree(pipelineProperties.savedRoot().toAbsolutePath().normalize().resolve(jobId.toString()));
            deleteTaskFiles(jobId);
            locks.remove(jobId);
        }
    }

    public void saveJob(UUID jobId, Instant now) {
        synchronized (lock(jobId)) {
            JobDocument job = get(jobId);
            if (job.state() != JobState.FRAMES_READY || !Files.isRegularFile(manifestPath(jobId))) {
                throw invalidState(job, "save frames");
            }
            Path savedRoot = pipelineProperties.savedRoot().toAbsolutePath().normalize();
            Path destination = savedRoot.resolve(jobId.toString()).normalize();
            if (!destination.startsWith(savedRoot)) {
                throw new ApplicationException(
                        FailureKind.INVALID_INPUT, "invalid_job_path", "invalid save destination");
            }
            JobDocument saved = job.withRetention(Retention.SAVED, now);
            if (!Files.exists(destination)) {
                Path temporary = savedRoot.resolve("." + jobId + ".saving");
                deleteTree(temporary);
                copyTree(jobDirectory(jobId), temporary);
                write(temporary.resolve("status.json"), saved);
                try {
                    moveAtomically(temporary, destination, false);
                } catch (IOException exception) {
                    deleteTree(temporary);
                    throw storageFailure("could not save job", exception);
                }
            }
            write(destination.resolve("status.json"), saved);
            write(statusPath(jobId), saved);
        }
    }

    private long copyBounded(InputStream input, Path destination, MessageDigest digest) throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (DigestOutputStream output = new DigestOutputStream(
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW), digest)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > pipelineProperties.maxFileSizeBytes()) {
                    throw new ApplicationException(
                            FailureKind.PAYLOAD_TOO_LARGE,
                            "video_too_large",
                            "video exceeds the configured 1 GB limit");
                }
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private Object lock(UUID jobId) {
        return locks.computeIfAbsent(jobId, ignored -> new Object());
    }

    private void deleteTaskFiles(UUID jobId) {
        for (String state : List.of("pending", "processing", "done", "failed")) {
            Path directory = root.resolve("tasks").resolve(state);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(file -> file.getFileName().toString().startsWith(jobId.toString())).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException exception) {
                throw storageFailure("could not delete job tasks", exception);
            }
        }
    }

    private Path insideRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new ApplicationException(
                    FailureKind.INVALID_INPUT, "invalid_job_path", "path escapes pipeline storage");
        }
        return normalized;
    }

    private void write(Path destination, Object value) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
            objectMapper.writeValue(temporary.toFile(), value);
            moveAtomically(temporary, destination, true);
        } catch (IOException exception) {
            throw storageFailure("could not write pipeline metadata", exception);
        }
    }

    private static void moveAtomically(Path source, Path destination, boolean replace) throws IOException {
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

    private static void copyTree(Path source, Path destination) {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw storageFailure("could not copy job artifacts", exception);
        }
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException exception) {
            throw storageFailure("could not delete job artifacts", exception);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A later transient-data sweep handles abandoned upload files.
        }
    }

    private static ApplicationException invalidState(JobDocument job, String action) {
        return new ApplicationException(
                FailureKind.CONFLICT,
                "invalid_job_state",
                "cannot " + action + " while job is " + job.state().wireValue());
    }

    private static ApplicationException storageFailure(String message, Exception exception) {
        return new ApplicationException(
                FailureKind.INTERNAL_ERROR, "storage_failure", message + ": " + exception.getMessage());
    }
}
