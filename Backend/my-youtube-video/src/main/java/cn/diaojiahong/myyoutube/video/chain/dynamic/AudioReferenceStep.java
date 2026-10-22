package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import org.springframework.stereotype.Component;

@Component
public class AudioReferenceStep extends DynamicPreparationStep {

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
    public void apply(DynamicPreparationContext ctx) {
        if (ctx.getDto().getAudioId() == null) {
            ctx.setAudio(null, null, null, 0);
            return;
        }
        var audio = audioService.getReferenceable(ctx.getDto().getAudioId(), ctx.getUserId());
        if (audio == null || audio.getDurationMs() == null || audio.getDurationMs() <= 0
                || audio.getObjectKey() == null || !audio.getObjectKey().startsWith("uploads/audio/"))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频素材无效");
        ctx.setAudio(audio.getId(), audio.getObjectKey(), audio.getTitle(), audio.getDurationMs());
    }
}
