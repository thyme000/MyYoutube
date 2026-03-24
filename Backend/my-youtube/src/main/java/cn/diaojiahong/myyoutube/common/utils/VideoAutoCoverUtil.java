package cn.diaojiahong.myyoutube.common.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 视频自动封面工具：多点抽帧 -> 按图像质量评分 -> 选最佳帧；失败时回退默认图。
 *
 * <p>说明：
 * <ul>
 *   <li>需要系统安装 ffmpeg/ffprobe（可通过构造参数改命令名）。</li>
 *   <li>评分当前采用清晰度（拉普拉斯近似）+ 亮度 + 对比度。</li>
 *   <li>预留 faceScore 字段，后续可接 OpenCV 人脸检测扩展。</li>
 * </ul>
 */
@Slf4j
public final class VideoAutoCoverUtil {

    private VideoAutoCoverUtil() {
    }

    /** OSS 默认视频封面（抽帧失败时可直接回退该 URL） */
    public static final String DEFAULT_FALLBACK_COVER_URL =
            "https://thyme.oss-ap-southeast-1.aliyuncs.com/default-video-cover/%E9%BB%98%E8%AE%A4%E8%A7%86%E9%A2%91%E5%B0%81%E9%9D%A2.jpg";

    public static final class Options {
        /** ffmpeg 命令名或绝对路径 */
        public String ffmpegCommand = "ffmpeg";
        /** ffprobe 命令名或绝对路径 */
        public String ffprobeCommand = "ffprobe";
        /** 候选帧数量（建议 8~12） */
        public int sampleCount = 10;
        /** 采样起始比例（跳过片头） */
        public double sampleStartRatio = 0.10;
        /** 采样结束比例（跳过片尾） */
        public double sampleEndRatio = 0.90;
        /** 单次外部命令超时 */
        public Duration commandTimeout = Duration.ofSeconds(30);
        /** ffmpeg 输出 jpg 质量（1 最好，31 最差） */
        public int jpegQuality = 2;
        /** 默认封面本地路径（失败回退，可为空） */
        public Path fallbackCoverPath;
        /** 默认封面 URL（适配 OSS 场景；未设置时使用 {@link #DEFAULT_FALLBACK_COVER_URL}） */
        public String fallbackCoverUrl = DEFAULT_FALLBACK_COVER_URL;
    }

    public record FrameScore(
            Path framePath,
            double second,
            double sharpnessScore,
            double brightnessScore,
            double contrastScore,
            double faceScore,
            double totalScore
    ) {
    }

    public record Result(
            Path selectedCoverPath,
            String selectedCoverUrl,
            boolean usedFallback,
            String reason,
            List<FrameScore> candidates
    ) {
    }

    /**
     * 自动生成最佳封面。
     *
     * @param videoPath  视频文件路径
     * @param outputDir  输出目录（会自动创建）
     * @param outputName 输出文件名（建议 .jpg）
     * @param options    工具参数
     */
    public static Result generateBestCover(Path videoPath, Path outputDir, String outputName, Options options) {
        if (videoPath == null || outputDir == null || outputName == null || options == null) {
            throw new IllegalArgumentException("videoPath/outputDir/outputName/options 不能为空");
        }
        if (!Files.exists(videoPath)) {
            return fallback(outputDir, outputName, options, "视频文件不存在: " + videoPath);
        }
        if (options.sampleCount < 2) {
            options.sampleCount = 2;
        }
        if (options.sampleStartRatio < 0 || options.sampleStartRatio >= 1) {
            options.sampleStartRatio = 0.10;
        }
        if (options.sampleEndRatio <= 0 || options.sampleEndRatio > 1 || options.sampleEndRatio <= options.sampleStartRatio) {
            options.sampleEndRatio = 0.90;
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            return fallback(outputDir, outputName, options, "创建输出目录失败: " + e.getMessage());
        }

        Double durationSec = probeDurationSeconds(videoPath, options);
        if (durationSec == null || durationSec <= 0.1) {
            return fallback(outputDir, outputName, options, "探测视频时长失败");
        }

        List<Double> sampleSeconds = buildSampleSeconds(durationSec, options.sampleCount, options.sampleStartRatio, options.sampleEndRatio);
        List<FrameScore> scored = new ArrayList<>();
        for (int i = 0; i < sampleSeconds.size(); i++) {
            double sec = sampleSeconds.get(i);
            Path framePath = outputDir.resolve("cover-candidate-" + i + "-" + UUID.randomUUID() + ".jpg");
            boolean ok = extractFrame(videoPath, sec, framePath, options);
            if (!ok) {
                continue;
            }
            FrameScore fs = scoreFrame(framePath, sec);
            if (fs != null) {
                scored.add(fs);
            }
        }

        if (scored.isEmpty()) {
            return fallback(outputDir, outputName, options, "候选帧为空，可能抽帧失败");
        }

        FrameScore best = scored.stream().max(Comparator.comparingDouble(FrameScore::totalScore)).orElse(null);
        if (best == null) {
            return fallback(outputDir, outputName, options, "候选帧评分失败");
        }

        Path finalCover = outputDir.resolve(outputName);
        try {
            Files.copy(best.framePath(), finalCover, StandardCopyOption.REPLACE_EXISTING);
            cleanupCandidates(scored, best.framePath());
        } catch (IOException e) {
            return fallback(outputDir, outputName, options, "写入最终封面失败: " + e.getMessage());
        }
        return new Result(finalCover, null, false, "ok", scored);
    }

