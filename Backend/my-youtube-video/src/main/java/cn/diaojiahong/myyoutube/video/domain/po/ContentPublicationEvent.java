package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("content_publication_event")
public class ContentPublicationEvent {
    @TableId(type = IdType.ASSIGN_ID)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long contentId;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long authorId;
    private ContentStatusEnum status;
    private Integer delta;
    private String reason;
    private Boolean userCountDone;
    private Boolean searchDone;
    private Boolean noticeDone;
    private Boolean completed;
    private java.time.LocalDateTime createTime;
}
