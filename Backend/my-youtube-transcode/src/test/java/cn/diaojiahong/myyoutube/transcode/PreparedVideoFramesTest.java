package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.preparation.*;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedVideoFramesTest {
    @TempDir Path work;
    @Test
    @EnabledIfSystemProperty(named = "preparation.video-source", matches = ".+")
    void providedOriginalVideoProducesAllEightFrames() throws Exception {
        var source = Path.of(System.getProperty("preparation.video-source"));
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(properties, new PreparedImageWriter(properties));
        byte[] head;
        try (var input = Files.newInputStream(source)) { head = input.readNBytes(65536); }
        var probe = reader.probe(source.toString(), head);
        var timestamps = new ArrayList<Long>();
        reader.frames(source.toString(), probe, work, frame -> {
            assertEquals(timestamps.size(), frame.index());
            assertTrue(Files.size(frame.path()) > 0);
            assertTrue(frame.timeMs() >= 0 && frame.timeMs() <= probe.durationMs());
            timestamps.add(frame.timeMs());
        });
        assertEquals(8, timestamps.size());
        assertEquals(timestamps.stream().sorted().toList(), timestamps);
    }
    @Test void localFfmpegProducesEightBudgetedFramesWithActualTimestampsAndCleansFiles() throws Exception {
        checkFrames("2", "30", false);
    }
    @Test void oneFrameVideoKeepsAllEightIndicesWithRepeatedActualTime() throws Exception {
        checkFrames("0.04", "25", true);
    }
    @Test void duplicateMajorBrandMetadataStillProducesEightSharedFrames() throws Exception {
        checkFrames("0.2", "25", false, true);
    }
    void checkFrames(String duration, String fps, boolean repeated) throws Exception {
        checkFrames(duration, fps, repeated, false);
    }
    void checkFrames(String duration, String fps, boolean repeated, boolean duplicateBrand) throws Exception {
        var video = work.resolve("source.mp4");
        var command = new ArrayList<>(List.of("ffmpeg","-nostdin","-v","error","-y","-f","lavfi",
                "-i","testsrc2=size=320x180:rate="+fps,"-t",duration,"-c:v","libx264","-threads","1","-pix_fmt","yuv420p"));
        if (duplicateBrand) command.addAll(List.of("-movflags", "use_metadata_tags", "-metadata", "major_brand=isom"));
        command.add(video.toString());
        var generated = FFmpegUtil.runBoundedCommand(command,Duration.ofSeconds(30));
        assertEquals(0,generated.exitCode(),generated.output());
        if (duplicateBrand) {
            var metadata = FFmpegUtil.runBoundedCommand(List.of("ffprobe", "-v", "error", "-show_entries",
                    "format_tags=major_brand", "-of", "json", video.toString()), Duration.ofSeconds(30));
            assertEquals(0, metadata.exitCode(), metadata.output());
            assertTrue(metadata.output().contains("isom;isom"), metadata.output());
        }
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(properties,new PreparedImageWriter(properties));
        byte[] head;
        try(var input=Files.newInputStream(video)) { head=input.readNBytes(65536); }
        var probe=reader.probe(video.toString(),head);
        assertFalse(probe.hasAudio());
        var frames=new ArrayList<PreparedVideoReader.Frame>();
        reader.frames(video.toString(),probe,work.resolve("frames"),frame -> {
            assertTrue(Files.size(frame.path()) > 0);
            assertTrue(frame.image().width() <= 1600);
            assertTrue((long)frame.image().width()*frame.image().height() <= 1_600_000);
            frames.add(frame);
        });
        assertEquals(8,frames.size());
        for(int i=0;i<8;i++) {
            assertEquals(i,frames.get(i).index()); assertFalse(Files.exists(frames.get(i).path()));
            if(i>0) assertTrue(frames.get(i).timeMs() >= frames.get(i-1).timeMs());
        }
        if(repeated) assertEquals(1,frames.stream().map(PreparedVideoReader.Frame::timeMs).distinct().count());
    }
    @Test void alreadyCompletedFramesAreSkippedBeforeOpeningVideoInput() throws Exception {
        var properties = new MediaPreparationProperties();
        var reader = new PreparedVideoReader(properties, new PreparedImageWriter(properties));
        reader.frames("missing-source", new PreparedVideoReader.Probe(1000, 320, 180, 0, false, "High", 42, 30),
                work.resolve("skipped"), index -> true, frame -> fail("Completed frame must not be extracted"));
        try (var files = Files.list(work.resolve("skipped"))) { assertEquals(0, files.count()); }
    }
}
