package cn.diaojiahong.myyoutube.recommend.domain.po;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
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
 * 用户-视频行为流水表
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_video_behavior")
public class UserVideoBehavior implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 视频 ID
     */
    private Long contentId;

    /**
     * 行为：expose曝光 play播放 finish完播 like点赞 skip跳过 dislike点踩
     */
    private EventEnum eventType;

    /**
     * 停留/播放时长(毫秒)，跳过可为 0
     */
    private Long durationMs;

    /**
     * 行为发生时间
     */
    private LocalDateTime createdAt;


}
