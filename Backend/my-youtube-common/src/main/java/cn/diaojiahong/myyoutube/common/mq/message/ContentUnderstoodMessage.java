package cn.diaojiahong.myyoutube.common.mq.message;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Manifest;
public record ContentUnderstoodMessage(Manifest manifest) {
    public static final String TOPIC = "content_understood_topic";
}
