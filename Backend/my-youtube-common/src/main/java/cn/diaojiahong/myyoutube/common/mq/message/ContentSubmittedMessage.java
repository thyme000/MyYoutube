package cn.diaojiahong.myyoutube.common.mq.message;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
public record ContentSubmittedMessage(Input input) {
    public static final String TOPIC = "content_submitted_topic";
}
