package cn.diaojiahong.myyoutube.common.media;

import java.util.List;
import java.util.Set;

public enum MediaTaskType {
    TRANSCODE, PREPARE, AUDIO_PREPARE, AUDIO_ASR, AUDIO_REVIEW, COVER, ASR, OCR, SUMMARY, TAG, REVIEW;
    public Set<String> getDependencies() {
        return switch (this) {
            case SUMMARY -> Set.of(ASR.name(), OCR.name());
            case TAG -> Set.of(SUMMARY.name());
            default -> Set.of();
        };
    }
    public static List<MediaTaskType> contentTypes() { return List.of(COVER, ASR, OCR, SUMMARY, TAG); }
    public boolean isAudio() { return this == AUDIO_PREPARE || this == AUDIO_ASR || this == AUDIO_REVIEW; }
    public boolean matches(String code) { return name().equals(code); }
    public static MediaTaskType fromCode(String code) { return valueOf(code); }
}
