package cn.diaojiahong.myyoutube.common.mq.message;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
public record ContentSubmittedMessage(Input input) {}
