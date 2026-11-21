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
@TableName("content_comment")
public class ContentComment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long contentId;

    private Long userId;

    /**
     * 物化路径，含自己：根评论=自己id；回复=父评论path.自己id
     */
    private String path;

    /**
     * 层级：1=根评论，大于1为楼中楼回复
     */
    private Integer level;

    private Long replyUserId;

    private String content;

    private Integer likeCount;

    private Integer replyCount;

    /**
     * 状态：visible正常，hidden隐藏，deleted用户删除，blocked违规
     */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
