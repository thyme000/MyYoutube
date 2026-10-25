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

    @Override
    public void apply(DynamicPreparationContext ctx) {
        if (ctx.getDto().getAudioId() == null) {
            ctx.setAudio(null, null, null, 0);
            return;
        }
        var audio = audioService.getReferenceable(ctx.getDto().getAudioId(), ctx.getUserId());
        if (audio == null || audio.getDurationMs() == null || audio.getDurationMs() <= 0
                || audio.getObjectKey() == null || audio.getObjectKey().isBlank())
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频素材无效");
        ctx.setAudio(audio.getId(), audio.getObjectKey(), audio.getTitle(), audio.getDurationMs());
    }
}
