package cn.diaojiahong.myyoutube.notification.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 用户站内通知（含评论赞、回复等）
 * </p>
 *
 * @author thyme
 * @since 2026-04-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_notification")
public class UserNotification implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收用户 ID
     */
    private Long receiverId;

    /**
     * 触发用户 ID（如点赞人；系统通知可为空）
     */
    private Long senderId;

    /**
     * 类型：COMMENT_LIKE / COMMENT_REPLY / VIDEO_COMMENT 等
     */
    private String type;

    /**
     * 标题（可选，推送/列表用）
     */
    private String title;

    /**
     * 摘要文案（可选）
     */
    private String content;

    /**
     * 关联业务类型：VIDEO / COMMENT
     */
    private String refType;

    /**
     * 关联主键 ID（如 comment_id 或 video_id，按 type 约定）
     */
    private Long refId;

    /**
     * 扩展字段：videoId、parentCommentId 等
     */
    private String extraJson;

    /**
     * 是否已读：0 否 1 是
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


}
