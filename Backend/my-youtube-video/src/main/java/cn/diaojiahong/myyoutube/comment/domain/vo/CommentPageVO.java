package cn.diaojiahong.myyoutube.comment.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class CommentPageVO {
    private List<CommentVO> list;
    private String nextCursor;
    private boolean hasMore;
}
