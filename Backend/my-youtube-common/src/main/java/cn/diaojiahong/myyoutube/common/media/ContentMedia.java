package cn.diaojiahong.myyoutube.common.media;

public record ContentMedia(String type, VideoMedia video, DynamicMedia dynamic, PreparationContracts.Manifest preparation) {
    public ContentMedia(String type, VideoMedia video, DynamicMedia dynamic) { this(type, video, dynamic, null); }
    public ContentMedia {
        if (!("video".equals(type) && video != null && dynamic == null
                || "dynamic".equals(type) && dynamic != null && video == null))
            throw new IllegalArgumentException("媒体类型与载荷不一致");
    }

    public long durationMs() { return video != null ? video.durationMs() : dynamic.exportDurationMs(); }
}
