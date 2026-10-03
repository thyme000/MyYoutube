package cn.diaojiahong.myyoutube.content.domain.po;

import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 统一内容池: video/dynamic 共用雪花id, 互动计数与跨内容分发都挂这里
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("content")
public class Content implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 与领域表(video/dynamic)主键相同
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 内容类型
     */
    private ContentTypeEnum type;

    /**
     * 作者ID
     */
    private Long authorId;

    /**
     * 状态(published/review/banned), 随领域状态机
     */
    private String status;

    /**
     * 可见性(public/private), 随领域状态机
     */
    private String visibility;

    /**
     * 播放次数
     */
    private Long playCount;

    /**
     * 点赞数
     */
    private Long likeCount;

    /**
     * 收藏数
     */
    private Long collectCount;

    /**
     * 评论数
     */
    private Long commentCount;

    /**
     * 分享数
     */
    private Long shareCount;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
