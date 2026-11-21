package cn.diaojiahong.myyoutube.admin.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentVO {
    private Long commentId;
    private String content;
    private Long userId;
    private LocalDateTime createTime;
}
