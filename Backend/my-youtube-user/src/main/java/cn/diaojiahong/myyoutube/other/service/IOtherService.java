package cn.diaojiahong.myyoutube.other.service;

import cn.diaojiahong.myyoutube.other.domain.vo.StoragePresignVO;

public interface IOtherService {
    StoragePresignVO presignUpload(String bizType, String filename, Long fileSize, String contentType);
}
