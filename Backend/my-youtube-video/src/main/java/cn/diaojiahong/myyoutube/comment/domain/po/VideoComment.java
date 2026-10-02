package cn.diaojiahong.myyoutube.comment.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 视频评论表
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("video_comment")
public class VideoComment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 视频ID，关联视频
     */
    private Long videoId;

    /**
     * 发表评论的用户ID
     */
    private Long userId;

    /**
     * 父评论ID；0表示一级评论（直接评论视频）；非0表示回复对应id的评论
     */
    private Long parentId;

    /**
     * 根评论ID；为null表示一级评论
     */
    private Long rootId;

    /**
     * 回复用户的Id
     */
    private Long replyUserId;

    /**
     * 评论正文
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer likeCount;

    /**
     * 回复数
     */
    private Integer replyCount;

    /**
     * 状态：visible正常，hidden隐藏，deleted用户删除，blocked违规
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;
}
