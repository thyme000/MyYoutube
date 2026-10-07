package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传事件消息: 对象存储 webhook 桥接后的规范化事件, 触发拆任务
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaUploadEventMessage {
    private Long draftId;
    private Long userId;
    private String objectKey;
    private Long fileSize;
}
