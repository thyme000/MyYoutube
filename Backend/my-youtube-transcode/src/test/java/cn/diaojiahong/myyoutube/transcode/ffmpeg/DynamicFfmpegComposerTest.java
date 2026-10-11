package cn.diaojiahong.myyoutube.transcode.ffmpeg;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeItemDTO;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "ffmpeg.tests", matches = "true")
class DynamicFfmpegComposerTest {
    @TempDir Path work;
    private final String ffmpeg = System.getProperty("ffmpeg.command", "ffmpeg");
    private final String ffprobe = System.getProperty("ffprobe.command", "ffprobe");

    @Test void shortSoundtrackLoopsThroughFadeExport() throws Exception { check(1, true); }
    @Test void longSoundtrackStopsWithCutExport() throws Exception { check(8, false); }
    @Test void noSoundtrackExportsSilentFade() throws Exception { check(0, true); }
    @Test void missingSoundtrackFailsInsteadOfBecomingSilent() throws Exception {
        var params = params(true);
        assertThrows(java.io.IOException.class, () -> DynamicFfmpegComposer.compose(params,
                images(), work.resolve("missing.wav"), work, work.resolve("out.mp4"), ffmpeg));
    }

    private void check(int audioSeconds, boolean fade) throws Exception {
        var params = params(fade);
        Path audio = null;
        if (audioSeconds > 0) {
            audio = work.resolve("audio.wav");
            run(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                    "-t", String.valueOf(audioSeconds), audio.toString());
        }
        var output = work.resolve("out.mp4");
        DynamicFfmpegComposer.compose(params, images(), audio, work, output, ffmpeg);
        double expected = params.getTotalDurationMs() / 1000.0;
        String duration = new String(run(ffprobe, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=nw=1:nk=1", output.toString())).trim();
        assertEquals(expected, Double.parseDouble(duration), .08);
        for (double at : List.of(.3, 3.5, expected - .3)) {
            byte[] pcm = run(ffmpeg, "-v", "error", "-ss", String.valueOf(at), "-i", output.toString(),
                    "-t", "0.1", "-vn", "-ac", "1", "-f", "f32le", "pipe:1");
            var samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            int count = samples.remaining() / 4;
            assertTrue(count > 1000, "audio must cover export tail");
            double sum = 0;
            while (samples.hasRemaining()) { double value = samples.getFloat(); sum += value * value; }
            double rms = Math.sqrt(sum / count);
            if (audioSeconds == 0) assertTrue(rms < .0001, "silent export");
            else assertTrue(rms > .05, "soundtrack must keep playing, including after original end");
        }
        int[] first = pixel(output, 1);
        int[] last = pixel(output, expected - .2);
        assertTrue(first[0] > 200 && first[2] < 30, "first image red");
        assertTrue(last[2] > 200 && last[0] < 30, "last image blue, no last-first transition");
        if (fade) {
            int[] overlap = pixel(output, 2.75);
            assertTrue(overlap[0] > 60 && overlap[2] > 60, "adjacent fade blends both images");
        }
    }
    private int[] pixel(Path output, double second) throws Exception {
        byte[] rgb = run(ffmpeg, "-v", "error", "-ss", String.valueOf(second), "-i", output.toString(),
                "-frames:v", "1", "-vf", "scale=1:1", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1");
        assertEquals(3, rgb.length);
        return new int[]{rgb[0] & 255, rgb[1] & 255, rgb[2] & 255};
    }
    private List<Path> images() throws Exception {
        var paths = new ArrayList<Path>();
        for (String color : List.of("255 0 0", "0 0 255")) {
            var path = work.resolve("image-" + paths.size() + ".ppm");
            Files.writeString(path, "P3\n16 16\n255\n" + (color + "\n").repeat(256));
            paths.add(path);
        }
        return paths;
    }
    private TranscodeSubmitDTO params(boolean fade) {
        var params = new TranscodeSubmitDTO();
        params.setCanvasW(160); params.setCanvasH(90);
        params.setTransition(fade ? "fade" : "cut"); params.setFadeMs(fade ? 500 : 0);
        params.setTotalDurationMs(fade ? 5500L : 6000L);
        params.setItems(List.of(item(), item()));
        return params;
    }
    private TranscodeItemDTO item() {
        var item = new TranscodeItemDTO(); item.setDurationMs(3000L); return item;
    }
    private byte[] run(String... args) throws Exception {
        var log = Files.createTempFile(work, "process-", ".log");
        var p = new ProcessBuilder(args).redirectError(log.toFile()).start();
        byte[] output = p.getInputStream().readAllBytes();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertEquals(0, p.waitFor(), Files.readString(log)));
        return output;
    }
}
