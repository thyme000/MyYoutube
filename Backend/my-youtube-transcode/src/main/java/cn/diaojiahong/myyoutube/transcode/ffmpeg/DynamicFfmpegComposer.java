package cn.diaojiahong.myyoutube.transcode.ffmpeg;

import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeItemDTO;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import lombok.extern.slf4j.Slf4j;

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

/**
 * 动态成片合成器: 单张图逐段缩放裁剪(focus 焦点对齐)后, 按 cut/fade 拼接并铺音频。
 * 切换点公式与预览端一致: 第 k 次切换发生在 item[k].startMs(= sum(d_0..d_{k-1}) - k*fade)。
 */
@Slf4j
public final class DynamicFfmpegComposer {

    private DynamicFfmpegComposer() {
    }

    public static void compose(TranscodeSubmitDTO params, List<Path> imagePaths, Path audioPath,
                               Path workDir, Path outputMp4, String ffmpegCommand) throws IOException, InterruptedException {
        if (params == null || params.getItems() == null || imagePaths.size() != params.getItems().size()) {
            throw new IllegalArgumentException("参数与图片数量不一致");
        }
        String ff = ffmpegCommand == null || ffmpegCommand.isBlank() ? "ffmpeg" : ffmpegCommand;
        Files.createDirectories(workDir);
        int n = imagePaths.size();
        int w = params.getCanvasW();
        int h = params.getCanvasH();
        boolean isFade = "fade".equals(params.getTransition());
        double fadeSec = isFade ? Math.max(1, params.getFadeMs() == null ? 800 : params.getFadeMs()) / 1000.0 : 0.0;
        double totalSec = (params.getTotalDurationMs() == null ? 0 : params.getTotalDurationMs()) / 1000.0;
        if (totalSec <= 0) {
            throw new IllegalArgumentException("成片总时长无效");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ff);
        cmd.add("-y");
        for (int i = 0; i < n; i++) {
            cmd.add("-loop");
            cmd.add("1");
            cmd.add("-t");
            cmd.add(sec(params.getItems().get(i).getDurationMs()));
            cmd.add("-i");
            cmd.add(imagePaths.get(i).toAbsolutePath().toString());
        }
        boolean hasAudio = audioPath != null && Files.exists(audioPath);
        int audioIndex = n;
        if (hasAudio) {
            cmd.add("-i");
            cmd.add(audioPath.toAbsolutePath().toString());
        } else {
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-t");
            cmd.add(sec((long) (totalSec * 1000)));
            cmd.add("-i");
            cmd.add("anullsrc=r=48000:cl=stereo");
        }

        List<String> chains = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TranscodeItemDTO item = params.getItems().get(i);
            double fx = item.getFocusX() == null ? 0.5 : Math.max(0, Math.min(1, item.getFocusX()));
            double fy = item.getFocusY() == null ? 0.5 : Math.max(0, Math.min(1, item.getFocusY()));
            chains.add("[" + i + ":v]"
                    + "scale=" + w + ':' + h + ":force_original_aspect_ratio=increase,"
                    + "crop=" + w + ':' + h
                    + ":(iw-ow)*" + ratio(fx)
                    + ":(ih-oh)*" + ratio(fy)
                    + ",setsar=1,fps=30,format=yuv420p[v" + i + ']');
        }
        String finalLabel;
        if (n == 1) {
            finalLabel = "v0";
        } else if (isFade) {
            StringBuilder sb = new StringBuilder();
            String prev = "v0";
            for (int k = 1; k < n; k++) {
                // offset = 本次切换点(秒), 即第 k 项的 startMs
                double offset = (params.getItems().get(k).getStartMs() == null ? 0 : params.getItems().get(k).getStartMs()) / 1000.0;
                String out = "x" + k;
                if (k > 1) {
                    sb.append(';');
                }
                sb.append('[').append(prev).append("][v").append(k).append(']')
                        .append("xfade=transition=fade:duration=").append(String.format(Locale.US, "%.3f", fadeSec))
                        .append(":offset=").append(String.format(Locale.US, "%.3f", offset))
                        .append('[').append(out).append(']');
                prev = out;
            }
            chains.add(sb.toString());
            finalLabel = prev;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append("[v").append(i).append(']');
            }
            sb.append("concat=n=").append(n).append(":v=1:a=0[xcat]");
            chains.add(sb.toString());
            finalLabel = "xcat";
        }

        // 音频: 不足补静音, 超长在结尾 2s 淡出后截断
        String st = String.format(Locale.US, "%.3f", Math.max(0, totalSec - 2));
        String d = String.format(Locale.US, "%.3f", Math.min(2, totalSec / 2));
        chains.add("[" + audioIndex + ":a]"
                + "aresample=48000,apad,afade=t=out:st=" + st + ":d=" + d
                + ",atrim=0:" + String.format(Locale.US, "%.3f", totalSec) + "[aud]");

        String fc = String.join(";", chains);
        cmd.add("-filter_complex");
        cmd.add(fc);
        cmd.add("-map");
        cmd.add("[" + finalLabel + "]");
        cmd.add("-map");
        cmd.add("[aud]");
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-crf");
        cmd.add("23");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add("-t");
        cmd.add(String.format(Locale.US, "%.3f", totalSec));
        cmd.add(outputMp4.toAbsolutePath().toString());

        runFfmpeg(cmd, workDir, Duration.ofMinutes(15));
        if (!Files.exists(outputMp4) || Files.size(outputMp4) < 100) {
            throw new IOException("成片输出无效: " + outputMp4);
        }
    }

    private static String sec(Long ms) {
        double v = (ms == null ? 0 : ms) / 1000.0;
        return String.format(Locale.US, "%.3f", Math.max(0.04, v));
    }

    private static String ratio(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    private static void runFfmpeg(List<String> cmd, Path workDir, Duration timeout) throws IOException, InterruptedException {
        log.info("动态成片 ffmpeg 命令: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        StringBuilder out = new StringBuilder();
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        boolean ok = p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 超时");
        }
        if (p.exitValue() != 0) {
            log.warn("ffmpeg 非零退出: code={} out={}", p.exitValue(), out);
            throw new IOException("ffmpeg 失败 exit=" + p.exitValue() + ": " + out);
        }
    }
}
