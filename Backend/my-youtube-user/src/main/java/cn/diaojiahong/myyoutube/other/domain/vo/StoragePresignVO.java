package cn.diaojiahong.myyoutube.other.domain.vo;

import lombok.Data;

import java.util.Map;

@Data
public class StoragePresignVO {
    // 上传类型：视频 PUT 签名绑定实际大小和类型；post 使用表单字段。
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
