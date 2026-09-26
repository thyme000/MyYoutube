package cn.diaojiahong.myyoutube.mq.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LikeCommentMessage {
    // 点赞评论ID
    private Long commentId;
    // 点赞的用户ID
    private Long senderId;
}
