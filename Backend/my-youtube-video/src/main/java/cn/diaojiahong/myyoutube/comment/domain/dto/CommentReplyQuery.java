package cn.diaojiahong.myyoutube.comment.domain.dto;

import lombok.Data;

@Data
public class CommentReplyQuery {
    /** 楼id, 即根评论id */
    private Long rootId;
    private String cursor;
    private Integer pageSize;
}
