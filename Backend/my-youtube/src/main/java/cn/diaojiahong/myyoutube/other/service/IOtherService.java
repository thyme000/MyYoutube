package cn.diaojiahong.myyoutube.other.service;

import cn.diaojiahong.myyoutube.other.domain.vo.OssPresignVO;

public interface IOtherService {
    OssPresignVO presignUpload(String bizType, String filename);
}
