package cn.diaojiahong.myyoutube.transcode.domain.po;

import cn.diaojiahong.myyoutube.transcode.api.enums.MediaRetryBatchStatus;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("media_retry_batch")
public class MediaRetryBatch {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventSequence;
    private String requestId;
    private Long actorId;
    private MediaRetryBatchStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer active;
    private String selectionJson;
    private String resultJson;
    private Integer maxConcurrency;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
