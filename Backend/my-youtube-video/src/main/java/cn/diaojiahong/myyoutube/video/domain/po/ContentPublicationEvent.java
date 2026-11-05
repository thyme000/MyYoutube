package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("content_publication_event")
public class ContentPublicationEvent {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long contentId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorId;
    private ContentStatusEnum status;
    private Long audioId;
    private Boolean audioCountDone;
    private String reason;
    private Boolean searchDone;
    private Boolean noticeDone;
    private Boolean completed;
    private java.time.LocalDateTime createTime;
}
