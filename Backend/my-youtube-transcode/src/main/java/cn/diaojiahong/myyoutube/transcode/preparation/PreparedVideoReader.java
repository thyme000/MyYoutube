package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreparedVideoReader {
    private final MediaPreparationProperties properties;
    private final PreparedImageWriter images;
    private static final Pattern PTS = Pattern.compile("pts_time:([0-9]+(?:\\.[0-9]+)?)");

    public record Probe(long durationMs, int width, int height, int rotation, boolean hasAudio,
                        String profile, int level, double frameRate) {}
    public record Frame(int index, long timeMs, Path path, PreparedImageWriter.Result image) {}
    @FunctionalInterface public interface FrameConsumer { void accept(Frame frame) throws Exception; }

    public Probe probe(String source, byte[] head) {
        String mime = new Tika().detect(head);
        if (!mime.startsWith("video/") && !"application/mp4".equals(mime))
            throw new IllegalArgumentException("视频类型初筛失败: " + mime);
        var result = FFmpegUtil.runBoundedCommand(List.of("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", source), Duration.ofSeconds(60));
        if (result.exitCode() != 0) throw new IllegalArgumentException("视频探测失败");
        return validateProbe(result.output());
    }

    public Probe validateProbe(String text) {
        var json = StructuredJson.object(text);
        var format = json.getAsJsonObject("format");
        if (!Set.of(format.get("format_name").getAsString().split(",")).contains("mp4"))
            throw new IllegalArgumentException("仅支持 MP4 容器");
        var tags = format.get("tags");
        var brand = tags != null && tags.isJsonObject() ? tags.getAsJsonObject().get("major_brand") : null;
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
        for (var item : json.getAsJsonArray("streams")) {
            var stream = item.getAsJsonObject();
            String type = stream.get("codec_type").getAsString();
            if ("video".equals(type)) {
                if (video != null) throw new IllegalArgumentException("仅支持单条视频轨");
                if (!"h264".equals(stream.get("codec_name").getAsString())
                        || !"yuv420p".equals(stream.get("pix_fmt").getAsString())
                        || !properties.getVideoProfiles().contains(stream.get("profile").getAsString())
                        || stream.get("level").getAsInt() <= 0 || stream.get("level").getAsInt() > properties.getVideoMaxLevel())
                    throw new IllegalArgumentException("视频编码不兼容: codec=" + stream.get("codec_name")
                            + ", profile=" + stream.get("profile") + ", level=" + stream.get("level")
                            + ", pix_fmt=" + stream.get("pix_fmt") + "；要求 H.264、8-bit 4:2:0 (yuv420p)，profile="
                            + properties.getVideoProfiles().stream().sorted().toList()
                            + "，level≤" + properties.getVideoMaxLevel() + "（如 50 表示 5.0）");
                if (stream.has("color_transfer") && Set.of("smpte2084", "arib-std-b67").contains(stream.get("color_transfer").getAsString()))
                    throw new IllegalArgumentException("不支持 HDR 视频");
                video = stream;
            } else if ("audio".equals(type)) {
                if (audio || !"aac".equals(stream.get("codec_name").getAsString())) throw new IllegalArgumentException("音轨须为单条 AAC");
                audio = true;
            }
        }
        if (video == null) throw new IllegalArgumentException("缺少视频轨");
        int width = video.get("width").getAsInt(), height = video.get("height").getAsInt();
        if (width <= 0 || height <= 0 || (long) width * height > properties.getSourcePixels()
                || Math.max(width, height) > properties.getSourceLongEdge()) throw new IllegalArgumentException("视频尺寸超限");
        int rotation = 0;
        if (video.has("side_data_list")) for (var side : video.getAsJsonArray("side_data_list")) {
            if (side.getAsJsonObject().has("rotation")) rotation = side.getAsJsonObject().get("rotation").getAsInt();
        }
        return new Probe(Math.max(1, Math.round(seconds * 1000)), width, height, rotation, audio,
                video.get("profile").getAsString(), video.get("level").getAsInt(), frameRate(video));
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
        Files.createDirectories(directory);
        for (int index = 0; index < 8; index++) {
            if (skip.test(index)) continue;
            double duration = probe.durationMs() / 1000.0;
            double lastFrame = probe.frameRate() > 0 ? Math.max(0, duration - 1.0 / probe.frameRate()) : duration * 0.99;
            double second = Math.min(lastFrame, duration * (0.01 + 0.98 * index / 7.0));
            var raw = directory.resolve("frame-" + index + ".jpg");
            var prepared = directory.resolve("prepared-" + index + ".jpg");
            try {
                var result = FFmpegUtil.runBoundedCommand(List.of("ffmpeg", "-nostdin", "-y", "-threads", "1",
                        "-copyts", "-ss", String.format(java.util.Locale.ROOT, "%.6f", second), "-i", source,
                        "-map", "0:v:0", "-frames:v", "1", "-threads", "1", "-filter_threads", "1",
                        "-vf", "scale=w='min(iw," + properties.getLongEdge() + ")':h='min(ih," + properties.getLongEdge()
                                + ")':force_original_aspect_ratio=decrease,showinfo", "-q:v", "2", raw.toString()), Duration.ofSeconds(60));
                var timestamp = PTS.matcher(result.output());
                if (result.exitCode() != 0 || !Files.isRegularFile(raw) || !timestamp.find())
                    throw new IllegalStateException("视频公共帧缺失: index=" + index);
                long actualMs = Math.round(Double.parseDouble(timestamp.group(1)) * 1000);
                if (actualMs < 0 || actualMs > probe.durationMs()) throw new IllegalStateException("视频帧时间非法");
                var image = images.write(raw, prepared);
                consumer.accept(new Frame(index, actualMs, prepared, image));
            } finally {
                for (var temporary : List.of(raw, prepared)) {
                    try { Files.deleteIfExists(temporary); }
                    catch (java.io.IOException error) { log.warn("媒体准备临时文件清理失败", error); }
                }
            }
        }
    }
}
