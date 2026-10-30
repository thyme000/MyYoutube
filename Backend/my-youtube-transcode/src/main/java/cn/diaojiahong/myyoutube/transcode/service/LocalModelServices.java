package cn.diaojiahong.myyoutube.transcode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Iterator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class LocalModelServices {
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean startOnBoot;
    private final Path root;
    private final Duration wait;
    private final Duration startupWait;

    private final Map<String, Process> launched = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, String> states = new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ExecutorService starters = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "local-model-start");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean armed;
    private volatile boolean closing;
    private boolean started;

    @Value("${my-youtube.local-model-services.asr-python:}") private String asrPython = "";

    @Value("${my-youtube.local-model-services.ocr-python:}") private String ocrPython = "";

    public LocalModelServices(ObjectMapper mapper,
            @Value("${my-youtube.local-model-services.stop-on-shutdown:true}") boolean enabled,
            @Value("${my-youtube.local-model-services.project-root:}") String projectRoot,
            @Value("${my-youtube.local-model-services.shutdown-wait:30s}") String shutdownWait,
            @Value("${my-youtube.local-model-services.start-on-boot:true}") boolean startOnBoot,
            @Value("${my-youtube.local-model-services.startup-wait:180s}") String startupWait) throws IOException {
        Duration wait = DurationStyle.detectAndParse(shutdownWait);
        if (wait.isNegative() || wait.isZero()) throw new IllegalArgumentException("shutdown-wait must be positive");
        this.mapper = mapper;
        this.enabled = enabled;
        this.wait = wait;
        this.startOnBoot = startOnBoot;
        this.startupWait = DurationStyle.detectAndParse(startupWait);
        if (this.startupWait.isNegative() || this.startupWait.isZero()) throw new IllegalArgumentException("startup-wait must be positive");
        Path candidate = null;
        try {
            candidate = projectRoot.isBlank() ? findRoot(Path.of(System.getProperty("user.dir"))) : Path.of(projectRoot)
                    .toAbsolutePath()
                    .normalize();
        } catch (IOException | RuntimeException error) {
            log.warn("Local model project unavailable reason={}", error.toString());
        }
        this.root = candidate;
    }

    @PostConstruct
    public synchronized void start() {
        if (started || closing || !startOnBoot || root == null) return;
        started = true;
        for (String service : List.of("asr-svc", "ocr-svc")) {
            states.put(service, "STARTING");
            starters.submit(() -> startService(service));
        }
        log.info("Local model startup scheduled; transcode accepts tasks independently");
    }

    private void startService(String service) {
        try {
            if (registeredProcess(service).isPresent()) {
                log.info("Reusing local model service={}", service);
            } else {
                Path directory = root.resolve(service).toRealPath();
                Files.createDirectories(directory.resolve(".runtime"));
                String kind = service.substring(0, service.length() - 4);
                ProcessBuilder builder = new ProcessBuilder(
                        pythonExecutable(directory, kind),
                        "-m",
                        "model_runtime.server",
                        "--service",
                        kind,
                        "--directory",
                        directory.toString(),
                        "--managed").directory(directory.toFile())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(
                                directory.resolve(".runtime/transcode-launch.log").toFile()));
                builder.environment().remove("SERVER_PORT");
                builder.environment().remove("MODEL_LOG_DIRECTORY");
                builder.environment()
                        .compute(
                                "PYTHONPATH",
                                (k, inheritedPath) -> root + (inheritedPath == null ? "" : File.pathSeparator + inheritedPath));
                synchronized (this) {
                    if (closing) return;
                    Process process = launch(builder);
                    launched.put(service, process);
                    log.info("Started local model service={} pid={}", service, process.pid());
                }
            }
            awaitReady(service);
            states.put(service, "READY");
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            states.put(service, closing ? "STOPPING" : "FAILED");
            log.warn("Local model startup ended service={} state={} reason={}; transcode continues",
                    service, states.get(service), error.toString());
        }
    }

    public Map<String, String> startupStates() {
        return Map.copyOf(states);
    }

    private String pythonExecutable(Path directory, String kind) throws IOException {
        String configured = kind.equals("asr") ? asrPython : ocrPython;
        Path executable;
        if (!configured.isBlank()) {
            executable = Path.of(configured);
            if (!executable.isAbsolute()) executable = root.resolve(executable);
        } else {
            boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
            executable = directory.resolve(windows ? ".venv/Scripts/python.exe" : ".venv/bin/python");
        }
        if (!Files.isRegularFile(executable)) throw new IOException("Missing Python interpreter for " + kind + ": " + executable);
        return executable.toAbsolutePath().toString();
    }

    private void awaitReady(String serviceName) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + startupWait.toNanos();
        var pending = new ArrayList<>(List.of(serviceName));
        while (!pending.isEmpty()) {
            if (closing
                    || Thread.currentThread()
                    .isInterrupted()) throw new InterruptedException("Model startup cancelled");
            for (Iterator<String> iterator = pending.iterator(); iterator.hasNext();) {
                String service = iterator.next();
                Process owned = launched.get(service);
                if (owned != null
                        && !owned.isAlive()) throw new IOException(service + " exited with code " + owned.exitValue());
                Optional<LocalModelServices.ServerProcess> instance = registeredProcess(service);
                if (instance.isEmpty()) continue;
                LocalModelServices.ServerProcess process = instance.get();
                if (owned != null
                        && process.handle().pid() != owned.pid()) throw new IOException("Process record changed for " + service);
                if (process.port() <= 0
                        || process.port() > 65535) throw new IOException("Missing or invalid port in record for " + service);
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + process.port() + "/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonNode health = mapper.readTree(response.body());
                    if (response.statusCode() == 200 && health.path("ready").asBoolean(false)) {
                        if (health.path("pid").asLong(-1) != process.handle().pid())
                            throw new IllegalStateException("Health endpoint belongs to another process for " + service);
                        log.info(
                                "Local model ready service={} pid={} port={}",
                                service,
                                process.handle().pid(),
                                process.port());
                        iterator.remove();
                    }
                } catch (IOException ignored) {
                    // The listener starts only after the model and discovery lifespan have initialized.
                }
            }
            if (pending.isEmpty()) return;
            if (System.nanoTime() >= deadline) throw new IOException("Local model readiness timed out: " + pending);
            Thread.sleep(200);
        }
    }

    private static Path findRoot(Path start) throws IOException {
        for (Path path = start.toRealPath(); path != null; path = path.getParent()) {
            if (Files.isDirectory(path.resolve("asr-svc")) && Files.isDirectory(path.resolve("ocr-svc"))) return path;
        }
        return null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void arm() {
        if (closing) return;
        armed = true;
        log.info("Local ASR/OCR linkage startOnBoot={} stopOnShutdown={} projectRoot={}", startOnBoot, enabled, root);
    }

    @PreDestroy
    public synchronized void close() {
        if (closing) return;
        closing = true;
        starters.shutdownNow();
        if (root == null || (armed && !enabled)) return;
        boolean stopRegistered = armed;
        armed = false;
        var exits = new ArrayList<CompletableFuture<ProcessHandle>>();
        var ownedPids = new java.util.HashSet<Long>();
        for (Process process : launched.values()) {
            ownedPids.add(process.pid());
            if (!process.isAlive()) continue;
            try {
                process.getOutputStream().write("shutdown\n".getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
                exits.add(process.toHandle().onExit());
                log.info("Requested local model shutdown pid={}", process.pid());
            } catch (IOException error) {
                log.warn("Could not request owned model shutdown pid={}", process.pid(), error);
            }
        }
        launched.clear();
        if (stopRegistered) for (String service : List.of("asr-svc", "ocr-svc")) {
            try {
                Optional<LocalModelServices.ServerProcess> process = registeredProcess(service);
                if (process.isPresent() && !ownedPids.contains(process.get().handle().pid())) {
                    requestShutdown(process.get());
                    exits.add(process.get().handle().onExit());
                }
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Could not read local model process service={}", service, error);
            }
        }
        try {
            CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
                    .get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for local model services to stop");
        } catch (TimeoutException error) {
            log.warn("Local model shutdown exceeded {}; signalled services continue graceful shutdown", wait);
        } catch (Exception error) {
            log.warn("Local model shutdown wait ended; signalled services continue graceful shutdown", error);
        }
    }

    void requestShutdown(ServerProcess process) throws IOException, InterruptedException {
        if (process.controlPort() <= 0 || process.controlPort() > 65535 || process.controlToken() == null
                || !process.controlToken()
                .matches("[0-9a-f]{64}")) throw new IOException("Invalid local model control record");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + process.controlPort() + "/shutdown"))
                .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + process.controlToken())
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<Void> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                .send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 202) throw new IOException("Local model shutdown rejected: " + response.statusCode());
        log.info("Requested reused local model shutdown pid={}", process.handle().pid());
    }

    private Optional<ServerProcess> registeredProcess(String service) throws IOException {
        Path directory = root.resolve(service).toRealPath();
        Path file = directory.resolve(".runtime/server.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        if (Files.size(file) > 4096) throw new IOException("Invalid local model process record: " + file);
        LocalModelServices.ServerRecord record = mapper.readValue(file.toFile(), ServerRecord.class);
        if (!service.equals(record.service()) || record.directory() == null
                || !directory.equals(Path.of(record.directory()).toRealPath())) return Optional.empty();
        if (record.controlPort() <= 0 || record.controlPort() > 65535 || record.controlToken() == null
                || !record.controlToken().matches("[0-9a-f]{64}")) {
            log.warn("Local model record lacks cooperative control; restart service={}", service);
            return Optional.empty();
        }
        Optional<ProcessHandle> handle = process(record.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) return Optional.empty();
        ProcessHandle.Info info = handle.get().info();
        Optional<Instant> started = info.startInstant();
        List<String> arguments = Arrays.asList(info.arguments().orElse(new String[0]));
        // A reused PID must never turn a stale record into permission to stop another process.
        Instant recorded = Instant.ofEpochMilli(record.recordedAt());
        if ((started.isPresent() && started.get().isAfter(recorded)) || recorded.isAfter(Instant.now())
                || (!arguments.isEmpty() && (!option(arguments, "-m", "model_runtime.server")
                || !option(arguments, "--service", service.substring(0, service.length() - 4))))) {
            log.warn(
                    "Ignoring stale or mismatched local model process record service={} pid={}",
                    service,
                    record.pid());
            return Optional.empty();
        }
        return Optional.of(new ServerProcess(handle.get(), record.port(), record.controlPort(), record.controlToken()));
    }

    private static boolean option(List<String> arguments, String name, String value) {
        int index = arguments.indexOf(name);
        return Collections.frequency(arguments, name) == 1 && index + 1 < arguments.size()
                && index >= 0 && value.equals(arguments.get(index + 1));
    }

    Optional<ProcessHandle> process(long pid) {
        return ProcessHandle.of(pid);
    }

    Process launch(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    private record ServerRecord(long pid, String service, String directory, long recordedAt, int port,
                                int controlPort, String controlToken) {
    }

    record ServerProcess(ProcessHandle handle, int port, int controlPort, String controlToken) {
    }
}
