package cn.diaojiahong.myyoutube.history.domain.po;

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
 * 用户浏览历史(视频/动态同池, (user_id, content_id) 唯一, 重复观看 upsert 置顶)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_view_history")
public class UserViewHistory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long contentId;

    /** 最近观看时间 */
    private LocalDateTime viewTime;
}
