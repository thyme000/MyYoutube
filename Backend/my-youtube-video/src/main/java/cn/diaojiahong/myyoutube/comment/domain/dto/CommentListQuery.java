package cn.diaojiahong.myyoutube.comment.domain.dto;

import lombok.Data;

@Data
public class CommentListQuery {
    private Long contentId;
    /** hot / latest, 缺省 hot */
    private String tab;
    /** 「排序值.评论id」, 首页不传 */
    private String cursor;
    private Integer pageSize;
}
