package cn.diaojiahong.myyoutube.comment.domain.vo;

import cn.diaojiahong.myyoutube.comment.domain.enums.CommentStatusEnum;
import lombok.Data;

@Data
public class CommentVO {
    private Long id;
    private String content;
    private Long contentId;
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String createTime;
    private Long likeCount;
    private Long replyCount;
    private String replyNickname;
    private Long replyUserId;
    private Boolean isLike;
    private String status;
}
