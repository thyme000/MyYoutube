package cn.diaojiahong.myyoutube.common.mq.message;

public record ContentVectorMessage(Long contentId, String targetVersion, String failureReason) {
    public ContentVectorMessage(Long contentId) {
        this(contentId, null, null);
    }
}
