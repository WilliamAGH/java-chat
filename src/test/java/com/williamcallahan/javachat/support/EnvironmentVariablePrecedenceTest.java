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
 * Verifies environment-variable precedence over .env values for embedding settings.
 */
class EnvironmentVariablePrecedenceTest {

    private static final String ENV_LOADER_SCRIPT_RELATIVE_PATH = "scripts/lib/env_loader.sh";

    @TempDir
    Path temporaryDirectoryPath;

    @Test
    void processEnvironmentOverridesDotEnvThenDefaultsApply() throws IOException, InterruptedException {
        Path environmentFilePath = temporaryDirectoryPath.resolve("embedding-test.env");
        Files.writeString(environmentFilePath, """
                REMOTE_EMBEDDING_SERVER_URL=https://from-dotenv.example/v1
                REMOTE_EMBEDDING_MODEL_NAME=text-embedding-qwen3-embedding-8b
                REMOTE_EMBEDDING_API_KEY=dotenv-remote-api-key
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
                export REMOTE_EMBEDDING_SERVER_URL="https://from-process.example/v1"
                export REMOTE_EMBEDDING_MODEL_NAME=""
                preserve_process_env_then_source_file "$ENV_FILE_PATH"
                printf '%s|%s|%s|%s' \
                  "$REMOTE_EMBEDDING_SERVER_URL" \
                  "$REMOTE_EMBEDDING_MODEL_NAME" \
                  "$REMOTE_EMBEDDING_API_KEY" \
                  "${APP_EMBEDDING_TIMEOUT_SECONDS:-fallback-default}"
                """;

        ProcessBuilder shellProcessBuilder = new ProcessBuilder("/bin/bash", "-lc", shellProgram);
        Map<String, String> shellEnvironmentMap = shellProcessBuilder.environment();
        shellEnvironmentMap.put("SCRIPT_PATH", environmentLoaderScriptPath.toString());
        shellEnvironmentMap.put("ENV_FILE_PATH", environmentFilePath.toString());

        // Remove variables under test from the inherited parent environment so that
        // only values explicitly exported in the shell script or sourced from the
        // .env file participate in the precedence check.
        shellEnvironmentMap.remove("REMOTE_EMBEDDING_SERVER_URL");
        shellEnvironmentMap.remove("REMOTE_EMBEDDING_MODEL_NAME");
        shellEnvironmentMap.remove("REMOTE_EMBEDDING_API_KEY");
        shellEnvironmentMap.remove("APP_EMBEDDING_TIMEOUT_SECONDS");

        Process shellProcess = shellProcessBuilder.start();
        int shellExitCode = shellProcess.waitFor();
        String shellOutput = readStream(shellProcess.getInputStream()).trim();
        String shellErrorOutput = readStream(shellProcess.getErrorStream()).trim();

        assertEquals(0, shellExitCode, shellErrorOutput);
        assertEquals("https://from-process.example/v1||dotenv-remote-api-key|fallback-default", shellOutput);
    }

    private static String readStream(java.io.InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
