package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicDraftDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPreparationRules.DEFAULT_ITEM_DURATION_MS;

@Component
public class ItemsPrepareStep extends DynamicPreparationStep {

    public ItemsPrepareStep() {
        super(40);
    }

    /** 逐项校验并归一 objectKey, 顺带算好每张图时长, 发布主体不再碰条目 */
    @Override
    public void apply(DynamicPreparationContext ctx) {
        List<DynamicDraftDTO.DynamicItemDTO> items = ctx.getItems();
        int n = items.size();
        long[] durations = new long[n];
        List<String> imageKeys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DynamicDraftDTO.DynamicItemDTO item = items.get(i);
            if (item == null || !StringUtils.hasText(item.getImageObjectKey())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "图片列表存在空项");
            }
            String key = item.getImageObjectKey().trim();
            if (!objectKeyBelongsToUser(ctx.getUserId(), key)) {
                throw new BizException(ErrorCode.FORBIDDEN, "存在无权使用的图片资源");
            }
            imageKeys.add(key);
            durations[i] = item.getDurationMs() == null ? DEFAULT_ITEM_DURATION_MS : item.getDurationMs();
            if (durations[i] <= ctx.getFadeMs() || durations[i] > Integer.MAX_VALUE)
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "图片时长必须大于转场时长且不超出范围");
            DynamicPreparationRules.clampRatio(item.getFocusX());
            DynamicPreparationRules.clampRatio(item.getFocusY());
        }
        ctx.setItems(imageKeys, durations);
    }

    private boolean objectKeyBelongsToUser(Long userId, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String[] segments = objectKey.split("/", -1);
        return objectKey.length() <= 512 && segments.length == 5 && objectKey.startsWith("uploads/picture/" + userId + "/")
                && java.util.Arrays.stream(segments).noneMatch(s -> s.isBlank() || s.equals(".") || s.equals(".."));
    }

}
