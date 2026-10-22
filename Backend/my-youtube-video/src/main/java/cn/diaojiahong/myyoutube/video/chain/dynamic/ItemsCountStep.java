package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPreparationRules.MAX_IMAGES;
import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPreparationRules.MIN_IMAGES;

@Component
public class ItemsCountStep extends DynamicPreparationStep {

    public ItemsCountStep() {
        super(10);
    }

    @Override
    public void apply(DynamicPreparationContext ctx) {
        if (ctx.getItems() == null || ctx.getItems().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "图片列表不能为空");
        }
        if (ctx.getItems().size() > MAX_IMAGES) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "图片数量需在 " + MIN_IMAGES + "~" + MAX_IMAGES + " 张之间");
        }
    }
}
