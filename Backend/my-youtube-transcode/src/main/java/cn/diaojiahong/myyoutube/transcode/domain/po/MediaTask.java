package cn.diaojiahong.myyoutube.transcode.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("media_task")
public class MediaTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long ownerId;
    private Long pipelineId;
    private String phase;
    private String taskType;
    private String status;
    private Boolean completionAck;
    private Integer attempt;
    private Integer retryCount;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String draftInputJson;
    private String dependenciesJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resultJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private LocalDateTime readyAt;
    private LocalDateTime startedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextRetryAt;
    private LocalDateTime dispatchAfter;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
