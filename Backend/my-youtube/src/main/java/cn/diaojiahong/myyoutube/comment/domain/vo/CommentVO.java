package cn.diaojiahong.myyoutube.comment.domain.vo;

import lombok.Data;

@Data
public class CommentVO {
    // 评论id
    private Long id;
    // 评论内容
    private String content;
    // 视频id
    private Long videoId;
    // 用户id
    private Long userId;
    // 用户昵称
    private String nickname;
    // 用户头像url
    private String avatarUrl;
    // 评论创建时间
    private String createTime;
    // 评论点赞数
    private Long likeCount;
    // 评论回复数
    private Long replyCount;
    // 父评论id
    private Long parentId;
    // 回复用户的昵称
    private String replyNickname;
    // 回复用户的id
    private Long replyUserId;
    // 是否点赞
    private Boolean isLike;
}
