package cn.diaojiahong.myyoutube.common.mq.message;

public record AudioContentBanMessage(Long contentId, Long audioId, long audioVersion) {
}
