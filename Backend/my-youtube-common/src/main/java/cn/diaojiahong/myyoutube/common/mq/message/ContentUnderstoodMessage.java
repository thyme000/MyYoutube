package cn.diaojiahong.myyoutube.common.mq.message;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Manifest;
public record ContentUnderstoodMessage(Manifest manifest) {
}
