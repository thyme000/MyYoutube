package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.preparation.*;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedVideoFramesTest {
    @TempDir Path work;
    @Test
    @EnabledIfSystemProperty(named = "preparation.video-source", matches = ".+")
    void providedOriginalVideoProducesAllPlannedFrames() throws Exception {
        Path source = Path.of(System.getProperty("preparation.video-source"));
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        byte[] head;
        try (InputStream input = Files.newInputStream(source)) {
            head = input.readNBytes(65536);
        }
        PreparedVideoReader.Probe probe = reader.probe(source.toString(), head);
        var timestamps = new ArrayList<Long>();
        reader.frames(source.toString(), probe, work, frame -> {
            assertEquals(timestamps.size(), frame.index());
            assertTrue(Files.size(frame.path()) > 0);
            assertTrue(frame.timeMs() >= 0 && frame.timeMs() <= probe.durationMs());
            timestamps.add(frame.timeMs());
        });
        assertEquals(probe.sampleCount(), timestamps.size());
        assertEquals(timestamps.stream().sorted().toList(), timestamps);
    }

    @Test
    void localFfmpegProducesBudgetedFramesWithActualTimestampsAndCleansFiles() throws Exception {
        checkFrames("30", "10", false);
    }

    @Test
    void oneFrameVideoProducesOnlyOneSample() throws Exception {
        checkFrames("0.04", "25", true);
    }

    @Test
    void longVideoDistributesTenSamplesAcrossTheFullTwentyMinutes() throws Exception {
        checkFrames("1200", "1/10", false);
    }

    @ParameterizedTest
    @CsvSource({"0.067,30", "0.167,30", "0.334,30", "0.084,60", "0.201,30000/1001"})
    void shortVideoWithRoundedDurationProducesOneSample(String duration, String fps) throws Exception {
        checkFrames(duration, fps, false);
    }

    @Test
    void shortVideoCanResumeOnlyTheMissingLastFrame() throws Exception {
        checkFrames("30", "10", false, false, index -> index < 5);
    }

    @Test
    void duplicateMajorBrandMetadataStillProducesPlannedFrames() throws Exception {
        checkFrames("0.2", "25", false, true);
    }

    @Test
    void audioLongerThanVideoDoesNotInflateSamplingDuration() throws Exception {
        checkAudioTail(false);
    }

    @Test
    void audioTailCanResumeOnlyTheMissingLastFrame() throws Exception {
        checkAudioTail(true);
    }

    void checkAudioTail(boolean resume) throws Exception {
        Path original = work.resolve("video.mp4");
        FFmpegUtil.CommandOutput generated = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-t", "1", "-c:v", "libx264",
                "-threads", "1", "-pix_fmt", "yuv420p", original.toString()), Duration.ofSeconds(30));
        assertEquals(0, generated.exitCode(), generated.output());
        Path source = work.resolve("audio-tail.mp4");
        FFmpegUtil.CommandOutput muxed = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-i", original.toString(), "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1.1",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                source.toString()), Duration.ofSeconds(30));
        assertEquals(0, muxed.exitCode(), muxed.output());
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        byte[] head;
        try (InputStream input = Files.newInputStream(source)) {
            head = input.readNBytes(65536);
        }
        PreparedVideoReader.Probe probe = reader.probe(source.toString(), head);
        assertTrue(probe.hasAudio());
        assertEquals(1100, probe.durationMs());
        var times = new ArrayList<Long>();
        assertEquals(1000, probe.videoDurationMs());
        assertEquals(1, probe.sampleCount());
        reader.frames(
                source.toString(),
                probe,
                work.resolve("tail-frames"),
                index -> resume && index < probe.sampleCount() - 1,
                frame -> {
                    assertEquals(times.size(), frame.index());
                    assertTrue(Files.size(frame.path()) > 0);
                    assertTrue(frame.timeMs() >= 0 && frame.timeMs() <= 967);
                    times.add(frame.timeMs());
                });
        assertEquals(1, times.size());
        assertTrue(times.get(0) >= 467 && times.get(0) <= 500);
        assertEquals(times.stream().sorted().toList(), times);
        try (Stream<Path> files = Files.list(work.resolve("tail-frames"))) {
            assertEquals(0, files.count());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void variableFrameRateUsesActualLastFrameEvenWithoutAverageRate(boolean unknownRate) throws Exception {
        Path source = work.resolve("variable.mp4");
        FFmpegUtil.CommandOutput generated = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=1/3:duration=90",
                "-vf", "settb=1/30,setpts=if(lt(N\\,15)\\,N\\,15+(N-15)*30)",
                "-fps_mode", "vfr", "-enc_time_base", "1:30", "-bf", "0", "-c:v", "libx264",
                "-threads", "1", "-pix_fmt", "yuv420p", source.toString()), Duration.ofSeconds(30));
        assertEquals(0, generated.exitCode(), generated.output());
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        byte[] head;
        try (InputStream input = Files.newInputStream(source)) {
            head = input.readNBytes(65536);
        }
        PreparedVideoReader.Probe probe = reader.probe(source.toString(), head);
        assertEquals(17500, probe.durationMs());
        if (unknownRate) probe = new PreparedVideoReader.Probe(probe.durationMs(), probe.width(), probe.height(),
                probe.rotation(), probe.hasAudio(), probe.profile(), probe.level(), 0);
        var times = new ArrayList<Long>();
        reader.frames(source.toString(), probe, work.resolve("variable-frames"), frame -> {
            assertEquals(times.size(), frame.index());
            assertTrue(Files.size(frame.path()) > 0);
            assertTrue(frame.timeMs() >= 0 && frame.timeMs() <= 14500);
            times.add(frame.timeMs());
        });
        assertEquals(4, times.size());
        assertEquals(14500L, times.get(3));
        assertEquals(times.stream().sorted().toList(), times);
        try (Stream<Path> files = Files.list(work.resolve("variable-frames"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void unreadableVideoFailsWithDiagnosticsAndCleansTemporaryFiles() throws Exception {
        Path source = work.resolve("invalid.mp4");
        Files.writeString(source, "invalid video");
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> reader.frames(source.toString(),
                new PreparedVideoReader.Probe(1000, 320, 180, 0, false, "High", 42, 30), work.resolve("failed-frames"),
                frame -> fail("Unreadable video must not produce a successful frame")));
        assertTrue(error.getMessage().contains("index=0"));
        assertTrue(error.getMessage().contains("seek="));
        assertTrue(error.getMessage().contains("exitCode="));
        assertFalse(error.getMessage().contains("exitCode=0"));
        try (Stream<Path> files = Files.list(work.resolve("failed-frames"))) {
            assertEquals(0, files.count());
        }
    }

    void checkFrames(String duration, String fps, boolean repeated) throws Exception {
        checkFrames(duration, fps, repeated, false);
    }

    void checkFrames(String duration, String fps, boolean repeated, boolean duplicateBrand) throws Exception {
        checkFrames(duration, fps, repeated, duplicateBrand, index -> false);
    }

    void checkFrames(String duration, String fps, boolean repeated, boolean duplicateBrand,
                     java.util.function.IntPredicate skip) throws Exception {
        Path video = work.resolve("source.mp4");
        var command = new ArrayList<>(List.of("ffmpeg","-nostdin","-v","error","-y","-f","lavfi",
                "-i",
                "testsrc2=size=320x180:rate="+fps,
                "-t",
                duration,
                "-c:v",
                "libx264",
                "-threads",
                "1",
                "-pix_fmt",
                "yuv420p"));
        if (duplicateBrand) command.addAll(List.of("-movflags", "use_metadata_tags", "-metadata", "major_brand=isom"));
        command.add(video.toString());
        FFmpegUtil.CommandOutput generated = FFmpegUtil.runBoundedCommand(command,Duration.ofSeconds(30));
        assertEquals(0,generated.exitCode(),generated.output());
        if (duplicateBrand) {
            FFmpegUtil.CommandOutput metadata = FFmpegUtil.runBoundedCommand(List.of("ffprobe", "-v", "error", "-show_entries",
                    "format_tags=major_brand", "-of", "json", video.toString()), Duration.ofSeconds(30));
            assertEquals(0, metadata.exitCode(), metadata.output());
            assertTrue(metadata.output().contains("isom;isom"), metadata.output());
        }
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        byte[] head;
        try(InputStream input=Files.newInputStream(video)) {
            head=input.readNBytes(65536);
        }
        PreparedVideoReader.Probe probe=reader.probe(video.toString(),head);
        assertFalse(probe.hasAudio());
        var frames=new ArrayList<PreparedVideoReader.Frame>();
        reader.frames(video.toString(),probe,work.resolve("frames"),skip,frame -> {
            assertTrue(Files.size(frame.path()) > 0);
            assertTrue(frame.timeMs() >= 0 && frame.timeMs() <= probe.durationMs());
            assertTrue(frame.image().width() <= 1600);
            assertTrue((long)frame.image().width()*frame.image().height() <= 1_600_000);
            frames.add(frame);
        });
        List<Integer> expectedIndices = java.util.stream.IntStream.range(0, probe.sampleCount())
                .filter(index -> !skip.test(index))
                .boxed()
                .toList();
        assertEquals(expectedIndices, frames.stream().map(PreparedVideoReader.Frame::index).toList());
        if (Double.parseDouble(duration) <= 5) assertEquals(1, frames.size());
        for(int i=0;i<frames.size();i++) {
            assertFalse(Files.exists(frames.get(i).path()));
            if(i>0) assertTrue(frames.get(i).timeMs() >= frames.get(i-1).timeMs());
        }
        if (probe.videoDurationMs() >= 320000) {
            assertEquals(10, frames.size());
            assertEquals(0, frames.get(0).timeMs());
            assertTrue(frames.get(9).timeMs() >= probe.videoDurationMs() * 0.95);
            assertEquals(10, frames.stream().map(PreparedVideoReader.Frame::timeMs).distinct().count());
            for (int i = 1; i < frames.size(); i++) {
                assertTrue(frames.get(i).timeMs() - frames.get(i - 1).timeMs() <= 150000);
            }
        }
        if(repeated) assertEquals(1,frames.stream().map(PreparedVideoReader.Frame::timeMs).distinct().count());
    }

    @Test
    void alreadyCompletedFramesAreSkippedBeforeOpeningVideoInput() throws Exception {
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(
                properties,
                new PreparedImageWriter(properties),
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        reader.frames("missing-source", new PreparedVideoReader.Probe(1000, 320, 180, 0, false, "High", 42, 30),
                work.resolve("skipped"), index -> true, frame -> fail("Completed frame must not be extracted"));
        try (Stream<Path> files = Files.list(work.resolve("skipped"))) {
            assertEquals(0, files.count());
        }
    }
}
