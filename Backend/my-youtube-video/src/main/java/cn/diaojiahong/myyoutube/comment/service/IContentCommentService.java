package cn.diaojiahong.myyoutube.comment.service;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentListQuery;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReplyQuery;
import cn.diaojiahong.myyoutube.comment.domain.po.ContentComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentPageVO;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 视频评论表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
public interface IContentCommentService extends IService<ContentComment> {

    void publishComment(CommentDTO commentDTO);

    void deleteComment(Long commentId);
    /** 根评论列表, hot/latest 双 tab 游标分页 */
    CommentPageVO getRootComments(CommentListQuery query);
    /** 楼中楼展开, 时间升序游标分页 */
    CommentPageVO getReplies(CommentReplyQuery query);
}
