package cn.diaojiahong.myyoutube.transcode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.DirectoryStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MODEL_LIFECYCLE_PYTHON", matches = ".+")
class LocalModelServicesLiveTest {
    @TempDir Path root;

    private final ObjectMapper mapper = new ObjectMapper();

    private void prepareServices() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (repository != null
                && !Files.isDirectory(repository.resolve("model_runtime"))) repository = repository.getParent();
        assertNotNull(repository);
        Path runtime = Files.createDirectories(root.resolve("model_runtime"));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(repository.resolve("model_runtime"), "*.py")) {
            for (Path file : files) Files.copy(file, runtime.resolve(file.getFileName()));
        }
        for (String service : new String[]{"asr-svc", "ocr-svc"}) {
            Path directory = Files.createDirectories(root.resolve(service));
            int port;
            try (var socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            String models;
            if (service.equals("asr-svc")) {
                Files.writeString(directory.resolve("speech.bin"), "test model");
                Files.writeString(directory.resolve("vad.bin"), "test model");
                models = "ASR_MODEL=speech.bin\nASR_VAD_MODEL=vad.bin\n";
            } else {
                for (String name : new String[]{"det", "rec"}) {
                    Path model = Files.createDirectories(directory.resolve(name));
                    Files.writeString(model.resolve("inference.yml"), "Global:\n  model_name: fixture\n");
                    Files.writeString(model.resolve("inference.json"), "{}");
                    Files.writeString(model.resolve("inference.pdiparams"), "test weights");
                }
                models = "OCR_DETECTION_MODEL_DIR=det\nOCR_RECOGNITION_MODEL_DIR=rec\nOCR_USE_TEXTLINE_ORIENTATION=false\n";
            }
            Files.writeString(directory.resolve(".env"), models + "NACOS_ENABLED=false\nSERVER_PORT=" + port + "\n");
            Files.writeString(directory.resolve("app.py"), """
                    from contextlib import asynccontextmanager
                    from multiprocessing import get_context
                    from pathlib import Path
                    import os
                    from fastapi import FastAPI
                    from model_runtime.process import ProcessWorker

                    def idle(connection, config):
                        connection.send({'ready': True})
                        while connection.recv() is not None:
                            connection.send({'ok': True})

                    @asynccontextmanager
                    async def lifespan(app):
                        worker = ProcessWorker(get_context('spawn'), {'timeout': 1}, idle)
                        Path('child.pid').write_text(str(worker.process.pid))
                        try:
                            yield
                        finally:
                            worker.stop()
                            Path('stopped').touch()

                    app = FastAPI(lifespan=lifespan)
                    @app.get('/health')
                    async def health():
                        return {'ready': True, 'pid': os.getpid()}
                    """);
        }
    }

    private AnnotationConfigApplicationContext startContext(boolean stop, String budget) {
        var context = new AnnotationConfigApplicationContext();
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("live-test", Map.of(
                    "my-youtube.local-model-services.project-root", root.toString(),
                    "my-youtube.local-model-services.stop-on-shutdown", Boolean.toString(stop),
                    "my-youtube.local-model-services.shutdown-wait", "10s",
                    "my-youtube.local-model-services.startup-wait", budget,
                    "my-youtube.local-model-services.asr-python", System.getenv("MODEL_LIFECYCLE_PYTHON"),
                    "my-youtube.local-model-services.ocr-python", System.getenv("MODEL_LIFECYCLE_PYTHON"))));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(LocalModelServices.class);
            context.refresh();
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(LocalModelServices.class),
                    new String[0], context, Duration.ZERO));
            return context;
        } catch (RuntimeException error) {
            context.close();
            throw error;
        }
    }

    private void awaitStartup(AnnotationConfigApplicationContext context) throws Exception {
        LocalModelServices linkage=context.getBean(LocalModelServices.class);
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while (linkage.startupStates().containsValue("STARTING") && System.nanoTime()<deadline) Thread.sleep(20);
        assertFalse(linkage.startupStates().containsValue("STARTING"));
    }

    private long pid(String service) throws Exception {
        return mapper.readTree(root.resolve(service + "/.runtime/server.json").toFile()).path("pid").asLong();
    }

    private void assertStopped() throws Exception {
        for (String service : new String[]{"asr-svc", "ocr-svc"}) {
            assertTrue(Files.exists(root.resolve(service + "/stopped")), service);
            assertFalse(Files.exists(root.resolve(service + "/.runtime/server.json")), service);
            long child = Long.parseLong(Files.readString(root.resolve(service + "/child.pid")));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void launchesBothReusesAndStopsOwnedServersAndChildren() throws Exception {
        prepareServices();
        var parents = new ArrayList<ProcessHandle>();
        try (AnnotationConfigApplicationContext owner = startContext(true, "15s")) {
            awaitStartup(owner);
            assertTrue(owner.getBean(LocalModelServices.class)
                    .startupStates()
                    .values()
                    .stream()
                    .allMatch("READY"::equals));
            for (String service : new String[]{"asr-svc", "ocr-svc"}) {
                parents.add(ProcessHandle.of(pid(service)).orElseThrow());
                JsonNode record = mapper.readTree(root.resolve(service + "/.runtime/server.json").toFile());
                assertTrue(Files.readString(root.resolve(service + "/.env"))
                        .contains("SERVER_PORT=" + record.path("port").asInt() + "\n"));
                assertTrue(Files.exists(root.resolve(service + "/logs/" + service + ".log")));
            }
            try (AnnotationConfigApplicationContext reused = startContext(false, "15s")) {
                awaitStartup(reused);
                assertEquals(parents.get(0).pid(), pid("asr-svc"));
                assertEquals(parents.get(1).pid(), pid("ocr-svc"));
            }
            for (ProcessHandle process : parents) assertTrue(process.isAlive());
        }
        for (ProcessHandle process : parents) assertFalse(process.isAlive());
        assertStopped();
    }

    @Test
    void reusedServersAreStoppedThroughAuthenticatedControl() throws Exception {
        prepareServices();
        try (AnnotationConfigApplicationContext launcher = startContext(false, "15s")) {
            awaitStartup(launcher);
            try (AnnotationConfigApplicationContext reused = startContext(true, "15s")) {
                awaitStartup(reused);
                assertTrue(ProcessHandle.of(pid("asr-svc")).orElseThrow().isAlive());
            }
        }
        assertStopped();
    }

    @Test
    void wrongHealthIdentityKeepsContextAndHealthySiblingRunning() throws Exception {
        prepareServices();
        Path app = root.resolve("asr-svc/app.py");
        Files.writeString(app, Files.readString(app).replace("'pid': os.getpid()", "'pid': os.getpid() + 1"));
        try (AnnotationConfigApplicationContext context=startContext(true,"15s")) {
            awaitStartup(context);
            assertTrue(context.isActive());
            assertEquals("FAILED",context.getBean(LocalModelServices.class).startupStates().get("asr-svc"));
            assertEquals("READY",context.getBean(LocalModelServices.class).startupStates().get("ocr-svc"));
        }
        assertStopped();
    }

    @Test
    void readinessTimeoutDoesNotFailTranscodeContext() throws Exception {
        prepareServices();
        for (String service : new String[]{"asr-svc", "ocr-svc"}) {
            Path app = root.resolve(service + "/app.py");
            Files.writeString(app, Files.readString(app).replace("return {'ready': True", "return {'ready': False"));
        }
        try (AnnotationConfigApplicationContext context=startContext(true,"5s")) {
            awaitStartup(context);
            assertTrue(context.isActive());
            assertTrue(context.getBean(LocalModelServices.class)
                    .startupStates()
                    .values()
                    .stream()
                    .allMatch("FAILED"::equals));
        }
        assertStopped();
    }
}
