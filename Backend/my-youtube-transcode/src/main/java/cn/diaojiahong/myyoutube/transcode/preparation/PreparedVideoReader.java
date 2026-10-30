package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.common.media.VideoFrameSampling;
import cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.regex.Matcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreparedVideoReader {
    private final MediaPreparationProperties properties;
    private final PreparedImageWriter images;
    private final FrameProcessingSettings frameSettings;

    private static final String DECIMAL = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";

    private static final Pattern PTS = Pattern.compile("pts_time:" + DECIMAL);

    private static final Pattern FRAME_PTS = Pattern.compile("(?m)^best_effort_timestamp_time=" + DECIMAL + "$");

    public record Probe(long durationMs, int width, int height, int rotation, boolean hasAudio,
                        String profile, int level, double frameRate, long videoDurationMs, long availableFrames) {
        public Probe(long durationMs, int width, int height, int rotation, boolean hasAudio,
                     String profile, int level, double frameRate) {
            this(durationMs, width, height, rotation, hasAudio, profile, level, frameRate, durationMs, 0);
        }
        public int sampleCount() {
            return VideoFrameSampling.count(videoDurationMs, availableFrames);
        }
    }

    public record Frame(int index, long timeMs, Path path, PreparedImageWriter.PreparedImageResult image) {
    }

    @FunctionalInterface public interface FrameConsumer {
        void accept(Frame frame) throws Exception;
    }

    public Probe probe(String source, byte[] head) {
        String mime = new Tika().detect(head);
        if (!mime.startsWith("video/") && !"application/mp4".equals(mime))
            throw new IllegalArgumentException("视频类型初筛失败: " + mime);
        FFmpegUtil.CommandOutput result = FFmpegUtil.runBoundedCommand(
                List.of("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", source),
                Duration.ofSeconds(60));
        if (result.exitCode() != 0) throw new IllegalArgumentException("视频探测失败");
        return validateProbe(result.output());
    }

    public Probe validateProbe(String text) {
        JsonObject json = StructuredJson.object(text);
        JsonObject format = json.getAsJsonObject("format");
        if (!Set.of(format.get("format_name").getAsString().split(",")).contains("mp4"))
            throw new IllegalArgumentException("仅支持 MP4 容器");
        JsonElement tags = format.get("tags");
        JsonElement brand = tags != null && tags.isJsonObject() ? tags.getAsJsonObject().get("major_brand") : null;
        if (brand == null || brand.isJsonNull() || !brand.isJsonPrimitive()
                || !brand.getAsJsonPrimitive().isString() || brand.getAsString().isBlank())
            throw new IllegalArgumentException("MP4 探测结果缺少 major_brand，无法确认容器兼容性");
        // ffprobe 可能用分号合并文件头中的 brand 与重复的元数据标签。
        String[] majorBrands = brand.getAsString().split(";", -1);
        String majorBrand = majorBrands[0].trim();
        if (!properties.getVideoBrands().contains(majorBrand)
                || java.util.Arrays.stream(majorBrands).anyMatch(value -> !majorBrand.equals(value.trim())))
            throw new IllegalArgumentException("不支持的 MP4 容器 brand: major_brand=" + brand
                    + ", 允许值=" + properties.getVideoBrands().stream().sorted().toList());
        double seconds = format.get("duration").getAsDouble();
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > Long.MAX_VALUE / 1000.0)
            throw new IllegalArgumentException("视频时长非法");
        com.google.gson.JsonObject video = null;
        boolean audio = false;
        for (JsonElement item : json.getAsJsonArray("streams")) {
            JsonObject stream = item.getAsJsonObject();
            String type = stream.get("codec_type").getAsString();
            if ("video".equals(type)) {
                if (video != null) throw new IllegalArgumentException("仅支持单条视频轨");
                if (!"h264".equals(stream.get("codec_name").getAsString())
                        || !"yuv420p".equals(stream.get("pix_fmt").getAsString())
                        || !properties.getVideoProfiles().contains(stream.get("profile").getAsString())
                        || stream.get("level").getAsInt() <= 0
                        || stream.get("level").getAsInt() > properties.getVideoMaxLevel())
                    throw new IllegalArgumentException("视频编码不兼容: codec=" + stream.get("codec_name")
                            + ", profile=" + stream.get("profile") + ", level=" + stream.get("level")
                            + ", pix_fmt=" + stream.get("pix_fmt") + "；要求 H.264、8-bit 4:2:0 (yuv420p)，profile="
                            + properties.getVideoProfiles().stream().sorted().toList()
                            + "，level≤" + properties.getVideoMaxLevel() + "（如 50 表示 5.0）");
                if (stream.has("color_transfer")
                        && Set.of("smpte2084", "arib-std-b67").contains(stream.get("color_transfer").getAsString()))
                    throw new IllegalArgumentException("不支持 HDR 视频");
                video = stream;
            } else if ("audio".equals(type)) {
                if (audio
                        || !"aac".equals(stream.get("codec_name").getAsString())) throw new IllegalArgumentException("音轨须为单条 AAC");
                audio = true;
            }
        }
        if (video == null) throw new IllegalArgumentException("缺少视频轨");
        int width = video.get("width").getAsInt(), height = video.get("height").getAsInt();
        if (width <= 0 || height <= 0 || (long) width * height > properties.getSourcePixels()
                || Math.max(width, height) > properties.getSourceLongEdge()) throw new IllegalArgumentException("视频尺寸超限");
        int rotation = 0;
        if (video.has("side_data_list")) for (JsonElement side : video.getAsJsonArray("side_data_list")) {
            if (side.getAsJsonObject().has("rotation")) rotation = side.getAsJsonObject().get("rotation").getAsInt();
        }
        return new Probe(Math.max(1, Math.round(seconds * 1000)), width, height, rotation, audio,
                video.get("profile").getAsString(), video.get("level").getAsInt(), frameRate(video),
                videoDuration(video, seconds), availableFrames(video));
    }

    private static long videoDuration(com.google.gson.JsonObject stream, double fallback) {
        double seconds = fallback;
        if (stream.has("duration")) {
            try {
                seconds = Double.parseDouble(stream.get("duration").getAsString());
            } catch (NumberFormatException ignored) {
                seconds = fallback;
            }
        }
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > Long.MAX_VALUE / 1000.0) seconds = fallback;
        return Math.max(1, Math.round(seconds * 1000));
    }

    private static long availableFrames(com.google.gson.JsonObject stream) {
        if (!stream.has("nb_frames")) return 0;
        try {
            return Math.max(0, Long.parseLong(stream.get("nb_frames").getAsString()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double frameRate(com.google.gson.JsonObject stream) {
        if (!stream.has("avg_frame_rate")) return 0;
        String[] parts = stream.get("avg_frame_rate").getAsString().split("/");
        if (parts.length != 2) return 0;
        double rate = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        return Double.isFinite(rate) && rate > 0 ? rate : 0;
    }

    public void frames(String source, Probe probe, Path directory, FrameConsumer consumer) throws Exception {
        frames(source, probe, directory, index -> false, consumer);
    }

    public void frames(String source, Probe probe, Path directory, java.util.function.IntPredicate skip,
                       FrameConsumer consumer) throws Exception {
        frames(
                source,
                probe,
                directory,
                frameSettings.snapshot().plan(probe.videoDurationMs(), probe.availableFrames()),
                skip,
                consumer);
    }

    public void frames(
            String source,
            Probe probe,
            Path directory,
            VideoFrameSampling.Plan plan,
            java.util.function.IntPredicate skip,
                       FrameConsumer consumer) throws Exception {
        int count = plan.targetCount();
        var accepted = new ArrayList<Integer>();
        Files.createDirectories(directory);
        double duration = probe.videoDurationMs() / 1000.0;
        // 时长已取整到毫秒，seek 再取整到微秒；避免片尾定位越过最后一帧的 PTS。
        double lastFrame = probe.frameRate() > 0
                ? Math.max(0, duration - 1.0 / probe.frameRate() - 0.001) : duration * 0.99;
        double samplingEnd = lastFrame;
        for (int index = 0; index < count; index++) {
            if (skip.test(index)) {
                accepted.add(index);
                continue;
            }
            double second = Math.min(lastFrame, samplingEnd * VideoFrameSampling.ratio(index, count));
            Path raw = directory.resolve("frame-" + index + ".jpg");
            Path prepared = directory.resolve("prepared-" + index + ".jpg");
            try {
                Frame frame = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    FFmpegUtil.CommandOutput result = extract(source, second, raw);
                    if (FFmpegUtil.extractionUnavailable(result)) throw commandFailure(
                            "视频抽帧进程或源输入不可用",
                            index,
                            second,
                            result);
                    Matcher timestamp = PTS.matcher(result.output());
                    if (result.exitCode() == 0 && Files.isRegularFile(raw) && Files.size(raw) > 0 && timestamp.find()) {
                        double actualSecond = Double.parseDouble(timestamp.group(1));
                        if (!Double.isFinite(actualSecond)) throw new IllegalStateException("视频帧时间非法");
                        long actualMs = Math.round(actualSecond * 1000);
                        if (actualMs < 0 || actualMs > probe.durationMs()) throw new IllegalStateException("视频帧时间非法");
                        PreparedImageWriter.PreparedImageResult image = images.write(raw, prepared);
                        frame = new Frame(index, actualMs, prepared, image);
                        break;
                    }
                    log.warn(
                            "视频采样点抽取失败: index={}, seek={}s, attempt={}, exitCode={}",
                            index,
                            seconds(second),
                            attempt + 1,
                            result.exitCode());
                    if (attempt == 0 && result.exitCode() == 0 && !PTS.matcher(result.output()).find()) {
                        // 正常 EOF 才查询真实片尾；解码错误不能被片尾恢复掩盖。
                        double tail = lastFrameSecond(source, second, index);
                        if (tail >= 0) {
                            lastFrame = Math.max(0, tail - 0.000002);
                            second = Math.min(second, lastFrame);
                        }
                    }
                }
                if (frame != null) {
                    consumer.accept(frame);
                    accepted.add(index);
                }
            } finally {
                for (Path temporary : List.of(raw, prepared)) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (java.io.IOException error) {
                        log.warn("媒体准备临时文件清理失败", error);
                    }
                }
            }
        }
        plan.validate(accepted);
        log.info(
                "视频抽帧完成: target={} minimum={} actual={} missing={}",
                count,
                plan.minimumCount(),
                accepted.size(),
                plan.missing(accepted));
    }

    private FFmpegUtil.CommandOutput extract(String source, double second, Path raw) throws java.io.IOException {
        Files.deleteIfExists(raw);
        return FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-y", "-threads", "1", "-xerror",
                "-copyts", "-ss", seconds(second), "-i", source,
                "-map", "0:v:0", "-frames:v", "1", "-threads", "1", "-filter_threads", "1",
                "-vf", "scale=w='min(iw," + properties.getLongEdge() + ")':h='min(ih," + properties.getLongEdge()
                        + ")':force_original_aspect_ratio=decrease,showinfo", "-q:v", "2", "-pix_fmt", "yuvj420p",
                raw.toString()), Duration.ofSeconds(60));
    }

    private double lastFrameSecond(String source, double second, int index) {
        for (double start : new double[]{second, 0}) {
            FFmpegUtil.CommandOutput result = FFmpegUtil.runBoundedCommand(List.of("ffprobe", "-v", "error", "-threads", "1", "-select_streams", "v:0",
                    "-read_intervals", seconds(start) + "%", "-show_entries", "frame=best_effort_timestamp_time",
                    "-of", "default=noprint_wrappers=1", source), Duration.ofSeconds(60));
            if (result.exitCode() != 0) throw commandFailure("视频片尾探测失败", index, start, result);
            Matcher timestamps = FRAME_PTS.matcher(result.output());
            double last = -1;
            while (timestamps.find()) {
                double value = Double.parseDouble(timestamps.group(1));
                if (Double.isFinite(value)) last = Math.max(last, value);
            }
            if (last >= 0) return last;
        }
        return -1;
    }

    private static String seconds(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static IllegalStateException commandFailure(
            String message,
            int index,
            double second,
            FFmpegUtil.CommandOutput result) {
        String detail = result.output().strip();
        if (detail.length() > 1800) detail = detail.substring(detail.length() - 1800);
        log.warn(
                "{}: index={}, seek={}s, exitCode={}, output={}",
                message,
                index,
                seconds(second),
                result.exitCode(),
                detail);
        return new IllegalStateException(message + ": index=" + index + ", seek=" + seconds(second)
                + "s, exitCode=" + result.exitCode() + "，详情见服务日志");
    }
}
