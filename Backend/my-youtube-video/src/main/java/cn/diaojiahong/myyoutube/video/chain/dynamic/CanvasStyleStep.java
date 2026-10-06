package cn.diaojiahong.myyoutube.video.chain.dynamic;

import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.FADE_DURATION_MS;

@Component
public class CanvasStyleStep extends DynamicPublishStep {

    public CanvasStyleStep() {
        super(20);
    }

    /** 画布与转场派生: 未知取值归一到 vertical/fade, 后续步骤与装配共用 */
    @Override
    public void apply(DynamicPublishContext ctx) {
        String canvasType = "horizontal".equals(ctx.getDto().getCanvasType()) ? "horizontal" : "vertical";
        boolean isFade = !"cut".equals(ctx.getDto().getTransition());
        ctx.setCanvasStyle(canvasType,
                "horizontal".equals(canvasType) ? 1920 : 1080,
                "horizontal".equals(canvasType) ? 1080 : 1920,
                isFade ? "fade" : "cut",
                isFade ? FADE_DURATION_MS : 0L);
    }
}
