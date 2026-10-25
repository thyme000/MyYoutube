package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.domain.enums.AudioProcessingStatus;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("audio")
public class Audio implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long uploaderId;

    private String title;

    private String requestId;
    private String requestFingerprint;
    private String sourceObjectKey;
    private String sourceCoverObjectKey;

    private String objectKey;
    private String coverObjectKey;

    private Integer durationMs;
    private Long fileSize;
    private String mime;
    private AudioProcessingStatus processingStatus;
    private AudioReviewStatus reviewStatus;
    private Integer preparationAttempt;
    private Boolean preparationRetry;
    private LocalDateTime preparationAfter;
    private LocalDateTime processingDeadline;
    private String errorMessage;

    /** public/private */
    private String visibility;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDelete;
}
