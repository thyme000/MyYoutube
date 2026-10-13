package cn.diaojiahong.myyoutube.transcode.domain.enums;

import java.util.List;
import java.util.Set;

public enum MediaTaskType {
    TRANSCODE, PREPARE, COVER, ASR, OCR, SUMMARY, TAG, REVIEW;
    public Set<String> getDependencies() {
        return switch (this) {
            case SUMMARY -> Set.of("ASR", "OCR");
            case TAG -> Set.of("SUMMARY");
            default -> Set.of();
        };
    }
    public static List<MediaTaskType> contentTypes() { return List.of(COVER, ASR, OCR, SUMMARY, TAG); }
    public static MediaTaskType fromCode(String code) { return valueOf(code); }
}
