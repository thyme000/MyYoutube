package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockStatic;

class PreparedVideoWriterTest {
    @TempDir Path work;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(MediaPreparationProperties.class, PreparedVideoWriter.class);

    @Test
    void missingConfigurationUsesAutomaticThreadsAndConservativeBudget() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            int expectedThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            verifyEncoding(context.getBean(PreparedVideoWriter.class), expectedThreads, Duration.ofMinutes(32));
        });
    }

    @Test
    void automaticThreadsUseTheConfiguredDraftConcurrency() {
        runner.withPropertyValues("my-youtube.media-pipeline.draft-concurrency=2").run(context -> {
            assertNull(context.getStartupFailure());
            int expectedThreads = Math.max(1, (Runtime.getRuntime().availableProcessors() - 1) / 2);
            verifyEncoding(context.getBean(PreparedVideoWriter.class), expectedThreads, Duration.ofMinutes(32));
        });
    }

    @Test
    void explicitSettingsReachTheEncoderAndTimeoutMonitor() {
        runner.withPropertyValues("my-youtube.media-preparation.playback.threads=4",
                "my-youtube.media-preparation.playback.duration-multiplier=2",
                "my-youtube.media-pipeline.draft-concurrency=2").run(context -> {
            assertNull(context.getStartupFailure());
            verifyEncoding(context.getBean(PreparedVideoWriter.class), 4, Duration.ofMinutes(22));
        });
    }

    private void verifyEncoding(PreparedVideoWriter writer, int expectedThreads, Duration expectedBudget) throws Exception {
        Path playlist = work.resolve("index.m3u8");
        Path media = work.resolve("media.mp4");
        Files.writeString(playlist, "#EXT-X-MAP:URI=\"media.mp4\",BYTERANGE=\"800@0\"\n"
                + "#EXTINF:4,\n#EXT-X-BYTERANGE:500@800\n#EXTINF:1,\n#EXT-X-BYTERANGE:250@1300\n");
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class)) {
            ffmpeg.when(() -> FFmpegUtil.transcodeHls(eq("source"), eq(work), eq(PlaybackQuality.SOURCE), any(), any()))
                    .thenAnswer(call -> {
                        FFmpegUtil.Options options = call.getArgument(3);
                        FFmpegUtil.HlsTimeouts timeouts = call.getArgument(4);
                        assertEquals(expectedThreads, options.ffmpegThreads);
                        assertEquals(expectedBudget, timeouts.total());
                        return new FFmpegUtil.HlsOutput(playlist, media);
                    });
            ffmpeg.when(() -> FFmpegUtil.probeHls(eq(media.toString()), any()))
                    .thenReturn(new FFmpegUtil.HlsProbe(1920, 1080, "avc1.640028,mp4a.40.2", 25));

            PreparedVideoWriter.Result result = writer.write("source", work, PlaybackQuality.SOURCE, 600000);
            assertEquals(1920, result.width());
            assertEquals(2000, result.bandwidth());
            assertEquals(1200, result.playback().averageBandwidth());
            assertEquals("avc1.640028,mp4a.40.2", result.playback().codecs());
            assertEquals(25, result.playback().frameRate());
        }
    }
}
