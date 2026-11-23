package cn.diaojiahong.myyoutube.video.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("audio_review_record")
public class AudioReviewRecord {
    @TableId(type = IdType.ASSIGN_ID) private Long id;
    private Long audioId;
    private String source;
    private String decision;
    private String reason;
    private String payloadJson;
    private Integer attempt;
    private Long actorId;
    private LocalDateTime createTime;
}
