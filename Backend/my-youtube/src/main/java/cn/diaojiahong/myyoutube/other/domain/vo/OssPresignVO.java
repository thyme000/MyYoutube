package cn.diaojiahong.myyoutube.other.domain.vo;

import lombok.Data;

@Data
public class OssPresignVO {
    // 预签名 PUT URL，前端直接 XHR PUT 文件字节
    private String url;
    // 上传成功后入库的对象键
    private String objectKey;
    // URL 有效期(秒)
    private Long expireSeconds;
}
