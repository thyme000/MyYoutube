package cn.diaojiahong.myyoutube.video.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("content_review_record")
public class ContentReviewRecord {
    @TableId(type = IdType.ASSIGN_ID)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long contentId;
    private Long pipelineId;
    private Integer attempt;
    private String source;
    private String decision;
    private Double riskScore;
    private Double confidence;
    private String reason;
    private String evidenceJson;
    private String model;
    private String rulesVersion;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long actorId;
    private java.time.LocalDateTime createTime;
}
