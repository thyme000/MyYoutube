package cn.diaojiahong.myyoutube.other.domain.vo;

import lombok.Data;

@Data
public class OssStsVO {
    private String accessKeyId;
    private String accessKeySecret;
    private String securityToken;
    // ISO 字符串
    private String expiration;
    private String region;
    private String bucket;
    private String endpoint;
    // 允许上传目录，如 uploads/video/123/
    private String dir;
}
