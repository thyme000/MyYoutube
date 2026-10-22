package cn.diaojiahong.myyoutube.other.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
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
    private static final long VIDEO_MIN_BYTES = 1;
    private static final long VIDEO_MAX_BYTES = 500L * 1024 * 1024;

    private final S3StorageUtil storageUtil;
    private final MediaDraftPort mediaDraftPort;

    @Override
    @ObservedBusiness(BusinessOperation.PRESIGN_UPLOAD)
    public StoragePresignVO presignUpload(String bizType, String filename, Long fileSize, String contentType) {
        String biz = BIZ_DIRS.get(bizType);
        if (biz == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不支持的 bizType: " + bizType);
        }
        if ("work".equals(bizType) && (fileSize == null || fileSize < VIDEO_MIN_BYTES || fileSize > VIDEO_MAX_BYTES
                || contentType == null || !contentType.matches("video/[a-zA-Z0-9.+-]+")))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请选择不超过 500 MB 的非空视频文件，并提供视频类型");
        Long userId = Long.valueOf(UserContext.getUserId());

        String objectKey = storageUtil.buildUniqueObjectKey(userId, biz, filename);

        StoragePresignVO vo = new StoragePresignVO();
        vo.setObjectKey(objectKey);
        vo.setExpireSeconds(PRESIGN_EXPIRE_SECONDS);
        if ("work".equals(bizType)) {
            // PUT 避开 RustFS 的二进制表单解析问题，签名仍锁定实际大小和类型。
            String url = storageUtil.presignPutUrl(objectKey, PRESIGN_EXPIRE_SECONDS, fileSize, contentType);
            Long draftId = mediaDraftPort.createDraft(
                    new MediaDraftPort.DraftCreate(userId, objectKey, filename)).getDraftId();
            vo.setUploadType("put");
            vo.setUrl(url);
            vo.setDraftId(draftId);
            log.info("视频上传凭证已发放 userId={} draftId={} objectKey={}", userId, draftId, objectKey);
        } else {
            String url = storageUtil.presignPutUrl(objectKey, PRESIGN_EXPIRE_SECONDS);
            vo.setUploadType("put");
            vo.setUrl(url);
            log.debug("上传凭证已发放 userId={} biz={} objectKey={}", userId, biz, objectKey);
        }
        return vo;
    }
}
