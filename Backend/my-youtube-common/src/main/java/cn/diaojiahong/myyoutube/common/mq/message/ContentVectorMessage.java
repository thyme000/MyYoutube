package cn.diaojiahong.myyoutube.common.mq.message;

public record ContentVectorMessage(Long contentId, String targetVersion, String failureReason) {
    public static final String TOPIC = "content_vector_topic";
    public static final String GROUP = "search_content_vector";

    public ContentVectorMessage(Long contentId) {
        this(contentId, null, null);
    }
}
