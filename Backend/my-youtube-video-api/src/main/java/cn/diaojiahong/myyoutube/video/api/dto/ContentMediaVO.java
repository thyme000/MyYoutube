package cn.diaojiahong.myyoutube.video.api.dto;

import java.util.List;

public record ContentMediaVO(String type, Video video, Dynamic dynamic) {
    /** 正式播放与审核预览均使用 HLS，各自的播放票不能跨用途使用。 */
    public record Video(long durationMs, VideoPlaybackVO playback) {
    }

    public record Dynamic(String canvasType, int canvasW, int canvasH, String transition, long fadeMs,
                          List<Image> items, Audio audio, long exportDurationMs) {
    }

    public record Image(int index, String url, long startMs, long durationMs, double focusX, double focusY) {
    }

    public record Audio(String id, String url, String title, long durationMs) {
    }
}
