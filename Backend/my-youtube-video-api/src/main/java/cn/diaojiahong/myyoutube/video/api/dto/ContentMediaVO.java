package cn.diaojiahong.myyoutube.video.api.dto;

import java.util.List;

public record ContentMediaVO(String type, Video video, Dynamic dynamic) {
    public record Video(String url, long durationMs) {}
    public record Dynamic(String canvasType, int canvasW, int canvasH, String transition, long fadeMs,
                          List<Image> items, Audio audio, long exportDurationMs) {}
    public record Image(int index, String url, long startMs, long durationMs, double focusX, double focusY) {}
    public record Audio(String id, String url, String title, long durationMs) {}
}
