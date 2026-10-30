package cn.diaojiahong.myyoutube.common.mq.message;

public record DraftPublicationMessage(Long draftId, Integer acceptedRevision) {
}
