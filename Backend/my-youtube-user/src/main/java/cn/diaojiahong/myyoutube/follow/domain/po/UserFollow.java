package cn.diaojiahong.myyoutube.follow.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 用户关注关系表
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_follow")
public class UserFollow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键（雪花ID）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关注者用户ID
     */
    private Long followerId;

    /**
     * 被关注者用户ID
     */
    private Long followeeId;

    /**
     * 关注备注（给对方起的别名）
     */
    private String remark;

    /**
     * 0=关注中 1=已取消关注
     */
    private Boolean isDeleted;

    /**
     * 首次关注时间
     */
    private LocalDateTime createTime;

    /**
     * 最后更新时间
     */
    private LocalDateTime updateTime;


}
