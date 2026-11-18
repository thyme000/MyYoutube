package cn.diaojiahong.myyoutube.common.mq.message;
public record ReviewRequestedMessage(Long pipelineId) {
    public static final String TOPIC = "review_requested_topic";
}
