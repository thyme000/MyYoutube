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
    public static final String TOPIC = "comment_action_topic";
    public static final String TAG = "publish";

    private Long commentId;
    private Long receiverId;
    private Long senderId;
    private Long contentId;
    private String content;

    private Boolean isContentComment;
}
