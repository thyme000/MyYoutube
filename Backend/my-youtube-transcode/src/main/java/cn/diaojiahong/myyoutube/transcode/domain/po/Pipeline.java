package cn.diaojiahong.myyoutube.transcode.domain.po;

import cn.diaojiahong.myyoutube.common.media.PipelineStatus;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("media_pipeline")
public class Pipeline {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long eventSequence;
    private Long contentId;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String inputJson;
    private PipelineStatus status;
    private Boolean received;
    private String manifestJson;
    private LocalDateTime submittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime notifyAfter;
    private LocalDateTime updateTime;
}
