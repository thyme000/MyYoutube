package cn.diaojiahong.myyoutube.other.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.other.domain.vo.StoragePresignVO;
import cn.diaojiahong.myyoutube.other.service.IOtherService;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtherService implements IOtherService {

    private static final Map<String, String> BIZ_DIRS = Map.of(
            "work", "video",
            "chat", "chat",
            "audio", "audio",
            "picture", "picture"
    );
    private static final long PRESIGN_EXPIRE_SECONDS = 30 * 60;
    // 视频直传约束: 签名策略锁死, 存储层直接拒绝越界对象
    private static final String VIDEO_CONTENT_TYPE_PREFIX = "video/";
    private static final long VIDEO_MIN_BYTES = 1;
    private static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;

    private final S3StorageUtil storageUtil;
    private final MediaDraftPort mediaDraftPort;

    @Override
    public StoragePresignVO presignUpload(String bizType, String filename) {
        String biz = BIZ_DIRS.get(bizType);
        if (biz == null) {
            throw new BizException(BizException.STORAGE_ERROR, "不支持的 bizType: " + bizType);
        }
        Long userId = Long.valueOf(UserContext.getUserId());

        String objectKey = storageUtil.buildUniqueObjectKey(userId, biz, filename);

        StoragePresignVO vo = new StoragePresignVO();
        vo.setObjectKey(objectKey);
        vo.setExpireSeconds(PRESIGN_EXPIRE_SECONDS);
        if ("work".equals(bizType)) {
            // 视频走事件驱动管线: presign 即建草稿, POST 表单锁定大小/类型
            Long draftId = mediaDraftPort.createDraft(
                    new MediaDraftPort.DraftCreate(userId, objectKey, filename)).getDraftId();
            S3StorageUtil.PresignPostForm form = storageUtil.presignPostForm(
                    objectKey, VIDEO_CONTENT_TYPE_PREFIX, VIDEO_MIN_BYTES, VIDEO_MAX_BYTES, PRESIGN_EXPIRE_SECONDS);
            vo.setUploadType("post");
            vo.setUrl(form.url());
            vo.setFormFields(form.fields());
            vo.setDraftId(draftId);
            log.info("视频上传凭证已发放 userId={} draftId={} objectKey={}", userId, draftId, objectKey);
        } else {
            String url = storageUtil.presignPutUrl(objectKey, PRESIGN_EXPIRE_SECONDS);
            vo.setUploadType("put");
            vo.setUrl(url);
        }
        return vo;
    }
}
