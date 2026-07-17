package com.williamcallahan.javachat.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies environment-variable precedence over .env values for embedding credentials.
 */
class EnvironmentVariablePrecedenceTest {

    private static final String ENV_LOADER_SCRIPT_RELATIVE_PATH = "scripts/lib/env_loader.sh";

    @TempDir
    Path temporaryDirectoryPath;

    @Test
    void processEnvironmentOverridesDotEnvThenDefaultsApply() throws IOException, InterruptedException {
        Path environmentFilePath = temporaryDirectoryPath.resolve("embedding-test.env");
        Files.writeString(environmentFilePath, """
                REMOTE_EMBEDDING_API_KEY=dotenv-remote-api-key
                QDRANT_API_KEY=dotenv-qdrant-api-key
                """, StandardCharsets.UTF_8);

        Path environmentLoaderScriptPath = Path.of("")
                .toAbsolutePath()
                .resolve(ENV_LOADER_SCRIPT_RELATIVE_PATH)
                .normalize();
        assertTrue(
                Files.exists(environmentLoaderScriptPath),
                "Environment loader script is required for precedence enforcement tests");

        String shellProgram = """
                set -euo pipefail
                source "$SCRIPT_PATH"
                export QDRANT_API_KEY="process-qdrant-api-key"
                preserve_process_env_then_source_file "$ENV_FILE_PATH"
                printf '%s|%s|%s' \
                  "$REMOTE_EMBEDDING_API_KEY" \
                  "$QDRANT_API_KEY" \
                  "${APP_EMBEDDING_TIMEOUT_SECONDS:-fallback-default}"
                """;

        ProcessBuilder shellProcessBuilder = new ProcessBuilder("/bin/bash", "-lc", shellProgram);
        Map<String, String> shellEnvironmentMap = shellProcessBuilder.environment();
        shellEnvironmentMap.put("SCRIPT_PATH", environmentLoaderScriptPath.toString());
        shellEnvironmentMap.put("ENV_FILE_PATH", environmentFilePath.toString());

        // Remove variables under test from the inherited parent environment so that
        // only values explicitly exported in the shell script or sourced from the
        // .env file participate in the precedence check.
        shellEnvironmentMap.remove("REMOTE_EMBEDDING_API_KEY");
        shellEnvironmentMap.remove("QDRANT_API_KEY");
        shellEnvironmentMap.remove("APP_EMBEDDING_TIMEOUT_SECONDS");

        Process shellProcess = shellProcessBuilder.start();
        int shellExitCode = shellProcess.waitFor();
        String shellOutput = readStream(shellProcess.getInputStream()).trim();
        String shellErrorOutput = readStream(shellProcess.getErrorStream()).trim();

        assertEquals(0, shellExitCode, shellErrorOutput);
        assertEquals("dotenv-remote-api-key|process-qdrant-api-key|fallback-default", shellOutput);
    }

    private static String readStream(java.io.InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
