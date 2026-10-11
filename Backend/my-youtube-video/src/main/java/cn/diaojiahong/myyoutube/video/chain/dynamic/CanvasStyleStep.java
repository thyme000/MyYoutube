package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPreparationRules.FADE_DURATION_MS;

@Component
public class CanvasStyleStep extends DynamicPreparationStep {

    public CanvasStyleStep() {
        super(20);
    }

    @Override
    public void apply(DynamicPreparationContext ctx) {
        String canvas = ctx.getDto().getCanvasType(), transition = ctx.getDto().getTransition();
        if (canvas != null && !"horizontal".equals(canvas) && !"vertical".equals(canvas))
            throw new BizException(BizException.BIZ_ERROR, "画布类型无效");
        if (transition != null && !"cut".equals(transition) && !"fade".equals(transition))
            throw new BizException(BizException.BIZ_ERROR, "转场类型无效");
        String canvasType = "horizontal".equals(ctx.getDto().getCanvasType()) ? "horizontal" : "vertical";
        boolean isFade = !"cut".equals(ctx.getDto().getTransition());
        ctx.setCanvasStyle(canvasType,
                "horizontal".equals(canvasType) ? 1920 : 1080,
                "horizontal".equals(canvasType) ? 1080 : 1920,
                isFade ? "fade" : "cut",
                isFade ? FADE_DURATION_MS : 0L);
    }
}
