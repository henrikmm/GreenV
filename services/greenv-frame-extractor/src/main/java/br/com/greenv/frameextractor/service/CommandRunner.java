package br.com.greenv.frameextractor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class CommandRunner {

    public CommandResult run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new ExtractionException(
                    "binary_unavailable",
                    "could not start " + command.getFirst() + ": " + exception.getMessage(),
                    false,
                    exception);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = executor.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderr = executor.submit(() ->
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ExtractionException(
                        "process_timeout",
                        command.getFirst() + " exceeded " + timeout.toSeconds() + " seconds",
                        true);
            }
            return new CommandResult(process.exitValue(), output(stdout), output(stderr));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ExtractionException("process_interrupted", "native process was interrupted", true, exception);
        }
    }

    private static String output(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("output_interrupted", "could not read process output", true, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new ExtractionException("output_failed", "could not read process output", true, exception);
        }
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
