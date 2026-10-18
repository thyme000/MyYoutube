package cn.diaojiahong.myyoutube.video.domain.po;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import java.time.LocalDateTime;
@Data
@Accessors(chain = true)
@TableName("content_understanding")
public class ContentUnderstanding {
    @TableId(type = IdType.INPUT)
    private Long contentId;
    private Long pipelineId;
    private Long eventSequence;
    private ContentReviewState state;
    private String inputJson;
    private Boolean submissionAck;
    private Boolean received;
    private Boolean retryRequested;
    private String subtitleKey;
    private String segmentsKey;
    private String ocrKey;
    private String summaryKey;
    private String tagKey;
    private String summary;
    private String chaptersJson;
    private Integer reviewAttempt;
    private LocalDateTime submittedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime updateTime;
}
