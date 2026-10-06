package cn.diaojiahong.myyoutube.video.chain.dynamic;

/**
 * 动态发布链的发布规则: 常量与纯函数从 DynamicServiceImpl 随链迁出,
 * 服务侧的时间轴计算与各步骤共用同一份口径。
 */
public final class DynamicPublishRules {

    public static final int MAX_IMAGES = 18;
    public static final int MIN_IMAGES = 1;
    public static final long DEFAULT_ITEM_DURATION_MS = 3000L;
    public static final long FADE_DURATION_MS = 800L;
    public static final long MIN_ITEM_DURATION_MS = FADE_DURATION_MS + 300L;
    public static final long AUDIO_MAX_DURATION_MS = 60_000L;

    private DynamicPublishRules() {
    }

    /**
     * 均分时长: 成片填满音频时 D = (T + (n-1)*fade) / n, 无音频给固定 3s。
     */
    public static long uniformDurationMs(int n, long audioMs, long fade) {
        if (audioMs > 0) {
            return Math.max((audioMs + (long) (n - 1) * fade) / n, MIN_ITEM_DURATION_MS);
        }
        return DEFAULT_ITEM_DURATION_MS;
    }

    public static double clampRatio(Double v) {
        if (v == null || v.isNaN()) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }
}
