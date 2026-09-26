package cn.diaojiahong.myyoutube.mq.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RabbitMQ 消息体。须具备无参构造供 Jackson 反序列化，否则监听器里字段（如 content）可能全为 null。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommentMessage {
    // 评论ID
    private Long commentId;
    // 接收用户ID
    private Long receiverId;
    // 发送者id
    private Long senderId;
    // 视频ID
    private Long videoId;
    // 评论正文
    private String content;
    // 评论类型 (视频评论/评论回复)
    private Boolean isVideoComment;
}
