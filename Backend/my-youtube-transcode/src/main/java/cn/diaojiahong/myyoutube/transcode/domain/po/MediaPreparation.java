package cn.diaojiahong.myyoutube.transcode.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("media_preparation")
public class MediaPreparation {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long draftId;
    private Long mediaVersion;
    private Long userId;
    private String requestJson;
    private String parameterVersion;
    private String lifecycle;
    private java.time.LocalDateTime reclaimAfter;
    private java.time.LocalDateTime retiredAt;
}
