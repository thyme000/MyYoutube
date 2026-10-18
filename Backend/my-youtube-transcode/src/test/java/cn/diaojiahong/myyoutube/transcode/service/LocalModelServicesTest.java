package cn.diaojiahong.myyoutube.transcode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalModelServicesTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private LocalModelServices linkage(boolean enabled) throws Exception {
        Files.createDirectories(root.resolve("asr-svc"));
        Files.createDirectories(root.resolve("ocr-svc"));
        var linkage = spy(new LocalModelServices(mapper, enabled, root.toString(), "20ms", false, "1s"));
        doNothing().when(linkage).requestShutdown(any());
        return linkage;
    }

    private ProcessHandle server(LocalModelServices linkage, String service, long pid) throws Exception {
        var directory = root.resolve(service).toRealPath();
        Files.createDirectories(directory.resolve(".runtime"));
        mapper.writeValue(directory.resolve(".runtime/server.json").toFile(), Map.of(
                "pid", pid, "service", service, "directory", directory.toString(), "recordedAt", System.currentTimeMillis(),
                "port", 18089, "controlPort", 12345, "controlToken", "a".repeat(64)));
        var handle = mock(ProcessHandle.class);
        var info = mock(ProcessHandle.Info.class);
        when(handle.pid()).thenReturn(pid);
        when(handle.isAlive()).thenReturn(true);
        when(handle.info()).thenReturn(info);
        when(info.startInstant()).thenReturn(Optional.of(Instant.now().minusSeconds(5)));
        when(info.arguments()).thenReturn(Optional.of(new String[]{"-m", "model_runtime.server", "--service", service.substring(0, 3)}));
        when(handle.destroy()).thenReturn(true);
        when(handle.onExit()).thenReturn(CompletableFuture.completedFuture(handle));
        doReturn(Optional.of(handle)).when(linkage).process(pid);
        return handle;
    }

    private void awaitStartup(LocalModelServices linkage) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (linkage.startupStates().containsValue("STARTING") && System.nanoTime()<deadline) Thread.sleep(10);
        assertFalse(linkage.startupStates().containsValue("STARTING"));
    }

    @Test void springContextShutdownSignalsBothServicesOnce() throws Exception {
        var linkage = linkage(true);
        var asr = server(linkage, "asr-svc", 101);
        var ocr = server(linkage, "ocr-svc", 102);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(LocalModelServices.class, () -> linkage);
            context.refresh();
            linkage.arm();
        }
        linkage.close();
        verify(linkage).requestShutdown(argThat(value -> value.handle() == asr));
        verify(linkage).requestShutdown(argThat(value -> value.handle() == ocr));
    }

    @Test void disabledLinkageAndFailedApplicationStartupDoNotStopModels() throws Exception {
        var disabled = linkage(false);
        var asr = server(disabled, "asr-svc", 101);
        disabled.arm();
        disabled.close();
        verify(asr, never()).destroy();
        var unready = linkage(true);
        var ocr = server(unready, "ocr-svc", 102);
        unready.close();
        verify(ocr, never()).destroy();
    }

    @Test void reusedPidAndDifferentCommandAreIgnored() throws Exception {
        var linkage = linkage(true);
        var asr = server(linkage, "asr-svc", 101);
        var ocr = server(linkage, "ocr-svc", 102);
        when(asr.info().startInstant()).thenReturn(Optional.of(Instant.now().plusSeconds(1)));
        when(ocr.info().arguments()).thenReturn(Optional.of(new String[]{"-m", "other.server", "--service", "ocr"}));
        linkage.arm();
        linkage.close();
        verify(linkage, never()).requestShutdown(any());
    }

    @Test void foreignDirectoryIsIgnoredAndMissingProcessIsHarmless() throws Exception {
        var linkage = linkage(true);
        var asr = server(linkage, "asr-svc", 101);
        var ocr = server(linkage, "ocr-svc", 102);
        mapper.writeValue(root.resolve("asr-svc/.runtime/server.json").toFile(), Map.of(
                "pid", 101, "service", "asr-svc", "directory", "/another/project", "recordedAt", System.currentTimeMillis()));
        doReturn(Optional.empty()).when(linkage).process(102);
        linkage.arm();
        linkage.close();
        verify(linkage, never()).requestShutdown(any());
    }

    @Test void brokenRecordDoesNotPreventOtherServiceShutdown() throws Exception {
        var linkage = linkage(true);
        Files.createDirectories(root.resolve("asr-svc/.runtime"));
        Files.writeString(root.resolve("asr-svc/.runtime/server.json"), "broken");
        var ocr = server(linkage, "ocr-svc", 102);
        linkage.arm();
        linkage.close();
        verify(linkage).requestShutdown(argThat(value -> value.handle() == ocr));
    }

    @Test void signalsBothBeforeWaitingAndNeverForceKillOnTimeout() throws Exception {
        var linkage = linkage(true);
        var asr = server(linkage, "asr-svc", 101);
        var ocr = server(linkage, "ocr-svc", 102);
        when(asr.onExit()).thenReturn(new CompletableFuture<>());
        linkage.arm();
        assertTimeout(Duration.ofSeconds(1), linkage::close);
        verify(linkage).requestShutdown(argThat(value -> value.handle() == asr));
        verify(linkage).requestShutdown(argThat(value -> value.handle() == ocr));
        verify(asr, never()).destroyForcibly();
        verify(ocr, never()).destroyForcibly();
    }

    @Test void discoversProjectFromWorkingDirectoryAndSkipsMissingRecords() throws Exception {
        linkage(true);
        String previous = System.getProperty("user.dir");
        var nested = Files.createDirectories(root.resolve("Backend/module"));
        try {
            System.setProperty("user.dir", nested.toString());
            var linkage = new LocalModelServices(mapper, true, "", "20ms", false, "1s");
            linkage.arm();
            assertDoesNotThrow(linkage::close);
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    @Test void launchFailureDoesNotStopHealthySiblingOrTranscode() throws Exception {
        linkage(true);
        for (String service : new String[]{"asr-svc", "ocr-svc"}) {
            Files.createDirectories(root.resolve(service + "/.venv/bin"));
            Files.createDirectories(root.resolve(service + "/.venv/Scripts"));
            Files.writeString(root.resolve(service + "/.venv/bin/python"), "fixture");
            Files.writeString(root.resolve(service + "/.venv/Scripts/python.exe"), "fixture");
        }
        var linkage = spy(new LocalModelServices(mapper, true, root.toString(), "20ms", true, "1s"));
        var process = mock(Process.class);
        var handle = mock(ProcessHandle.class);
        when(process.pid()).thenReturn(101L);
        when(process.isAlive()).thenReturn(true);
        var input = new java.io.ByteArrayOutputStream();
        when(process.getOutputStream()).thenReturn(input);
        when(process.toHandle()).thenReturn(handle);
        when(handle.pid()).thenReturn(101L);
        when(handle.isAlive()).thenReturn(true);
        when(handle.destroy()).thenReturn(true);
        when(handle.onExit()).thenReturn(CompletableFuture.completedFuture(handle));
        doReturn(process).doThrow(new IOException("second launch failed")).when(linkage).launch(any());
        assertTimeout(Duration.ofMillis(200), linkage::start);
        awaitStartup(linkage);
        assertTrue(linkage.startupStates().containsValue("FAILED"));
        assertEquals("", input.toString(java.nio.charset.StandardCharsets.US_ASCII));
        var builders = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(linkage, times(2)).launch(builders.capture());
        for (var builder : builders.getAllValues()) {
            assertFalse(builder.environment().containsKey("SERVER_PORT"));
            assertFalse(builder.environment().containsKey("MODEL_LOG_DIRECTORY"));
            assertEquals(java.util.List.of("-m", "model_runtime.server"), builder.command().subList(1, 3));
            assertTrue(builder.command().contains("--managed"));
        }
        linkage.close();
        assertEquals("shutdown\n", input.toString(java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test void invalidStartupBudgetFailsBeforeLaunching() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalModelServices(mapper, true, root.toString(), "30s", true, "0s"));
    }

    @Test void failedStartupPreservesReusedService() throws Exception {
        linkage(true);
        var linkage = spy(new LocalModelServices(mapper, true, root.toString(), "20ms", true, "1s"));
        var asr = server(linkage, "asr-svc", 101);
        Files.createDirectories(root.resolve("ocr-svc/.venv/bin"));
        Files.createDirectories(root.resolve("ocr-svc/.venv/Scripts"));
        Files.writeString(root.resolve("ocr-svc/.venv/bin/python"), "fixture");
        Files.writeString(root.resolve("ocr-svc/.venv/Scripts/python.exe"), "fixture");
        doThrow(new IOException("launch failed")).when(linkage).launch(any());
        assertDoesNotThrow(linkage::start);
        awaitStartup(linkage);
        verify(asr, never()).destroy();
        linkage.close();
    }

    @Test void missingOperatingSystemArgumentsStillUsesAuthenticatedShutdown() throws Exception {
        var linkage = linkage(true);
        var asr = server(linkage, "asr-svc", 101);
        when(asr.info().arguments()).thenReturn(Optional.empty());
        when(asr.info().startInstant()).thenReturn(Optional.empty());
        linkage.arm();
        linkage.close();
        verify(linkage).requestShutdown(argThat(value -> value.handle() == asr));
        verify(asr, never()).destroy();
    }

    @Test void windowsInterpreterPathIsSelectedWithoutShell() throws Exception {
        linkage(true);
        for (String service : new String[]{"asr-svc", "ocr-svc"}) {
            Files.createDirectories(root.resolve(service + "/.venv/Scripts"));
            Files.writeString(root.resolve(service + "/.venv/Scripts/python.exe"), "fixture");
        }
        var linkage = spy(new LocalModelServices(mapper, true, root.toString(), "20ms", true, "1s"));
        doThrow(new IOException("fixture stop")).when(linkage).launch(any());
        String previous = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertDoesNotThrow(linkage::start);
            awaitStartup(linkage);
            var command = ArgumentCaptor.forClass(ProcessBuilder.class);
            verify(linkage,times(2)).launch(command.capture());
            for (var builder:command.getAllValues()) {
                assertTrue(builder.command().get(0).endsWith("python.exe"));
                assertFalse(builder.command().contains("/bin/sh"));
            }
            linkage.close();
        } finally {
            System.setProperty("os.name", previous);
        }
    }
}
