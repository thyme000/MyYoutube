package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.MAX_IMAGES;
import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.MIN_IMAGES;

@Component
public class ItemsCountStep extends DynamicPublishStep {

    public ItemsCountStep() {
        super(10);
    }

    @Override
    public void apply(DynamicPublishContext ctx) {
        if (ctx.getItems() == null || ctx.getItems().isEmpty()) {
            throw new BizException(BizException.BIZ_ERROR, "图片列表不能为空");
        }
        if (ctx.getItems().size() > MAX_IMAGES) {
            throw new BizException(BizException.BIZ_ERROR,
                    "图片数量需在 " + MIN_IMAGES + "~" + MAX_IMAGES + " 张之间");
        }
    }
}
