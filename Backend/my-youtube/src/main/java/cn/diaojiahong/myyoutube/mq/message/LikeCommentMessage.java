package cn.diaojiahong.myyoutube.mq.message;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LikeCommentMessage {
    // 点赞评论ID
    private Long commentId;
    // 点赞的用户ID
    private Long senderId;
}
