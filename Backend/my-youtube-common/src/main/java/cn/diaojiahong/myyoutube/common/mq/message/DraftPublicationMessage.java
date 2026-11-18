package cn.diaojiahong.myyoutube.common.mq.message;

public record DraftPublicationMessage(Long draftId, Integer acceptedRevision) {
    public static final String TOPIC = "draft_publication_topic";
}
