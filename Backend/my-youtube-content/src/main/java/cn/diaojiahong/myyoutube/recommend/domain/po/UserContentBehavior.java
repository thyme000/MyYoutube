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
 * 用户内容行为流水表
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_content_behavior")
public class UserContentBehavior implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long contentId;

    /**
     * 播放行为按服务端会话去重，显式不感兴趣没有播放会话
     */
    private String sessionId;

    /**
     * 行为：play播放 finish完播 skip跳过 dislike点踩
     */
    private EventEnum eventType;

    /**
     * 停留/播放时长(毫秒)，跳过可为 0
     */
    private Long durationMs;

    /**
     * 行为记录写入时间，由数据库生成
     */
    private LocalDateTime createdAt;

}
