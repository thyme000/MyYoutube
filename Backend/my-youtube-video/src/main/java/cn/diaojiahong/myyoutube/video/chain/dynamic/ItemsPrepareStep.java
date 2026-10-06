package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.MIN_ITEM_DURATION_MS;
import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules.uniformDurationMs;

@Component
public class ItemsPrepareStep extends DynamicPublishStep {

    private final S3StorageUtil storageUtil;

    public ItemsPrepareStep(S3StorageUtil storageUtil) {
        super(40);
        this.storageUtil = storageUtil;
    }

    /** 逐项校验并归一 objectKey, 顺带算好每张图时长, 发布主体不再碰条目 */
    @Override
    public void apply(DynamicPublishContext ctx) {
        List<DynamicPublishDTO.DynamicItemDTO> items = ctx.getItems();
        int n = items.size();
        long uniform = uniformDurationMs(n, ctx.getAudioMs(), ctx.getFadeMs());
        long[] durations = new long[n];
        List<String> imageKeys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DynamicPublishDTO.DynamicItemDTO item = items.get(i);
            if (item == null || !StringUtils.hasText(item.getImageObjectKey())) {
                throw new BizException(BizException.BIZ_ERROR, "图片列表存在空项");
            }
            String key = storageUtil.normalizeToObjectKey(item.getImageObjectKey().trim());
            if (!objectKeyBelongsToUser(ctx.getUserId(), key)) {
                throw new BizException(BizException.BIZ_ERROR, "存在无权使用的图片资源");
            }
            imageKeys.add(key);
            durations[i] = item.getDurationMs() != null && item.getDurationMs() > 0
                    ? Math.max(item.getDurationMs(), MIN_ITEM_DURATION_MS) : uniform;
        }
        ctx.setItems(imageKeys, durations);
    }

    private boolean objectKeyBelongsToUser(Long userId, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String k = objectKey.startsWith("uploads/") ? objectKey.trim() : storageUtil.normalizeToObjectKey(objectKey);
        return k.startsWith("uploads/") && k.contains("/" + userId + "/");
    }
}
