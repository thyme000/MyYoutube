package cn.diaojiahong.myyoutube.content.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("content")
public class Content implements Serializable {
    @java.io.Serial private static final long serialVersionUID = 1L;
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private ContentTypeEnum type;
    private Long authorId;
    private Long audioId;
    private String title;
    private String description;
    private String coverObjectKey;
    private String categoryCode;
    private String tags;
    private Integer allowComment;
    private String region;
    private Integer isDelete;
    private ContentStatusEnum status;
    private String visibility;
    private Long indexVersion;
    private Long playCount;
    private Long likeCount;
    private Long collectCount;
    private Long commentCount;
    private Long shareCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
