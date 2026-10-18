package cn.diaojiahong.myyoutube.common.mq.message;

import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import lombok.*;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaTaskMessage {
    private Long retryBatchId;
    private Long taskId;
    private Long ownerId;
    private Long pipelineId;
    private Long userId;
    private String taskType;
    private String objectKey;
    private Integer attempt;
    private String phase;
    private Input input;
    private Map<String, String> dependencies;
}
