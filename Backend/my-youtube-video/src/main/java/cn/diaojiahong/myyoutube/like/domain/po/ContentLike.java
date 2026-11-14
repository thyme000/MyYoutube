package cn.diaojiahong.myyoutube.like.domain.po;

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
 * 内容点赞表
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("content_like")
public class ContentLike implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 内容ID
     */
    private Long contentId;

    /**
     * 本次点赞实际加分的标签 ID, 数组里的 Long 以字符串存储
     * (JsonUtil 全局按字符串序列化 Long, 防 JS 精度丢损)
     */
    private String creditedTagIds;

    /**
     * 本次点赞给每个记录标签加的分值，用于取消时扣回。
     */
    private Integer creditedScore;

    /**
     * 点赞时间
     */
    private LocalDateTime createTime;

}
