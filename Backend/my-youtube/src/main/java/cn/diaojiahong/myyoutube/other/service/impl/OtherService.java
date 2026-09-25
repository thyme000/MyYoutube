package cn.diaojiahong.myyoutube.other.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.other.domain.vo.StoragePresignVO;
import cn.diaojiahong.myyoutube.other.service.IOtherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OtherService implements IOtherService {

    private static final Map<String, String> BIZ_DIRS = Map.of(
            "work", "video",
            "chat", "chat",
            "picture", "picture"
    );
    private static final long PRESIGN_EXPIRE_SECONDS = 30 * 60;

    private final S3StorageUtil storageUtil;

    @Override
    public StoragePresignVO presignUpload(String bizType, String filename) {
        String biz = BIZ_DIRS.get(bizType);
        if (biz == null) {
            throw new BizException(BizException.STORAGE_ERROR, "不支持的 bizType: " + bizType);
        }
        Long userId = Long.valueOf(UserContext.getUserId());

        String objectKey = storageUtil.buildUniqueObjectKey(userId, biz, filename);
        String url = storageUtil.presignPutUrl(objectKey, PRESIGN_EXPIRE_SECONDS);

        StoragePresignVO vo = new StoragePresignVO();
        vo.setUrl(url);
        vo.setObjectKey(objectKey);
        vo.setExpireSeconds(PRESIGN_EXPIRE_SECONDS);
        return vo;
    }
}
