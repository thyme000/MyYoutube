package cn.diaojiahong.myyoutube.comment.service;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentPageQuery;
import cn.diaojiahong.myyoutube.comment.domain.po.VideoComment;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 视频评论表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
public interface IVideoCommentService extends IService<VideoComment> {

    Result<Void> publishComment(CommentDTO commentDTO);

    Result<Void> deleteComment(Long commentId);

    Result<PageResult<CommentVO>> getCommentList(CommentPageQuery commentPageQuery);

}