    private static Result fallback(Path outputDir, String outputName, Options options, String reason) {
        Path fallback = options.fallbackCoverPath;
        if (fallback != null && Files.exists(fallback)) {
            Path target = outputDir.resolve(outputName);
            try {
                Files.createDirectories(outputDir);
                Files.copy(fallback, target, StandardCopyOption.REPLACE_EXISTING);
                return new Result(target, null, true, reason, List.of());
            } catch (IOException e) {
                log.warn("自动封面失败且默认图复制失败: {}, err={}", reason, e.getMessage());
                return new Result(null, resolveFallbackCoverUrl(options), true, reason + "；默认图复制失败: " + e.getMessage(), List.of());
            }
        }
        String fallbackUrl = resolveFallbackCoverUrl(options);
        log.warn("自动封面失败，回退默认封面URL: {}, reason={}", fallbackUrl, reason);
        return new Result(null, fallbackUrl, true, reason, List.of());
    }

    private static String resolveFallbackCoverUrl(Options options) {
        if (options == null || options.fallbackCoverUrl == null || options.fallbackCoverUrl.isBlank()) {
            return DEFAULT_FALLBACK_COVER_URL;
        }
        return options.fallbackCoverUrl;
    }

    private static void cleanupCandidates(List<FrameScore> candidates, Path keepPath) {
        for (FrameScore c : candidates) {
            if (c.framePath() == null || c.framePath().equals(keepPath)) {
                continue;
            }
            try {
                Files.deleteIfExists(c.framePath());
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static List<Double> buildSampleSeconds(double durationSec, int sampleCount, double startRatio, double endRatio) {
        List<Double> seconds = new ArrayList<>(sampleCount);
        double start = durationSec * startRatio;
        double end = durationSec * endRatio;
        if (end <= start) {
            end = Math.max(start + 0.5, durationSec * 0.95);
        }
        if (sampleCount == 1) {
            seconds.add(start);
            return seconds;
        }
        for (int i = 0; i < sampleCount; i++) {
            double p = i / (double) (sampleCount - 1);
            seconds.add(start + (end - start) * p);
        }
        return seconds;
    }

    private static Double probeDurationSeconds(Path videoPath, Options options) {
        List<String> cmd = List.of(
                options.ffprobeCommand,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath.toString()
        );
        CommandResult result = runCommand(cmd, options.commandTimeout);
        if (result.exitCode != 0) {
            log.warn("ffprobe 探测失败: {}", result.output);
            return null;
        }
        String text = result.output == null ? "" : result.output.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            log.warn("ffprobe 输出不可解析为时长: {}", text);
            return null;
        }
    }

    private static boolean extractFrame(Path videoPath, double second, Path frameOut, Options options) {
        String sec = String.format(Locale.US, "%.3f", second);
        List<String> cmd = List.of(
                options.ffmpegCommand,
                "-y",
                "-ss", sec,
                "-i", videoPath.toString(),
                "-frames:v", "1",
                "-q:v", String.valueOf(options.jpegQuality),
                frameOut.toString()
        );
        CommandResult result = runCommand(cmd, options.commandTimeout);
        return result.exitCode == 0 && Files.exists(frameOut);
    }

    private static FrameScore scoreFrame(Path framePath, double second) {
        BufferedImage image;
        try {
            image = ImageIO.read(framePath.toFile());
        } catch (IOException e) {
            return null;
        }
        if (image == null || image.getWidth() < 3 || image.getHeight() < 3) {
            return null;
        }

        int w = image.getWidth();
        int h = image.getHeight();
        double[][] gray = new double[h][w];

        double sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                double gval = 0.299 * r + 0.587 * g + 0.114 * b;
                gray[y][x] = gval;
                sum += gval;
            }
        }
        double mean = sum / (w * h);

        double var = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = gray[y][x] - mean;
                var += d * d;
            }
        }
        var /= (w * h);
        double std = Math.sqrt(var);

        // 近似拉普拉斯（4邻域），方差越大通常越清晰
        double lapSum = 0;
        double lapSqSum = 0;
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                double lap = 4 * gray[y][x] - gray[y - 1][x] - gray[y + 1][x] - gray[y][x - 1] - gray[y][x + 1];
                lapSum += lap;
                lapSqSum += lap * lap;
                count++;
            }
        }
        double lapMean = lapSum / Math.max(1, count);
        double sharpVar = (lapSqSum / Math.max(1, count)) - lapMean * lapMean;

        double sharpnessScore = normalize(sharpVar, 20, 900);
        double brightnessScore = 1.0 - Math.min(1.0, Math.abs(mean - 128.0) / 128.0);
        double contrastScore = normalize(std, 20, 80);
        double faceScore = 0.0; // 预留：后续可接 OpenCV 人脸检测

        double total = 0.55 * sharpnessScore
                + 0.30 * brightnessScore
                + 0.15 * contrastScore
                + 0.00 * faceScore;

        return new FrameScore(framePath, second, sharpnessScore, brightnessScore, contrastScore, faceScore, total);
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
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        StringBuilder out = new StringBuilder();
        int code = -1;
        Process process = null;
        try {
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                out.append("process timeout");
                return new CommandResult(-1, out.toString());
            }
            code = process.exitValue();
            return new CommandResult(code, out.toString());
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, out + e.getMessage());
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}

