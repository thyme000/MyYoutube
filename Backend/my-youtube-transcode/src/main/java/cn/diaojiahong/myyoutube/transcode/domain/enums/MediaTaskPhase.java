package cn.diaojiahong.myyoutube.transcode.domain.enums;

public enum MediaTaskPhase {
    DRAFT, CONTENT, REVIEW;

    public boolean matches(String code) {
        return name().equals(code);
    }
}
