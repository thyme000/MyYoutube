package cn.diaojiahong.myyoutube.video.chain.dynamic;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicDraftDTO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPreparationRules.DEFAULT_ITEM_DURATION_MS;

@Component
public class ItemsPrepareStep extends DynamicPreparationStep {

    private final S3StorageUtil storageUtil;

    public ItemsPrepareStep(S3StorageUtil storageUtil) {
        super(40);
        this.storageUtil = storageUtil;
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
                throw new BizException(BizException.BIZ_ERROR, "图片列表存在空项");
            }
            String key = item.getImageObjectKey().trim();
            if (!objectKeyBelongsToUser(ctx.getUserId(), key)) {
                throw new BizException(BizException.BIZ_ERROR, "存在无权使用的图片资源");
            }
            if (!isImage(storageUtil.readHeadBytes(key, 16)))
                throw new BizException(BizException.BIZ_ERROR, "图片素材为空或格式无效");
            imageKeys.add(key);
            durations[i] = item.getDurationMs() == null ? DEFAULT_ITEM_DURATION_MS : item.getDurationMs();
            if (durations[i] <= ctx.getFadeMs() || durations[i] > Integer.MAX_VALUE)
                throw new BizException(BizException.BIZ_ERROR, "图片时长必须大于转场时长且不超出范围");
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
        return segments.length == 5 && objectKey.startsWith("uploads/picture/" + userId + "/")
                && java.util.Arrays.stream(segments).noneMatch(s -> s.isBlank() || s.equals(".") || s.equals(".."));
    }

    private boolean isImage(byte[] head) {
        if (head == null || head.length < 12) return false;
        String signature = new String(head, StandardCharsets.ISO_8859_1);
        return (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff
                || signature.startsWith("\u0089PNG\r\n\u001a\n")
                || signature.startsWith("GIF87a") || signature.startsWith("GIF89a")
                || signature.startsWith("RIFF") && signature.substring(8, 12).equals("WEBP")
                || signature.startsWith("BM");
    }
}
