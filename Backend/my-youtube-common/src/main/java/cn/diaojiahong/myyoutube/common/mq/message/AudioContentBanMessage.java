package cn.diaojiahong.myyoutube.common.mq.message;

public record AudioContentBanMessage(Long contentId, Long audioId, long audioVersion) {
    public static final String TOPIC = "audio_content_ban_topic";
}
