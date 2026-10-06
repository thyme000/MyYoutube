package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.transcode.ffmpeg.DynamicFfmpegComposer;
import org.springframework.stereotype.Component;

@Component
public class ComposeStep extends TranscodeStep {

    public ComposeStep() {
        super(30);
    }

    @Override
    protected void doApply(TranscodeContext ctx) throws Exception {
        DynamicFfmpegComposer.compose(ctx.getParams(), ctx.getImagePaths(),
                ctx.getAudioPath(), ctx.getWorkDir(), ctx.getOutput(), "ffmpeg");
    }
}
