import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;

/**
 * Serializes commands that share Gradle and frontend build output directories.
 *
 * <p>The Java file-lock API supplies one kernel-managed implementation on every operating system
 * supported by the repository. The lock is released automatically if this launcher exits, so a
 * terminated build cannot leave a stale owner file.
 */
public final class BuildStateLock {

    private static final int REQUIRED_ARGUMENT_COUNT = 3;
    private static final int LOCK_PATH_ARGUMENT_INDEX = 0;
    private static final int WAIT_TIMEOUT_ARGUMENT_INDEX = 1;
    private static final int COMMAND_ARGUMENT_START_INDEX = 2;
    private static final long LOCK_RETRY_INTERVAL_MILLISECONDS = 100L;

    private BuildStateLock() {}

    /**
     * Acquires the shared build lock, runs the requested command, and returns its exit code.
     *
     * @param arguments lock path, wait timeout in seconds, and the command with its arguments
     * @throws IOException when the lock or child command cannot be started
     * @throws InterruptedException when lock waiting or the child command is interrupted
     */
    public static void main(String[] arguments) throws IOException, InterruptedException {
        if (arguments.length < REQUIRED_ARGUMENT_COUNT) {
            throw new IllegalArgumentException(
                    "Usage: BuildStateLock <lock-path> <wait-timeout-seconds> <command> [arguments...]");
        }

        Path buildLockPath = Path.of(arguments[LOCK_PATH_ARGUMENT_INDEX]);
        long waitTimeoutSeconds = parseWaitTimeout(arguments[WAIT_TIMEOUT_ARGUMENT_INDEX]);
        String[] buildCommand =
                Arrays.copyOfRange(arguments, COMMAND_ARGUMENT_START_INDEX, arguments.length);

        int commandExitCode;
        try (FileChannel buildLockChannel = FileChannel.open(
                buildLockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = acquireBuildLock(buildLockChannel, waitTimeoutSeconds)) {
            Process buildCommandProcess = new ProcessBuilder(buildCommand).inheritIO().start();
            Runtime buildLockRuntime = Runtime.getRuntime();
            Thread buildCommandShutdownHook = new Thread(
                    () -> {
                        if (buildCommandProcess.isAlive()) {
                            buildCommandProcess.descendants().forEach(ProcessHandle::destroy);
                            buildCommandProcess.destroy();
                        }
                    },
                    "build-command-shutdown");
            buildLockRuntime.addShutdownHook(buildCommandShutdownHook);
            commandExitCode = buildCommandProcess.waitFor();
            if (!buildLockRuntime.removeShutdownHook(buildCommandShutdownHook)) {
                throw new IllegalStateException("Build-command shutdown hook was not registered");
            }
        }
        System.exit(commandExitCode);
    }

    private static long parseWaitTimeout(String timeoutText) {
        long waitTimeoutSeconds = Long.parseLong(timeoutText);
        if (waitTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("Build-lock wait timeout must be positive");
        }
        return waitTimeoutSeconds;
    }

    private static FileLock acquireBuildLock(FileChannel buildLockChannel, long waitTimeoutSeconds)
            throws IOException, InterruptedException {
        long waitDeadlineNanoseconds =
                System.nanoTime() + Duration.ofSeconds(waitTimeoutSeconds).toNanos();

        FileLock buildLock;
        while ((buildLock = buildLockChannel.tryLock()) == null) {
            if (System.nanoTime() >= waitDeadlineNanoseconds) {
                throw new IOException(
                        "Timed out after " + waitTimeoutSeconds + " seconds waiting for the build-state lock");
            }
            Thread.sleep(LOCK_RETRY_INTERVAL_MILLISECONDS);
        }
        return buildLock;
    }
}
