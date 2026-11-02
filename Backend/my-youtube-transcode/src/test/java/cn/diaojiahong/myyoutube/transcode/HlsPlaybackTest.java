package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.transcode.preparation.PreparedVideoWriter;
import cn.diaojiahong.myyoutube.transcode.preparation.MediaPreparationProperties;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "hls.native", matches = "true")
class HlsPlaybackTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern INIT_RANGE = Pattern.compile("#EXT-X-MAP:URI=\"media.mp4\",BYTERANGE=\"(\\d+)@(\\d+)\"");
    private static final Pattern MEDIA_RANGE = Pattern.compile("#EXT-X-BYTERANGE:(\\d+)@(\\d+)");

    @TempDir Path work;

    @ParameterizedTest
    @CsvSource({
            "320, 180, 9.2, true, 320, 180, 3",
            "180, 320, 9.2, false, 180, 320, 3",
            "160, 90, 0.6, false, 160, 90, 1",
            "80, 46, 4.2, true, 80, 46, 2"
    })
    void singleFileSupportsShortPortraitSilentAndSmallerSources(
            int width, int height, double seconds, boolean audio,
            int expectedWidth, int expectedHeight, int expectedSegments) throws Exception {
        Path source = source(width, height, seconds, audio);
        byte[] original = Files.readAllBytes(source);
        Path directory = work.resolve("播放 output");
        FFmpegUtil.HlsOutput output = FFmpegUtil.transcodeHls(source.toString(), directory, PlaybackQuality.P480, options(), new FFmpegUtil.HlsTimeouts(TIMEOUT, TIMEOUT, TIMEOUT));

        String playlist = Files.readString(output.playlist());
        assertTrue(playlist.startsWith("#EXTM3U\n"));
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertTrue(playlist.contains("#EXT-X-INDEPENDENT-SEGMENTS"));
        assertTrue(playlist.endsWith("#EXT-X-ENDLIST\n"));
        assertFalse(playlist.contains(work.toString()), "清单不能暴露本地路径");
        assertTrue(playlist.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .allMatch("media.mp4"::equals));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(2, files.count(), "每档只产生媒体文件和清单");
        }

        byte[] media = Files.readAllBytes(output.media());
        List<ByteRange> segments = ranges(playlist);
        assertEquals(expectedSegments, segments.size());
        List<Double> durations = playlist.lines().filter(line -> line.startsWith("#EXTINF:"))
                .map(line -> Double.parseDouble(line.substring(8, line.indexOf(',')))).toList();
        assertEquals(expectedSegments, durations.size());
        assertEquals(seconds, durations.stream().mapToDouble(Double::doubleValue).sum(), 0.1);
        for (int i = 0; i < durations.size() - 1; i++) {
            assertEquals(4, durations.get(i), 0.1);
        }
        ByteRange init = initRange(playlist);
        long end = init.offset() + init.length();
        for (ByteRange segment : segments) {
            assertEquals(end, segment.offset(), "媒体字节范围必须连续");
            end += segment.length();
        }
        assertEquals(media.length, end, "清单范围必须覆盖完整媒体文件");

        FFmpegUtil.VideoProbe probe = FFmpegUtil.probeVideo(output.media().toString(), options());
        assertEquals(expectedWidth, probe.width());
        assertEquals(expectedHeight, probe.height());
        assertEquals(seconds * 1000, probe.durationMs(), 150);
        String audioCodec = run(List.of("ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", output.media().toString())).strip();
        assertEquals(audio ? "aac" : "", audioCodec);
        run(List.of("ffmpeg", "-v", "error", "-xerror", "-i", output.playlist().toString(), "-f", "null", "-"));

        // 拼接初始化段和最后一段，验证拖动时无需读取前面的媒体段即可解码。
        decodeLastSegment(copy(media, init), copy(media, segments.get(segments.size() - 1)));
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void invalidSourceDoesNotReturnSuccessfulOutput() {
        assertThrows(IllegalStateException.class, () -> FFmpegUtil.transcodeHls(
                work.resolve("missing.mp4").toString(), work.resolve("failed"), PlaybackQuality.P480, options(), new FFmpegUtil.HlsTimeouts(TIMEOUT, TIMEOUT, TIMEOUT)));
    }

    @ParameterizedTest
    @CsvSource({
            "1920,1080,9.2,25,3,true,0",
            "1080,1920,9.2,30000/1001,3,false,0",
            "3840,2160,0.6,10,4,true,0",
            "320,180,0.6,25,1,false,0",
            "1920,1080,4.2,25,3,true,90"
    })
    void nativeQualityLadderAlignsSegmentsAndKeepsOriginal(int width, int height, double seconds, String rate,
                                                         int expectedQualities, boolean audio, int rotation) throws Exception {
        Path source = source(width, height, seconds, audio, rate);
        if (rotation != 0) {
            Path rotated = work.resolve("rotated.mp4");
            run(List.of("ffmpeg", "-v", "error", "-display_rotation", Integer.toString(rotation),
                    "-i", source.toString(), "-c", "copy", rotated.toString()));
            assertEquals(rotation, Integer.parseInt(run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream_side_data=rotation", "-of", "csv=p=0", rotated.toString())).strip()));
            source = rotated;
        }
        int displayWidth = rotation == 0 ? width : height;
        int displayHeight = rotation == 0 ? height : width;
        byte[] original = Files.readAllBytes(source);
        List<PlaybackQuality> qualities = PlaybackQuality.forVideo(displayWidth, displayHeight);
        assertEquals(expectedQualities, qualities.size());
        List<Double> previousDurations = null;
        List<Double> previousKeys = null;
        PreparedVideoWriter writer = new PreparedVideoWriter(new MediaPreparationProperties());
        for (PlaybackQuality quality : qualities) {
            PreparedVideoWriter.Result output = writer.write(source.toString(), work.resolve("q" + quality.code()), quality, Math.round(seconds * 1000));
            if (quality == PlaybackQuality.SOURCE) {
                assertEquals(displayWidth, output.width());
                assertEquals(displayHeight, output.height());
            } else {
                assertEquals(quality.code(), Math.min(output.width(), output.height()));
                assertTrue(output.width() < displayWidth && output.height() < displayHeight);
            }
            assertTrue(output.bandwidth() > 0);
            assertTrue(output.playback().averageBandwidth() > 0);
            assertTrue(output.playback().averageBandwidth() <= output.bandwidth());
            assertEquals(audio, output.playback().codecs().contains("mp4a.40.2"));
            assertTrue(output.playback().codecs().matches("avc1\\.[0-9a-f]{6}(,mp4a\\.40\\.2)?"));
            String[] frameRate = rate.split("/");
            double expectedRate = Double.parseDouble(frameRate[0]) / (frameRate.length == 2 ? Double.parseDouble(frameRate[1]) : 1);
            assertEquals(expectedRate, output.playback().frameRate(), 0.001);
            String playlist = Files.readString(output.playlist());
            List<Double> durations = playlist.lines().filter(line -> line.startsWith("#EXTINF:"))
                    .map(line -> Double.parseDouble(line.substring(8, line.indexOf(',')))).toList();
            String keys = run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0", "-skip_frame", "nokey",
                    "-show_entries", "frame=best_effort_timestamp_time", "-of", "csv=p=0", output.media().toString()));
            List<Double> keyTimes = keys.lines().filter(line -> line.matches("[0-9]+\\.[0-9]+.*"))
                    .map(line -> Double.parseDouble(line.split(",")[0])).toList();
            assertEquals(durations.size(), keyTimes.size());
            // B 帧重排会产生固定起始偏移；切换要求各档的真实时间戳与相对段边界一致。
            double elapsed = 0;
            for (int i = 0; i < keyTimes.size(); i++) {
                assertEquals(keyTimes.get(0) + elapsed, keyTimes.get(i), 0.05);
                elapsed += durations.get(i);
            }
            if (previousDurations != null) {
                assertEquals(previousDurations.size(), durations.size());
                for (int i = 0; i < durations.size(); i++) {
                    assertEquals(previousDurations.get(i), durations.get(i), 0.001);
                    assertEquals(previousKeys.get(i), keyTimes.get(i), 0.001);
                }
            }
            previousDurations = durations;
            previousKeys = keyTimes;
            byte[] media = Files.readAllBytes(output.media());
            List<ByteRange> segments = ranges(playlist);
            decodeLastSegment(copy(media, initRange(playlist)), copy(media, segments.get(segments.size() - 1)));
            run(List.of("ffmpeg", "-v", "error", "-xerror", "-i", output.playlist().toString(), "-f", "null", "-"));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HLS_S3_ENDPOINT", matches = ".+")
    void signedRustFsRangesSupportCorsAndIndependentDecoding() throws Exception {
        String bucket = requiredEnv("HLS_S3_BUCKET");
        URI endpoint = URI.create(requiredEnv("HLS_S3_ENDPOINT"));
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                requiredEnv("HLS_S3_ACCESS_KEY"), requiredEnv("HLS_S3_SECRET_KEY")));
        S3Configuration configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        FFmpegUtil.HlsOutput output = FFmpegUtil.transcodeHls(source(320, 180, 9.2, true).toString(),
                work.resolve("hls"), PlaybackQuality.P480, options(), new FFmpegUtil.HlsTimeouts(TIMEOUT, TIMEOUT, TIMEOUT));
        String prefix = "hls-it/" + UUID.randomUUID() + "/";
        String mediaKey = prefix + "media.mp4";
        String playlistKey = prefix + "index.m3u8";
        String origin = System.getenv().getOrDefault("HLS_S3_ORIGIN", "http://localhost:5173");

        try (S3Client s3 = S3Client.builder().endpointOverride(endpoint).credentialsProvider(credentials)
                .region(Region.US_EAST_1).serviceConfiguration(configuration).build();
             S3Presigner signer = S3Presigner.builder().endpointOverride(endpoint).credentialsProvider(credentials)
                     .region(Region.US_EAST_1).serviceConfiguration(configuration).build()) {
            try {
                s3.putObject(b -> b.bucket(bucket).key(mediaKey).contentType("video/mp4"), RequestBody.fromFile(output.media()));
                s3.putObject(b -> b.bucket(bucket).key(playlistKey).contentType("application/vnd.apple.mpegurl"),
                        RequestBody.fromFile(output.playlist()));
                HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
                URI playlistUrl = signer.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(5))
                        .getObjectRequest(r -> r.bucket(bucket).key(playlistKey))).url().toURI();
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(playlistUrl).timeout(TIMEOUT)
                        .header("Origin", origin).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                        .startsWith("application/vnd.apple.mpegurl"));
                assertCors(response, origin);
                assertEquals(Files.readString(output.playlist()), response.body());

                URI mediaUrl = signer.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(5))
                        .getObjectRequest(r -> r.bucket(bucket).key(mediaKey))).url().toURI();
                List<ByteRange> segments = ranges(response.body());
                ByteRange last = segments.get(segments.size() - 1);
                byte[] init = fetchRange(http, mediaUrl, initRange(response.body()), Files.size(output.media()), origin);
                byte[] segment = fetchRange(http, mediaUrl, last, Files.size(output.media()), origin);
                byte[] media = Files.readAllBytes(output.media());
                assertArrayEquals(copy(media, initRange(response.body())), init);
                assertArrayEquals(copy(media, last), segment);
                decodeLastSegment(init, segment);
            } finally {
                s3.deleteObject(b -> b.bucket(bucket).key(playlistKey));
                s3.deleteObject(b -> b.bucket(bucket).key(mediaKey));
            }
        }
    }

    private byte[] fetchRange(HttpClient http, URI url, ByteRange range, long total, String origin) throws Exception {
        long last = range.offset() + range.length() - 1;
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(url).timeout(TIMEOUT).header("Origin", origin)
                .header("Range", "bytes=" + range.offset() + "-" + last).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, response.statusCode());
        assertEquals("bytes " + range.offset() + "-" + last + "/" + total,
                response.headers().firstValue("Content-Range").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("video/mp4"));
        assertEquals(range.length(), response.body().length);
        assertCors(response, origin);
        return response.body();
    }

    private static void assertCors(HttpResponse<?> response, String origin) {
        String allowed = response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow();
        assertTrue("*".equals(allowed) || origin.equals(allowed));
    }

    private Path source(int width, int height, double seconds, boolean audio) {
        return source(width, height, seconds, audio, "25");
    }

    private Path source(int width, int height, double seconds, boolean audio, String rate) {
        Path source = work.resolve("source.mp4");
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi",
                "-i", "testsrc2=size=" + width + "x" + height + ":rate=" + rate));
        if (audio) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"));
        command.addAll(List.of("-t", Double.toString(seconds), "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-c:a", "aac", source.toString()));
        run(command);
        return source;
    }

    private void decodeLastSegment(byte[] init, byte[] segment) throws Exception {
        Path isolated = work.resolve("last-segment.mp4");
        try (java.io.OutputStream stream = Files.newOutputStream(isolated)) {
            stream.write(init);
            stream.write(segment);
        }
        run(List.of("ffmpeg", "-v", "error", "-xerror", "-i", isolated.toString(), "-f", "null", "-"));
        String frame = run(List.of("ffprobe", "-v", "error", "-select_streams", "v:0", "-read_intervals", "%+#1",
                "-show_entries", "frame=key_frame", "-of", "csv=p=0", isolated.toString()));
        assertTrue(frame.strip().startsWith("1"), "独立段必须从关键帧开始");
    }

    private static FFmpegUtil.Options options() {
        return new FFmpegUtil.Options();
    }

    private static String run(List<String> command) {
        FFmpegUtil.CommandOutput result = FFmpegUtil.runBoundedCommand(command, TIMEOUT);
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    private static ByteRange initRange(String playlist) {
        Matcher matcher = INIT_RANGE.matcher(playlist);
        assertTrue(matcher.find(), "必须包含初始化段范围");
        return new ByteRange(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private static List<ByteRange> ranges(String playlist) {
        return MEDIA_RANGE.matcher(playlist).results().map(match ->
                new ByteRange(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)))).toList();
    }

    private static byte[] copy(byte[] media, ByteRange range) {
        return Arrays.copyOfRange(media, range.offset(), range.offset() + range.length());
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        assertNotNull(value, "设置 " + key + " 后运行 RustFS 集成测试");
        assertFalse(value.isBlank(), key + " 不能为空");
        return value;
    }

    private record ByteRange(int length, int offset) {
    }
}
