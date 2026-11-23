package cn.diaojiahong.myyoutube.comment.domain.dto;

import lombok.Data;

@Data
public class CommentDTO {
    private Long contentId;
    private String content;
    private Long parentId;
}
