package cn.diaojiahong.myyoutube.recommend.domain.po;

import java.math.BigDecimal;
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
 * 用户标签偏好表（内容推荐用）
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_tag_preference")
public class UserTagPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 标签 ID
     */
    private Long tagId;

    /**
     * 偏好分数，越大越偏好
     */
    private Integer score;

    /**
     * 来源：behavior统计 manual运营
     */
    private String source;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;


}
