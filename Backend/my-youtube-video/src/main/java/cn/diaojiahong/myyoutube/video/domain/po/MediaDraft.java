package cn.diaojiahong.myyoutube.video.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
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

    private Long userId;

    private ContentTypeEnum type;

    private String title;

    private String description;

    private String visibility;

    private Integer allowComment;

    private String mediaJson;

    private String coverObjectKey;

    private Integer revision;

    private String objectKey;

    private String originalFilename;

    private MediaDraftStatusEnum status;

    private Long durationMs;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
