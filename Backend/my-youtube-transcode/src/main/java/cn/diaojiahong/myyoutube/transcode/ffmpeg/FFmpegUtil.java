package cn.diaojiahong.myyoutube.transcode.ffmpeg;

import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import cn.diaojiahong.myyoutube.common.media.StructuredJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 视频自动封面工具: 多点抽帧 -> 图像质量评分; 输入支持本地路径或 http(s) URL,
 * ffmpeg 对 URL 按 Range 按需拉取, 不落全量视频。
 *
 * <p>需要系统安装 ffmpeg/ffprobe; 评分为清晰度(拉普拉斯近似)+亮度+对比度,
 * 明显废帧(过暗/过曝/近纯色)由 {@link #passesPrefilter} 判定, 交调用方过滤。
 */
@Slf4j
public final class FFmpegUtil {

    private FFmpegUtil() {
    }

    public static final class Options {
        /** ffmpeg 命令名或绝对路径 */
        public String ffmpegCommand = "ffmpeg";
        /** ffprobe 命令名或绝对路径 */
        public String ffprobeCommand = "ffprobe";
        /** 候选帧数量 */
        public int sampleCount = 8;

        /** 采样起始比例(跳过片头) */
        public double sampleStartRatio = 0.10;

        /** 采样结束比例(跳过片尾) */
        public double sampleEndRatio = 0.90;
        /** 单次外部命令超时 */
        public Duration commandTimeout = Duration.ofSeconds(30);

        /** ffmpeg 输出 jpg 质量(1 最好, 31 最差) */
        public int jpegQuality = 4;

        /** 候选帧长边上限(横屏钳宽/竖屏钳高), 源更小不放大 */
        public int candidateMaxLongEdge = 640;
        /** 成品封面长边上限 */
        public int coverMaxLongEdge = 1280;
        public int ffmpegThreads = 1;
        /** 预筛: 亮度均值低于此值判过暗 */
        public double prefilterMinBrightness = 40;
        /** 预筛: 亮度均值高于此值判过曝 */
        public double prefilterMaxBrightness = 220;
        /** 预筛: 灰度标准差低于此值判近纯色 */
        public double prefilterMinContrast = 8;
    }

    public record FrameScore(
            Path framePath,
            double second,
            double sharpnessScore,
            double brightnessScore,
            double contrastScore,
            /** 灰度均值, 供过暗/过曝预筛 */
            double meanBrightness,
            /** 灰度标准差, 供近纯色预筛 */
            double contrastStd,
            double totalScore
    ) {
    }

    /**
     * 探测视频元数据: 时长/宽/高, 探测失败抛异常(调用方按任务失败处理)
     */
    public record VideoProbe(long durationMs, int width, int height) {
    }

    public static VideoProbe probeVideo(String source, Options options) {
        Double seconds = probeDurationSeconds(source, options);
        if (seconds == null || seconds <= 0) {
            throw new IllegalStateException("ffprobe 无法探测到有效时长");
        }

        List<String> dimsCmd = List.of(
                options.ffprobeCommand,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=p=0",
                source
        );
        CommandResult result = runCommand(dimsCmd, options.commandTimeout);
        int width = 0;
        int height = 0;
        if (result.exitCode == 0 && result.output != null) {
            String[] parts = result.output.trim().split("[,xX\\s]+");
            if (parts.length >= 2) {
                try {
                    width = Integer.parseInt(parts[0].trim());
                    height = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    log.warn("ffprobe 尺寸输出不可解析: {}", tail(result.output));
                }
            }
        }
        return new VideoProbe(Math.round(seconds * 1000), width, height);
    }

    public record HlsProbe(int width, int height, String codecs, double frameRate) {}

    public static HlsProbe probeHls(String source, Options options) {
        CommandResult result = runCommand(List.of(options.ffprobeCommand, "-v", "error", "-show_data",
                "-show_entries", "stream=codec_type,codec_name,codec_tag_string,profile,extradata,width,height,avg_frame_rate",
                "-of", "json", source), options.commandTimeout);
        if (result.exitCode != 0) throw new IllegalStateException("HLS 产物探测失败: " + tail(result.output));
        return parseHlsProbe(result.output);
    }

    static HlsProbe parseHlsProbe(String text) {
        JsonObject json = StructuredJson.object(text);
        JsonObject video = null;
        boolean audio = false;
        for (JsonElement entry : json.getAsJsonArray("streams")) {
            JsonObject stream = entry.getAsJsonObject();
            switch (stream.get("codec_type").getAsString()) {
                case "video" -> {
                    if (!"h264".equals(stream.get("codec_name").getAsString())
                            || !"avc1".equals(stream.get("codec_tag_string").getAsString()))
                        throw new IllegalStateException("HLS 产物必须使用 avc1/H.264");
                    video = stream;
                }
                case "audio" -> {
                    if (!"aac".equals(stream.get("codec_name").getAsString())
                            || !"LC".equals(stream.get("profile").getAsString()))
                        throw new IllegalStateException("HLS 产物必须使用 AAC-LC 音轨");
                    audio = true;
                }
                default -> { }
            }
        }
        if (video == null) throw new IllegalStateException("HLS 产物缺少视频轨");

        // avcC 的第 2~4 字节分别为 profile、兼容标志和 level，不能用源视频的探测值替代。
        Matcher header = Pattern.compile("(?m)^\\s*00000000:\\s*([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})")
                .matcher(video.get("extradata").getAsString());
        if (!header.find()) throw new IllegalStateException("HLS 产物缺少 AVC 配置");
        String bytes = header.group(1) + header.group(2);
        if (!bytes.startsWith("01")) throw new IllegalStateException("HLS 产物的 AVC 配置版本无效");
        String codecs = "avc1." + bytes.substring(2).toLowerCase(Locale.ROOT) + (audio ? ",mp4a.40.2" : "");
        String[] rate = video.get("avg_frame_rate").getAsString().split("/");
        double frameRate = Double.parseDouble(rate[0]) / Double.parseDouble(rate[1]);
        if (!Double.isFinite(frameRate) || frameRate <= 0) throw new IllegalStateException("HLS 产物帧率无效");
        return new HlsProbe(video.get("width").getAsInt(), video.get("height").getAsInt(), codecs, frameRate);
    }

    /**
     * 标准化转码: 长边钳制 H.264/AAC mp4, faststart 便于流式播放。源更小不放大。
     */
    public static void transcodeStandard(
            String source,
            Path output,
            int maxLongEdge,
            Options options,
            Duration timeout) {
        List<String> cmd = List.of(
                options.ffmpegCommand, "-y",
                "-i", source,
                "-vf", longEdgeScaleExpr(maxLongEdge),
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                output.toString()
        );
        CommandResult result = runCommand(cmd, timeout);
        if (result.exitCode != 0) {
            throw new IllegalStateException("转码失败: " + tail(result.output));
        }
    }

    public record HlsOutput(Path playlist, Path media) {
    }

    public record HlsTimeouts(Duration total, Duration startup, Duration stall) {
        public HlsTimeouts {
            for (Duration value : List.of(total, startup, stall)) {
                if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("HLS 超时时间必须大于 0");
            }
        }
    }

    public enum HlsTimeoutReason {
        TOTAL_BUDGET_EXCEEDED("HLS 总预算超时"),
        STARTUP_TIMEOUT("HLS 启动等待超时"),
        PROGRESS_STALLED("HLS 连续无进展超时");

        private final String message;

        HlsTimeoutReason(String message) {
            this.message = message;
        }
    }

    public static final class HlsTimeoutException extends IllegalStateException {
        private final HlsTimeoutReason reason;

        private HlsTimeoutException(HlsTimeoutReason reason) {
            super(reason.message);
            this.reason = reason;
        }

        public HlsTimeoutReason reason() {
            return reason;
        }
    }

    /** 仅实际处理时间的增长续期停滞计时；普通日志和重复进度不能续期。 */
    static final class HlsProgress {
        private final HlsTimeouts timeouts;
        private final long startedAt;
        private long lastProgressAt;
        private long outputTimeUs;

        HlsProgress(HlsTimeouts timeouts, long startedAt) {
            this.timeouts = timeouts;
            this.startedAt = startedAt;
            this.lastProgressAt = startedAt;
        }

        synchronized void accept(String line, long now) {
            if (!line.startsWith("out_time_us=")) return;
            try {
                long timeUs = Long.parseLong(line.substring("out_time_us=".length()));
                if (timeUs > outputTimeUs) {
                    outputTimeUs = timeUs;
                    lastProgressAt = now;
                }
            } catch (NumberFormatException ignored) {
                // 启动时 FFmpeg 可能尚无有效输出时间。
            }
        }

        synchronized HlsTimeoutReason timeoutReason(long now) {
            if (now - startedAt >= timeouts.total().toNanos()) return HlsTimeoutReason.TOTAL_BUDGET_EXCEEDED;
            if (outputTimeUs == 0) {
                return now - startedAt >= timeouts.startup().toNanos() ? HlsTimeoutReason.STARTUP_TIMEOUT : null;
            }
            return now - lastProgressAt >= timeouts.stall().toNanos() ? HlsTimeoutReason.PROGRESS_STALLED : null;
        }

        synchronized long waitNanos(long now) {
            long remaining = timeouts.total().toNanos() - (now - startedAt);
            long idleRemaining = outputTimeUs == 0
                    ? timeouts.startup().toNanos() - (now - startedAt)
                    : timeouts.stall().toNanos() - (now - lastProgressAt);
            return Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(1), Math.min(remaining, idleRemaining)));
        }
    }

    /** 编码命令和准备版本共用同一组参数；文件路径不参与版本计算。 */
    public static List<String> hlsEncodingArguments(PlaybackQuality quality, int threads) {
        var arguments = new ArrayList<String>(List.of("-map", "0:v:0", "-map", "0:a:0?"));
        if (quality != PlaybackQuality.SOURCE) {
            int shortEdge = quality.code();
            arguments.addAll(List.of("-vf", "scale=if(gt(iw\\,ih)\\,-2\\,min(" + shortEdge + "\\,iw)):"
                    + "if(gt(iw\\,ih)\\,min(" + shortEdge + "\\,ih)\\,-2)"));
        }
        // 最高档保持源尺寸，以较低 CRF 减少再次编码的画质损失。
        arguments.addAll(List.of(
                "-c:v", "libx264", "-preset", "veryfast", "-crf", quality == PlaybackQuality.SOURCE ? "18" : "23",
                "-pix_fmt", "yuv420p", "-threads", Integer.toString(threads),
                // 固定时间强制关键帧，避免场景切换使段边界偏离；闭合 GOP 允许按段解码。
                "-force_key_frames", "expr:gte(t,n_forced*4)", "-sc_threshold", "0", "-flags", "+cgop",
                "-c:a", "aac", "-b:a", "128k",
                "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "vod",
                "-hls_segment_type", "fmp4", "-hls_flags", "single_file+independent_segments", "-f", "hls"));
        return List.copyOf(arguments);
    }

    /** 单档点播 HLS：媒体段放在同一 fMP4，目录通过字节范围引用。调用方管理输出目录的生命周期。 */
    public static HlsOutput transcodeHls(
            String source,
            Path outputDirectory,
            PlaybackQuality quality,
            Options options,
            HlsTimeouts timeouts) throws IOException {
        if (options.ffmpegThreads < 1) {
            throw new IllegalArgumentException("转码线程数必须有效");
        }

        Files.createDirectories(outputDirectory);
        Path playlist = outputDirectory.resolve(HlsPlaylist.PLAYLIST_FILENAME);
        Path media = outputDirectory.resolve(HlsPlaylist.MEDIA_FILENAME);
        List<String> command = new ArrayList<>(List.of(
                options.ffmpegCommand, "-nostdin", "-y", "-nostats", "-progress", "pipe:1", "-stats_period", "1",
                "-i", source));
        command.addAll(hlsEncodingArguments(quality, options.ffmpegThreads));
        command.addAll(List.of("-hls_segment_filename", media.toString(), playlist.toString()));
        CommandResult result = runCommand(command, timeouts.total(), new HlsProgress(timeouts, System.nanoTime()));
        if (result.exitCode != 0) {
            throw new IllegalStateException("HLS 转码失败: " + tail(result.output));
        }

        return new HlsOutput(playlist, media);
    }

    /**
     * 按 options.sampleCount 个时间点抽取候选帧并评分。
     * 多路输入单进程一次抽取, 缺帧的点单点重试 1 次; 返回成功评分的候选(可能为空, 调用方据实兜底)。
     */
    public static List<FrameScore> extractAndScoreCandidates(String source, Path outputDir, Options options) {
        Double durationSec = probeDurationSeconds(source, options);
        if (durationSec == null || durationSec <= 0.1) {
            log.warn("探测视频时长失败");
            return List.of();
        }
        List<Double> sampleSeconds = buildSampleSeconds(durationSec, options.sampleCount,
                options.sampleStartRatio, options.sampleEndRatio);
        extractAll(source, sampleSeconds, outputDir, options);

        List<FrameScore> scored = new ArrayList<>();
        for (int i = 0; i < sampleSeconds.size(); i++) {
            Path framePath = outputDir.resolve(candidateName(i));
            FrameScore frame = isReadableFile(framePath) ? scoreFrame(framePath, sampleSeconds.get(i)) : null;
            if (frame == null) {
                try {
                    Files.deleteIfExists(framePath);
                } catch (IOException e) {
                    throw new IllegalStateException("清理失败采样帧失败", e);
                }
                if (extractFrameAt(source, sampleSeconds.get(i), framePath, options.candidateMaxLongEdge, options))
                    frame = scoreFrame(framePath, sampleSeconds.get(i));
            }
            if (frame != null) scored.add(frame);
        }
        return scored;
    }

    /**
     * 在指定时间点抽取单帧, 分辨率按长边 maxLongEdge 钳制。
     * 用于选定候选后高清重抽成品, 或多路抽取的缺帧补抽。
     */
    public static boolean extractFrameAt(
            String source,
            double second,
            Path frameOut,
            int maxLongEdge,
            Options options) {
        String sec = String.format(Locale.US, "%.3f", second);
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegCommand);
        cmd.add("-y");
        cmd.add("-ss");
        cmd.add(sec);
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-threads");
        cmd.add(String.valueOf(Math.max(1, options.ffmpegThreads)));
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-frames:v");
        cmd.add("1");
        cmd.add("-vf");
        cmd.add(longEdgeScaleExpr(maxLongEdge));
        cmd.add("-q:v");
        cmd.add(String.valueOf(options.jpegQuality));
        cmd.add(frameOut.toString());
        CommandResult result = runCommand(cmd, options.commandTimeout);
        requireExtractionAvailable(result);
        if (result.exitCode != 0) {
            log.warn("抽帧失败 t={}s: {}", sec, tail(result.output));
            return false;
        }
        return isReadableFile(frameOut);
    }

    /** 预筛: 明显废帧(过暗/过曝/近纯色)返回 false, 不送 LLM 决胜 */
    public static boolean passesPrefilter(FrameScore frame, Options options) {
        return frame.meanBrightness() >= options.prefilterMinBrightness
                && frame.meanBrightness() <= options.prefilterMaxBrightness
                && frame.contrastStd() >= options.prefilterMinContrast;
    }

    /**
     * 长边钳制缩放: 横屏宽<=max, 竖屏高<=max, 短边按比例, 源更小不放大。
     * filter graph 内逗号是滤镜分隔符, 表达式里的逗号必须转义。
     */
    private static String longEdgeScaleExpr(int maxLongEdge) {
        String w = "if(gt(iw\\,ih)\\,min(" + maxLongEdge + "\\,iw)\\,-2)";
        String h = "if(gt(iw\\,ih)\\,-2\\,min(" + maxLongEdge + "\\,ih))";
        return "scale=" + w + ":" + h;
    }

    /**
     * 多路输入单进程抽帧: 每个时间点一路 -ss 输入, 各 map 出 1 帧。
     * 任一路失败不影响其它路落盘, 缺帧由调用方盘点后单点重试。
     */
    private static void extractAll(String source, List<Double> seconds, Path outputDir, Options options) {
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegCommand);
        cmd.add("-y");
        for (int i = 0; i < seconds.size(); i++) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", seconds.get(i)));
            cmd.add("-threads");
            cmd.add(String.valueOf(Math.max(1, options.ffmpegThreads)));
            cmd.add("-i");
            cmd.add(source);
        }
        for (int i = 0; i < seconds.size(); i++) {
            cmd.add("-map");
            cmd.add(i + ":v");
            cmd.add("-frames:v");
            cmd.add("1");
            cmd.add("-vf");
            cmd.add(longEdgeScaleExpr(options.candidateMaxLongEdge));
            cmd.add("-q:v");
            cmd.add(String.valueOf(options.jpegQuality));
            cmd.add(outputDir.resolve(candidateName(i)).toString());
        }
        CommandResult result = runCommand(cmd, options.commandTimeout);
        requireExtractionAvailable(result);
        if (result.exitCode != 0) {
            log.warn("多路抽帧整体非零退出, 以落盘文件为准盘点: {}", tail(result.output));
        }
    }

    private static String candidateName(int index) {
        return "cover-candidate-" + index + ".jpg";
    }

    public static int candidateIndex(FrameScore frame) {
        String name = frame.framePath().getFileName().toString();
        if (!name.matches("cover-candidate-[0-9]+\\.jpg")) throw new IllegalArgumentException("Invalid sampled frame path");
        return Integer.parseInt(name.substring("cover-candidate-".length(), name.length() - 4));
    }

    public static boolean extractionUnavailable(CommandOutput result) {
        String output = result.output().toLowerCase(Locale.ROOT);
        return result.exitCode() < 0 || result.exitCode() != 0 && List.of("error opening input", "error opening output",
                "no such file or directory", "permission denied", "input/output error", "server returned",
                "connection refused",
                "connection timed out",
                "network is unreachable",
                "invalid data found when processing input")
                .stream().anyMatch(output::contains);
    }

    private static void requireExtractionAvailable(CommandResult result) {
        if (extractionUnavailable(new CommandOutput(result.exitCode(), result.output())))
            throw new IllegalStateException("视频抽帧进程或源输入不可用: exitCode=" + result.exitCode());
    }

    private static boolean isReadableFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Double> buildSampleSeconds(
            double durationSec,
            int sampleCount,
            double startRatio,
            double endRatio) {
        int count = Math.max(1, sampleCount);
        if (count == 1) return List.of(durationSec * (startRatio + endRatio) / 2);
        List<Double> seconds = new ArrayList<>(count);
        double start = durationSec * startRatio;
        double end = durationSec * endRatio;
        if (end <= start) {
            end = Math.max(start + 0.5, durationSec * 0.95);
        }
        for (int i = 0; i < count; i++) {
            double p = i / (double) (count - 1);
            seconds.add(start + (end - start) * p);
        }
        return seconds;
    }

    private static Double probeDurationSeconds(String source, Options options) {
        List<String> cmd = List.of(
                options.ffprobeCommand,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source
        );
        CommandResult result = runCommand(cmd, options.commandTimeout);
        if (result.exitCode != 0) {
            log.warn("ffprobe 探测失败: {}", tail(result.output));
            return null;
        }
        String text = result.output == null ? "" : result.output.trim();
        if (text.isEmpty()) {
            return null;
        }

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            log.warn("ffprobe 输出不可解析为时长: {}", tail(text));
            return null;
        }
    }

    public static FrameScore scoreFrame(Path framePath, double second) {
        BufferedImage image = readScoringImage(framePath);
        if (image == null) {
            return null;
        }

        Grayscale grayscale = toGrayscale(image);
        double mean = grayscale.mean();
        double std = standardDeviation(grayscale.pixels(), mean);
        double sharpnessScore = normalize(laplacianVariance(grayscale.pixels()), 20, 900);
        double brightnessScore = 1.0 - Math.min(1.0, Math.abs(mean - 128.0) / 128.0);
        double contrastScore = normalize(std, 20, 80);

        double total = 0.55 * sharpnessScore
                + 0.30 * brightnessScore
                + 0.15 * contrastScore;

        image.flush();
        return new FrameScore(framePath, second, sharpnessScore, brightnessScore, contrastScore, mean, std, total);
    }

    private static BufferedImage readScoringImage(Path framePath) {
        BufferedImage image;
        try {
            image = ImageIO.read(framePath.toFile());
        } catch (IOException e) {
            return null;
        }
        if (image == null || image.getWidth() < 3 || image.getHeight() < 3) {
            return null;
        }
        return image;
    }

    private record Grayscale(double[][] pixels, double mean) {
    }

    private static Grayscale toGrayscale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[][] gray = new double[height][width];
        // 整行取像素，避免逐点 getRGB 的边界开销。
        int[] row = new int[width];
        double sum = 0;
        for (int y = 0; y < height; y++) {
            row = image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x];
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                double value = 0.299 * red + 0.587 * green + 0.114 * blue;
                gray[y][x] = value;
                sum += value;
            }
        }
        return new Grayscale(gray, sum / (width * height));
    }

    private static double standardDeviation(double[][] gray, double mean) {
        int height = gray.length;
        int width = gray[0].length;
        double variance = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double difference = gray[y][x] - mean;
                variance += difference * difference;
            }
        }
        variance /= (width * height);
        return Math.sqrt(variance);
    }

    private static double laplacianVariance(double[][] gray) {
        int height = gray.length;
        int width = gray[0].length;
        // 近似拉普拉斯（4 邻域），方差越大通常越清晰。
        double lapSum = 0;
        double lapSqSum = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double lap = 4 * gray[y][x] - gray[y - 1][x] - gray[y + 1][x] - gray[y][x - 1] - gray[y][x + 1];
                lapSum += lap;
                lapSqSum += lap * lap;
                count++;
            }
        }
        double lapMean = lapSum / Math.max(1, count);
        return (lapSqSum / Math.max(1, count)) - lapMean * lapMean;
    }

    private static double normalize(double v, double low, double high) {
        if (v <= low) {
            return 0.0;
        }

        if (v >= high) {
            return 1.0;
        }

        return (v - low) / (high - low);
    }

    private static CommandResult runCommand(List<String> command, Duration timeout) {
        return runCommand(command, timeout, null);
    }

    private static CommandResult runCommand(List<String> command, Duration timeout, HlsProgress progress) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = builder.start();
            Thread reader = startOutputReader(process, output, progress);
            boolean finished = waitForProcess(process, timeout, progress, reader);
            if (!finished) {
                stopProcess(process);
                synchronized (output) {
                    output.append("process timeout");
                }
                return new CommandResult(-1, output.toString());
            }

            reader.join(1000);
            return new CommandResult(process.exitValue(), output.toString());
        } catch (HlsTimeoutException e) {
            throw e;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, output + e.getMessage());
        }
    }

    private static Thread startOutputReader(Process process, StringBuilder out, HlsProgress progress) {
        // 独立线程消费输出，避免阻塞读使主线程的超时检测失效。
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (progress != null) progress.accept(line, System.nanoTime());
                    synchronized (out) {
                        out.append(line.replaceAll("https?://[^\\s\"<>]+", "[redacted-url]")).append('\n');
                        if (out.length() > 262144) out.delete(0, out.length() - 262144);
                    }
                }
            } catch (IOException ignored) {
                // 进程被销毁时流关闭, 属预期
            }
        });
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static boolean waitForProcess(Process process, Duration timeout, HlsProgress progress, Thread reader)
            throws InterruptedException {
        if (progress == null) {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        while (process.isAlive()) {
            long now = System.nanoTime();
            HlsTimeoutReason reason = progress.timeoutReason(now);
            if (reason != null) {
                stopProcess(process);
                reader.join(1000);
                throw new HlsTimeoutException(reason);
            }
            process.waitFor(progress.waitNanos(now), TimeUnit.NANOSECONDS);
        }
        return true;
    }

    private static void stopProcess(Process process) throws InterruptedException {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }

        String[] lines = output.strip().split("\n");
        return lines.length <= 3 ? output.strip() : String.join("\n", lines[lines.length - 3]);
    }

    public static CommandOutput runBoundedCommand(List<String> command, Duration timeout) {
        FFmpegUtil.CommandResult result = runCommand(command, timeout);
        return new CommandOutput(result.exitCode(), result.output());
    }

    public record CommandOutput(int exitCode, String output) {
    }

    private record CommandResult(int exitCode, String output) {
    }

}
