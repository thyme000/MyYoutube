package cn.diaojiahong.myyoutube.comment.domain.dto;

import lombok.Data;

@Data
public class CommentDTO {
    private Long videoId;
    private String content;
    private Long parentId;
}
