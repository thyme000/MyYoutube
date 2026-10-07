package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务完成回报: worker -> 编排器, 编排器侧 CAS 收敛状态并推进 DAG
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaTaskDoneMessage {
    private Long taskId;
    private Long draftId;
    private String taskType;
    private Boolean success;
    /** CAS 依据: 只接受与当前执行轮次一致的回报, 丢弃僵尸执行 */
    private Integer attempt;
    private String resultJson;
    private String errorMessage;
}
