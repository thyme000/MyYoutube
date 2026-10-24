package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
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
@TableName("media_draft")
public class MediaDraft implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long eventSequence;

    private Long userId;

    private ContentTypeEnum type;

    private String title;

    private String description;

    private String visibility;

    private Integer allowComment;

    private String mediaJson;

    private String coverObjectKey;

    private Integer revision;
    private Long mediaVersion;
    private Long preparationId;
    private Integer preparationAttempt;
    private Boolean preparationRetry;
    private LocalDateTime preparationAfter;
    private LocalDateTime expiresAt;
    private DraftSubmissionState submissionState = DraftSubmissionState.NONE;
    private Integer submissionRevision;
    private Integer submissionAttempts;
    private LocalDateTime submissionAfter;
    private LocalDateTime submissionDeadline;
    private String submissionError;
    private String preparedJson;
    private String preparedCoverObjectKey;

    private String objectKey;

    private String originalFilename;

    private MediaDraftStatusEnum status;

    private Long durationMs;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public boolean expiredAt(LocalDateTime now) {
        return status == MediaDraftStatusEnum.EXPIRED || expiresAt == null || !expiresAt.isAfter(now);
    }
}
