package cn.diaojiahong.myyoutube.content.strategy;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import java.util.Objects;

public final class PreparedDrafts {
    private PreparedDrafts() {
    }

    public static ContentMedia media(MediaDraft draft) {
        try {
            ContentMedia media = JsonUtil.fromJson(draft.getPreparedJson(), ContentMedia.class);
            PreparedMedia.validate(media, draft.getPreparedCoverObjectKey());
            PreparationContracts.Manifest manifest = media.preparation();
            if (!Objects.equals(manifest.preparationId(), draft.getPreparationId())
                    || !Objects.equals(manifest.draftId(), draft.getId())
                    || !Objects.equals(manifest.userId(), draft.getUserId())
                    || !Objects.equals(manifest.mediaVersion(), draft.getMediaVersion())
                    || !media.type().equals(draft.getType().getCode())) {
                throw new IllegalArgumentException("Prepared draft identity mismatch");
            }
            return media;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DRAFT_PREPARATION_FAILED, "缺少完整且版本一致的媒体准备结果，请取消发布后编辑草稿", e);
        }
    }
}
