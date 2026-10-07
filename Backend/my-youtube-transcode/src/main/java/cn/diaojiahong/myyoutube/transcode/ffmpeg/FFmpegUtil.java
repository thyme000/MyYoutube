package cn.diaojiahong.myyoutube.transcode.ffmpeg;

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
     * 按 options.sampleCount 个时间点抽取候选帧并评分。
     * 多路输入单进程一次抽取, 缺帧的点单点重试 1 次; 返回成功评分的候选(可能为空, 调用方据实兜底)。
     */
    public static List<FrameScore> extractAndScoreCandidates(String source, Path outputDir, Options options) {
        Double durationSec = probeDurationSeconds(source, options);
        if (durationSec == null || durationSec <= 0.1) {
            log.warn("探测视频时长失败: {}", source);
            return List.of();
        }
        List<Double> sampleSeconds = buildSampleSeconds(durationSec, options.sampleCount,
                options.sampleStartRatio, options.sampleEndRatio);
        extractAll(source, sampleSeconds, outputDir, options);

        List<FrameScore> scored = new ArrayList<>();
        for (int i = 0; i < sampleSeconds.size(); i++) {
            Path framePath = outputDir.resolve(candidateName(i));
            if (!isReadableFile(framePath)) {
                // 单点重试: 多路调用里个别 seek 失败不该连坐其它路
                extractFrameAt(source, sampleSeconds.get(i), framePath, options.candidateMaxLongEdge, options);
            }
            if (isReadableFile(framePath)) {
                FrameScore fs = scoreFrame(framePath, sampleSeconds.get(i));
                if (fs != null) {
                    scored.add(fs);
                }
            }
        }
        return scored;
    }

    /**
     * 在指定时间点抽取单帧, 分辨率按长边 maxLongEdge 钳制。
     * 用于选定候选后高清重抽成品, 或多路抽取的缺帧补抽。
     */
    public static boolean extractFrameAt(String source, double second, Path frameOut, int maxLongEdge, Options options) {
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
        if (result.exitCode != 0) {
            log.warn("多路抽帧整体非零退出, 以落盘文件为准盘点: {}", tail(result.output));
        }
    }

    private static String candidateName(int index) {
        return "cover-candidate-" + index + ".jpg";
    }

    private static boolean isReadableFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Double> buildSampleSeconds(double durationSec, int sampleCount, double startRatio, double endRatio) {
        int count = Math.max(2, sampleCount);
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
            log.warn("ffprobe 输出不可解析为时长: {}", text);
            return null;
        }
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
        // 整行取像素, 避免逐点 getRGB 的边界开销
        int[] row = new int[w];
        double[][] gray = new double[h][w];

        double sum = 0;
        for (int y = 0; y < h; y++) {
            row = image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
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

        // 近似拉普拉斯(4邻域), 方差越大通常越清晰
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

        double total = 0.55 * sharpnessScore
                + 0.30 * brightnessScore
                + 0.15 * contrastScore;

        return new FrameScore(framePath, second, sharpnessScore, brightnessScore, contrastScore, mean, std, total);
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
        Process process = null;
        try {
            process = pb.start();
            final Process proc = process;
            // 输出在独立线程消费: 网络输入可能长时间无输出, 阻塞读会令 waitFor 超时失效
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被销毁时流关闭, 属预期
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                synchronized (out) {
                    out.append("process timeout");
                }
                return new CommandResult(-1, out.toString());
            }
            reader.join(1000);
            return new CommandResult(process.exitValue(), out.toString());
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, out + e.getMessage());
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String[] lines = output.strip().split("\n");
        return lines.length <= 3 ? output.strip() : String.join("\n", lines[lines.length - 3]);
    }

    private record CommandResult(int exitCode, String output) {
    }

}
