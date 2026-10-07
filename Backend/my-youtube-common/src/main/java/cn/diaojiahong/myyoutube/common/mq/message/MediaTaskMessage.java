package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体处理任务消息(短消息): 编排器分发给 worker, attempt 用于完成消息 CAS 防僵尸
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaTaskMessage {
    private Long taskId;
    private Long draftId;
    private Long userId;
    private String taskType;
    private String objectKey;
    private Integer attempt;
}
