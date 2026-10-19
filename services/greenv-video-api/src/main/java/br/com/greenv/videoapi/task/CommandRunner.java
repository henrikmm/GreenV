package br.com.greenv.videoapi.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a native process to completion, reading both of its streams while it runs.
 *
 * <p>Copied from {@code services/greenv-frame-extractor} ({@code service/CommandRunner}) rather
 * than shared: the two services are built, versioned and deployed separately, and a library
 * between them would couple their releases for forty lines. The only change is the exception it
 * throws, since the extractor's own exception type does not exist here.
 */
final class CommandRunner {

    CommandResult run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "could not start " + command.getFirst() + ": " + exception.getMessage(), exception);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = executor.submit(() ->
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderr = executor.submit(() ->
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        command.getFirst() + " exceeded " + timeout.toSeconds() + " seconds");
            }
            return new CommandResult(process.exitValue(), output(stdout), output(stderr));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("native process was interrupted", exception);
        }
    }

    private static String output(Future<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("could not read process output", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("could not read process output", exception);
        }
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
