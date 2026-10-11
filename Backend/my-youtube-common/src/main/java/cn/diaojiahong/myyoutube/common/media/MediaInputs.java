package cn.diaojiahong.myyoutube.common.media;

public final class MediaInputs {
    private MediaInputs() {}

    public static void objectKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains(":") || key.contains("?")
                || java.util.Arrays.stream(key.split("/", -1)).anyMatch(s -> s.isBlank() || s.equals(".") || s.equals("..")))
            throw new IllegalArgumentException("必须使用原始对象 key");
    }

    public static void validate(ContentMedia media, String cover) {
        if (media == null || media.durationMs() <= 0) throw new IllegalArgumentException("媒体时长无效");
        if (media.video() != null) { objectKey(media.video().objectKey()); return; }
        var dynamic = media.dynamic();
        if (dynamic.items().isEmpty() || dynamic.items().size() > 18
                || !("vertical".equals(dynamic.canvasType()) || "horizontal".equals(dynamic.canvasType()))
                || !("cut".equals(dynamic.transition()) || "fade".equals(dynamic.transition()))
                || dynamic.canvasW() <= 0 || dynamic.canvasH() <= 0
                || "cut".equals(dynamic.transition()) && dynamic.fadeMs() != 0
                || "fade".equals(dynamic.transition()) && dynamic.fadeMs() <= 0)
            throw new IllegalArgumentException("图集媒体不完整");
        var schedule = DynamicTimeline.schedule(dynamic.items().stream().map(DynamicMedia.Image::durationMs).toList(), dynamic.fadeMs());
        if (schedule.exportDurationMs() != dynamic.exportDurationMs()) throw new IllegalArgumentException("图集时间轴不一致");
        for (int i = 0; i < dynamic.items().size(); i++) {
            var item = dynamic.items().get(i);
            objectKey(item.imageObjectKey());
            if (item.startMs() != schedule.startsMs().get(i) || !Double.isFinite(item.focusX()) || !Double.isFinite(item.focusY())
                    || item.focusX() < 0 || item.focusX() > 1 || item.focusY() < 0 || item.focusY() > 1)
                throw new IllegalArgumentException("图片时间或焦点无效");
        }
        if (cover == null || dynamic.items().stream().noneMatch(i -> cover.equals(i.imageObjectKey())))
            throw new IllegalArgumentException("封面必须属于当前图集");
        if (dynamic.audio() != null) {
            objectKey(dynamic.audio().objectKey());
            if (dynamic.audio().id() == null || dynamic.audio().durationMs() <= 0)
                throw new IllegalArgumentException("配乐不完整");
        }
    }
}
