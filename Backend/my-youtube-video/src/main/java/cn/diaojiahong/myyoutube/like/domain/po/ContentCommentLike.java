package cn.diaojiahong.myyoutube.like.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 评论点赞表
 * </p>
 *
 * @author thyme
 * @since 2026-09-29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("content_comment_like")
public class ContentCommentLike implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long commentId;

    /**
     * 是否点赞: 1=已点赞, 0=已取消
     */
    private Integer isLiked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
