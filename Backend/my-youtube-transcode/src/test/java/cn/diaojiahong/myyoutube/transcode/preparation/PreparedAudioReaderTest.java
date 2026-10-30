package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException;
import static cn.diaojiahong.myyoutube.common.media.InvalidMediaInputException.Reason.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparedAudioReaderTest {
    @TempDir Path work;

    final PreparedAudioReader reader = new PreparedAudioReader(new AudioPreparationProperties());

    @ParameterizedTest @CsvSource({"wav,audio/wav", "mp3,audio/mpeg", "m4a,audio/mp4", "aac,audio/aac", "flac,audio/flac", "ogg,audio/ogg", "opus,audio/ogg"})
    void fullyDecodesRealAudioAndComputesDuration(String extension, String mime) throws Exception {
        Path source = work.resolve("sample." + extension);
        generate(source, "1.2");
        PreparedAudioReader.Probe result = reader.read(source);
        assertEquals(mime, result.mime());
        assertTrue(result.durationMs() >= 1150 && result.durationMs() <= 1300, result.toString());
    }

    @Test
    void rejectsDecodedAudioLongerThanTenMinutes() throws Exception {
        Path source = work.resolve("too-long.wav");
        generate(source, "600.1");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> reader.read(source))
                .getMessage()
                .contains("10 分钟"));
    }

    @Test
    void rejectsTextRenamedToAudio() throws Exception {
        Path source = work.resolve("bad.mp3");
        Files.writeString(source, "This is not audio.");
        assertThrows(IllegalArgumentException.class, () -> reader.read(source));
    }

    @Test
    void rejectsAnEmptyWavHeaderInsteadOfTreatingItAsSilence() throws Exception {
        Path source = work.resolve("empty.wav");
        generate(source, "0.1");
        Files.write(source, java.util.Arrays.copyOf(Files.readAllBytes(source), 44));
        assertThrows(IllegalArgumentException.class, () -> reader.read(source));
    }

    @Test
    void processTimeoutIsTechnicalFailureNotContentRejection() throws Exception {
        Path source = work.resolve("sample.wav");
        generate(source, "0.1");
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class)) {
            ffmpeg.when(() -> FFmpegUtil.runBoundedCommand(anyList(), any()))
                    .thenReturn(new FFmpegUtil.CommandOutput(-1, "process timeout"));
            assertThrows(IllegalStateException.class, () -> reader.read(source));
        }
    }

    @Test
    void requiresSingleAudioTrackAndRejectsVideo() {
        String audio = "{\"codec_type\":\"audio\",\"codec_name\":\"aac\",\"sample_rate\":\"48000\",\"channels\":2}";
        String video = "{\"codec_type\":\"video\",\"disposition\":{\"attached_pic\":0}}";
        assertThrows(
                IllegalArgumentException.class,
                () -> reader.validateProbe("{\"streams\":[" + audio + "," + audio + "],\"format\":{\"format_name\":\"mp4\"}}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reader.validateProbe("{\"streams\":[" + audio + "," + video + "],\"format\":{\"format_name\":\"mp4\"}}"));
    }

    @Test
    void missingProgressOrOverLimitProgressCannotBeSuccessful() {
        assertThrows(IllegalArgumentException.class, () -> reader.decodedDuration("progress=end\n"));
        assertThrows(
                IllegalStateException.class,
                () -> reader.decodedDuration("out_time_us=1234000\nprogress=continue\n"));
        assertThrows(
                IllegalArgumentException.class,
                () -> reader.decodedDuration("out_time_us=600001000\nprogress=end\n"));
        assertEquals(1234, reader.decodedDuration("out_time_us=1200000\nout_time_us=1234000\nprogress=end\n"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"sample_rate", "channels", "codec_name"})
    void missingRequiredStreamMetadataIsAnExplicitInputRejection(String missing) {
        JsonObject audio = com.google.gson.JsonParser.parseString("{\"codec_type\":\"audio\",\"codec_name\":\"aac\",\"sample_rate\":\"48000\",\"channels\":2}")
                .getAsJsonObject();
        audio.remove(missing);
        InvalidMediaInputException error = assertThrows(
                InvalidMediaInputException.class,
                () -> reader.validateProbe("{\"streams\":[" + audio + "],\"format\":{\"format_name\":\"mp4\"}}"));
        assertEquals(INVALID_AUDIO, error.reason());
    }

    @Test
    void malformedToolOutputIsNotAnInputRejection() {
        RuntimeException error = assertThrows(RuntimeException.class, () -> reader.validateProbe("not json"));
        assertFalse(error instanceof InvalidMediaInputException);
    }

    @Test
    void unknownPositiveProcessFailureIsTechnical() throws Exception {
        Path source = work.resolve("sample.wav");
        generate(source, "0.1");
        try (MockedStatic<FFmpegUtil> ffmpeg = mockStatic(FFmpegUtil.class)) {
            ffmpeg.when(() -> FFmpegUtil.runBoundedCommand(anyList(), any()))
                    .thenReturn(new FFmpegUtil.CommandOutput(1, "Unrecognized option from server configuration"));
            assertThrows(IllegalStateException.class, () -> reader.read(source));
        }
    }

    void generate(Path source, String duration) {
        FFmpegUtil.CommandOutput result = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000",
                "-t",
                duration,
                "-threads",
                "1",
                source.toString()), Duration.ofSeconds(30));
        assertEquals(0, result.exitCode(), result.output());
    }
}
