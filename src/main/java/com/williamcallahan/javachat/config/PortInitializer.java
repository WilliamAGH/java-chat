package com.williamcallahan.javachat.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

public class PortInitializer implements EnvironmentPostProcessor, Ordered {

    private static final int DEFAULT_MIN = 8085;
    private static final int DEFAULT_MAX = 8090;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        int min = getInt(environment, "app.ports.min", "APP_PORT_MIN", DEFAULT_MIN);
        int max = getInt(environment, "app.ports.max", "APP_PORT_MAX", DEFAULT_MAX);

        String range = get(environment, "app.ports.range", "APP_PORT_RANGE", null);
        if (range != null && range.contains("-")) {
            String[] parts = range.split("-");
            try {
                min = Integer.parseInt(parts[0].trim());
                max = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {
                // keep defaults if parsing fails
            }
        }
        if (min > max) { int t = min; min = max; max = t; }

        boolean killOnConflict = getBool(environment, "app.ports.killOnConflict", "APP_KILL_ON_CONFLICT", true);

        // Preferred from existing server.port/PORT, otherwise start at min
        int preferred = getInt(environment, "server.port", "PORT", min);
        if (preferred < min || preferred > max) {
            preferred = min;
        }

        int chosen = preferred;

        if (isPortInUse(chosen)) {
            if (killOnConflict) {
                killProcessOnPort(chosen);
                waitForPortToBeFree(chosen, 5000);
            }
            if (isPortInUse(chosen)) {
                int firstFree = findFirstFreePortInRange(min, max);
                if (firstFree > 0) {
                    chosen = firstFree;
                } else if (killOnConflict) {
                    // Nothing free; force using preferred by killing again
                    killProcessOnPort(preferred);
                    waitForPortToBeFree(preferred, 5000);
                    chosen = preferred;
                } else {
                    // As a last resort, keep preferred and hope binding succeeds later
                    chosen = preferred;
                }
            }
        }

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", Integer.toString(chosen));
        environment.getPropertySources().addFirst(new MapPropertySource("forcedServerPort", props));

        System.err.println("[startup] Using server.port=" + chosen + " (allowed range " + min + "-" + max + ")");
    }

    private static int findFirstFreePortInRange(int min, int max) {
        for (int p = min; p <= max; p++) {
            if (!isPortInUse(p)) {
                return p;
            }
        }
        return -1;
    }

    private static boolean isPortInUse(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return false; // successfully bound -> not in use
        } catch (IOException e) {
            return true; // exception means port is in use
        }
    }

    private static void waitForPortToBeFree(int port, long timeoutMillis) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (!isPortInUse(port)) return;
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
        }
    }

    private static void killProcessOnPort(int port) {
        String[] cmd = {"/bin/sh", "-lc", "lsof -ti tcp:" + port + " | xargs -r kill -9"};
        // macOS/BSD 'xargs' may not support -r; fallback by checking output ourselves
        String[] listCmd = {"/bin/sh", "-lc", "lsof -ti tcp:" + port};
        try {
            Process list = new ProcessBuilder(listCmd).redirectErrorStream(true).start();
            String pids = readAll(list);
            list.waitFor();
            if (pids != null && !pids.trim().isEmpty()) {
                String killCmd = "kill -9 " + pids.replaceAll("\\s+", " ").trim();
                new ProcessBuilder("/bin/sh", "-lc", killCmd).start().waitFor();
                System.err.println("[startup] Killed process(es) on port " + port + ": " + pids.trim());
            }
        } catch (Exception e) {
            System.err.println("[startup] Warning: failed to kill process on port " + port + ": " + e.getMessage());
        }
    }

    private static String readAll(Process p) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append(' ');
            return sb.toString();
        }
    }

    private static String get(ConfigurableEnvironment env, String keyProp, String keyEnv, String def) {
        String v = env.getProperty(keyProp);
        if (v == null) v = System.getenv(keyEnv);
        return v != null ? v : def;
    }

    private static int getInt(ConfigurableEnvironment env, String keyProp, String keyEnv, int def) {
        String v = get(env, keyProp, keyEnv, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    private static boolean getBool(ConfigurableEnvironment env, String keyProp, String keyEnv, boolean def) {
        String v = get(env, keyProp, keyEnv, null);
        if (v == null) return def;
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("1") || v.equalsIgnoreCase("yes");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

