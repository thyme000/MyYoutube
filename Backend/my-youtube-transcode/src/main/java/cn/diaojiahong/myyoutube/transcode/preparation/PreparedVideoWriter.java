package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackQuality;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.PlaybackMetadata;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;

@Component
@RequiredArgsConstructor
public class PreparedVideoWriter {
    private final MediaPreparationProperties properties;

    public record Result(Path playlist, Path media, int width, int height, long bandwidth, PlaybackMetadata playback) {}

    public Result write(String source, Path directory, PlaybackQuality quality, long durationMs) throws IOException {
        FFmpegUtil.Options options = new FFmpegUtil.Options();
        options.ffmpegThreads = properties.playbackThreads();
        MediaPreparationProperties.Playback playback = properties.getPlayback();
        FFmpegUtil.HlsTimeouts timeouts = new FFmpegUtil.HlsTimeouts(
                playback.totalTimeout(durationMs), playback.getStartupTimeout(), playback.getStallTimeout());
        FFmpegUtil.HlsOutput output = FFmpegUtil.transcodeHls(
                source, directory, quality, options, timeouts);
        FFmpegUtil.HlsProbe probe = FFmpegUtil.probeHls(output.media().toString(), options);
        Bandwidth bandwidth = bandwidth(output.playlist());
        PlaybackMetadata metadata = new PlaybackMetadata(probe.codecs(), bandwidth.average(), probe.frameRate());
        return new Result(output.playlist(), output.media(), probe.width(), probe.height(), bandwidth.peak(), metadata);
    }

    private record Bandwidth(long peak, long average) {}

    /** 码率只统计媒体段，初始化段不计入；平均值按总字节数和总时长计算。 */
    private Bandwidth bandwidth(Path playlist) throws IOException {
        double duration = 0;
        long peak = 0;
        long totalBytes = 0;
        double totalDuration = 0;
        for (String line : Files.readAllLines(playlist)) {
            if (line.startsWith(HlsPlaylist.DURATION_PREFIX)) duration = Double.parseDouble(line.substring(HlsPlaylist.DURATION_PREFIX.length(), line.indexOf(',')));
            else if (line.startsWith(HlsPlaylist.BYTE_RANGE_PREFIX) && duration > 0) {
                long bytes = Long.parseLong(line.substring(HlsPlaylist.BYTE_RANGE_PREFIX.length(), line.indexOf('@')));
                peak = Math.max(peak, (long) Math.ceil(bytes * 8.0 / duration));
                totalBytes += bytes;
                totalDuration += duration;
                duration = 0;
            }
        }
        if (peak == 0) throw new IllegalStateException("HLS 目录缺少有效媒体段");
        return new Bandwidth(peak, (long) Math.ceil(totalBytes * 8.0 / totalDuration));
    }
}
