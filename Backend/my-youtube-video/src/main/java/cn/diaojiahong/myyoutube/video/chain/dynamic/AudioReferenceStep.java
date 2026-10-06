package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.AUDIO_MAX_DURATION_MS;

@Component
public class AudioReferenceStep extends DynamicPublishStep {

    private final IAudioService audioService;

    public AudioReferenceStep(IAudioService audioService) {
        super(30);
        this.audioService = audioService;
    }

    /**
     * 音频库引用校验: 公开或本人私有才可用; objectKey/时长/标题作为快照落库,
     * 之后音频转私密/删除/改名均不影响已发布动态(在先使用权)。
     */
    @Override
    public void apply(DynamicPublishContext ctx) {
        if (ctx.getDto().getAudioId() == null) {
            throw new BizException(BizException.BIZ_ERROR, "请选择背景音乐");
        }
        var audio = audioService.getReferenceable(ctx.getDto().getAudioId(), ctx.getUserId());
        long audioMs = audio.getDurationMs() != null && audio.getDurationMs() > 0
                ? Math.min(audio.getDurationMs(), AUDIO_MAX_DURATION_MS) : 0L;
        ctx.setAudio(audio.getId(), audio.getObjectKey(), audio.getTitle(), audioMs);
    }
}
