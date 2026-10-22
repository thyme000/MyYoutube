package cn.diaojiahong.myyoutube.video.chain.dynamic;

/**
 * 动态发布链的发布规则: 常量与纯函数从 DynamicServiceImpl 随链迁出,
 * 服务侧的时间轴计算与各步骤共用同一份口径。
 */
public final class DynamicPreparationRules {

    public static final int MAX_IMAGES = 18;
    public static final int MIN_IMAGES = 1;
    public static final long DEFAULT_ITEM_DURATION_MS = 3000L;
    public static final long FADE_DURATION_MS = 800L;

    private DynamicPreparationRules() {
    }

    public static double clampRatio(Double v) {
        if (v == null) return 0.5;
        if (!Double.isFinite(v) || v < 0 || v > 1)
            throw new cn.diaojiahong.myyoutube.common.exception.BizException(
                    cn.diaojiahong.myyoutube.common.result.ErrorCode.INVALID_ARGUMENT, "图片焦点必须为 0~1");
        return v;
    }
}
