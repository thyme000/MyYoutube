package cn.diaojiahong.myyoutube.content.cache;

public enum ContentResourceRegion {
    CONTENT_STATE("content-state", TtlGroup.STATE),
    AUDIO_STATE("audio-state", TtlGroup.STATE),
    CONTENT_MEDIA("content-media", TtlGroup.ARTIFACT),
    UNDERSTANDING("understanding", TtlGroup.ARTIFACT),
    PLAYLIST("playlist", TtlGroup.ARTIFACT),
    SUBTITLE("subtitle", TtlGroup.ARTIFACT);

    private final String code;
    private final TtlGroup ttlGroup;

    ContentResourceRegion(String code, TtlGroup ttlGroup) {
        this.code = code;
        this.ttlGroup = ttlGroup;
    }

    public String key(String resourceId) {
        return "content:resource:" + code + ":" + resourceId;
    }

    public TtlGroup ttlGroup() {
        return ttlGroup;
    }

    public enum TtlGroup { STATE, ARTIFACT }
}
