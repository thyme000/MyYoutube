package cn.diaojiahong.myyoutube.mq.message;

import lombok.Builder;
import lombok.Data;


@Data
@Builder
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
