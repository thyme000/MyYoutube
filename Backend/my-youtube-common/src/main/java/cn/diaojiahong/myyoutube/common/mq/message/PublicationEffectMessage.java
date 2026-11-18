package cn.diaojiahong.myyoutube.common.mq.message;
public record PublicationEffectMessage(Long eventId, PublicationBranch branch) {
    public static final String TOPIC = "publication_effect_topic";
}
