package cn.diaojiahong.myyoutube.transcode.ffmpeg;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil.HlsTimeoutReason.*;
import static org.junit.jupiter.api.Assertions.*;

class HlsTimeoutTest {
    @TempDir Path work;

    private FFmpegUtil.HlsProgress progress() {
        return new FFmpegUtil.HlsProgress(new FFmpegUtil.HlsTimeouts(
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(2)), 0);
    }

    @Test
    void startupGraceDoesNotUseTheShorterStallDeadline() {
        FFmpegUtil.HlsProgress progress = progress();
        assertNull(progress.timeoutReason(Duration.ofSeconds(4).toNanos()));
        assertEquals(STARTUP_TIMEOUT, progress.timeoutReason(Duration.ofSeconds(5).toNanos()));
    }

    @Test
    void onlyAdvancingOutputTimeResetsStallDeadline() {
        FFmpegUtil.HlsProgress progress = progress();
        progress.accept("out_time_us=1000000", Duration.ofSeconds(4).toNanos());
        assertNull(progress.timeoutReason(Duration.ofSeconds(5).toNanos()));
        progress.accept("out_time_us=2000000", Duration.ofSeconds(5).toNanos());
        assertNull(progress.timeoutReason(Duration.ofSeconds(6).toNanos()));
        assertEquals(PROGRESS_STALLED, progress.timeoutReason(Duration.ofSeconds(7).toNanos()));
    }

    @Test
    void repeatedRegressingInvalidAndHumanOutputDoNotKeepAProcessAlive() {
        FFmpegUtil.HlsProgress progress = progress();
        progress.accept("out_time_us=1000000", Duration.ofSeconds(1).toNanos());
        for (String line : new String[]{"out_time_us=1000000", "out_time_us=999", "out_time_us=N/A",
                "out_time_us=-1", "progress=continue", "network connected", "frame=100"}) {
            progress.accept(line, Duration.ofSeconds(2).toNanos());
        }
        assertEquals(PROGRESS_STALLED, progress.timeoutReason(Duration.ofSeconds(3).toNanos()));
    }

    @Test
    void zeroOrMissingOutputStillUsesStartupDeadline() {
        FFmpegUtil.HlsProgress progress = progress();
        progress.accept("out_time_us=0", Duration.ofSeconds(4).toNanos());
        progress.accept("progress=continue", Duration.ofSeconds(4).toNanos());
        assertEquals(STARTUP_TIMEOUT, progress.timeoutReason(Duration.ofSeconds(5).toNanos()));
    }

    @Test
    void advancingProgressNeverExtendsTotalBudget() {
        FFmpegUtil.HlsProgress progress = progress();
        for (int seconds = 1; seconds <= 30; seconds++) {
            progress.accept("out_time_us=" + seconds * 1000000L, Duration.ofSeconds(seconds).toNanos());
        }
        assertEquals(TOTAL_BUDGET_EXCEEDED, progress.timeoutReason(Duration.ofSeconds(30).toNanos()));
    }

    @Test
    void waitingCannotOvershootAnEarlierDeadline() {
        FFmpegUtil.HlsProgress progress = progress();
        assertEquals(Duration.ofMillis(100).toNanos(), progress.waitNanos(Duration.ofMillis(4900).toNanos()));
        progress.accept("out_time_us=1000000", Duration.ofSeconds(5).toNanos());
        assertEquals(Duration.ofMillis(100).toNanos(), progress.waitNanos(Duration.ofMillis(6900).toNanos()));
    }

    @ParameterizedTest
    @EnumSource(FFmpegUtil.HlsTimeoutReason.class)
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void timeoutTerminatesTheActualChildAndPreservesItsReason(FFmpegUtil.HlsTimeoutReason reason) throws Exception {
        Path pid = work.resolve("child.pid");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path executable = work.resolve("fake-ffmpeg");
        Files.writeString(executable, "#!/bin/sh\nexec " + quote(java) + " -cp " + quote(classpath) + " "
                + quote(FakeFfmpeg.class.getName()) + " " + reason.name() + " " + quote(pid.toString()) + " \"$@\"\n");
        assertTrue(executable.toFile().setExecutable(true));
        FFmpegUtil.Options options = new FFmpegUtil.Options();
        options.ffmpegCommand = executable.toString();
        Duration total = reason == TOTAL_BUDGET_EXCEEDED ? Duration.ofSeconds(3) : Duration.ofSeconds(10);
        FFmpegUtil.HlsTimeouts timeouts = new FFmpegUtil.HlsTimeouts(total, Duration.ofSeconds(2), Duration.ofMillis(500));

        FFmpegUtil.HlsTimeoutException error = assertTimeoutPreemptively(Duration.ofSeconds(15), () ->
                assertThrows(FFmpegUtil.HlsTimeoutException.class, () -> FFmpegUtil.transcodeHls(
                        "unused-source", work.resolve("output"),
                        PlaybackQuality.P480, options, timeouts)));

        assertEquals(reason, error.reason());
        long childPid = Long.parseLong(Files.readString(pid));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static class FakeFfmpeg {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            long time = 1;
            while (true) {
                if (!STARTUP_TIMEOUT.name().equals(args[0])) {
                    System.out.println("out_time_us=" + time);
                    if (TOTAL_BUDGET_EXCEEDED.name().equals(args[0])) time++;
                }
                System.out.println("progress=continue");
                Thread.sleep(25);
            }
        }
    }
}
