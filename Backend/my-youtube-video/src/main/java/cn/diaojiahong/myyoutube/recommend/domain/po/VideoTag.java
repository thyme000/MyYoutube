package cn.diaojiahong.myyoutube.recommend.domain.po;

import java.math.BigDecimal;
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
 * 视频-标签关联表
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("video_tag")
public class VideoTag implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 视频 ID，关联 video.id
     */
    private Long videoId;

    /**
     * 标签 ID，关联 tag.id
     */
    private Long tagId;

    /**
     * 该视频在此标签上的权重（可选，用于排序微调）
     */
    private BigDecimal weight;

    /**
     * 绑定时间
     */
    private LocalDateTime createdAt;


}
