package cn.diaojiahong.myyoutube.admin.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentVO {
    // 评论id
    private Long commentId;
    // 评论内容
    private String content;
    // 评论人id
    private Long userId;
    // 评论时间
    private LocalDateTime createTime;
}
