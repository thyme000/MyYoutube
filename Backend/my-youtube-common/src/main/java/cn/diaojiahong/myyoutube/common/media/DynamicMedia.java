package cn.diaojiahong.myyoutube.common.media;

import java.util.List;

public record DynamicMedia(String canvasType, int canvasW, int canvasH, String transition, long fadeMs,
                           List<Image> items, Audio audio, long exportDurationMs) {
    public DynamicMedia {
        items = List.copyOf(items);
    }

    public record Image(String imageObjectKey, long startMs, long durationMs, double focusX, double focusY) {
    }

    public record Audio(Long id, String objectKey, String title, long durationMs) {
    }
}
