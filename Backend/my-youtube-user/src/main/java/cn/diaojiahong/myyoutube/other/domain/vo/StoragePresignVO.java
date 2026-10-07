package cn.diaojiahong.myyoutube.other.domain.vo;

import lombok.Data;

import java.util.Map;

@Data
public class StoragePresignVO {
    // 上传类型: put=XHR PUT 直传(旧路径), post=multipart form-data 表单直传(带大小/类型约束)
    private String uploadType;
    // put: 预签名 PUT URL; post: 表单提交地址
    private String url;
    // post 模式的表单隐藏字段, 需全部原样携带
    private Map<String, String> formFields;
    // 上传成功后入库的对象键
    private String objectKey;
    // URL 有效期(秒)
    private Long expireSeconds;
    // work 业务: presign 时创建的视频草稿ID
    private Long draftId;
}
