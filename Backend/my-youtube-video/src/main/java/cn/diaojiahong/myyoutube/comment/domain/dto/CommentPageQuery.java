package cn.diaojiahong.myyoutube.comment.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class CommentPageQuery extends PageQuery {
    private Long videoId;
    private Long rootId;
    private Long parentId;
}
