package cn.diaojiahong.myyoutube.comment.controller;

import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentListQuery;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentReplyQuery;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentPageVO;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.service.IContentCommentService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 视频评论表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@RestController
@RequestMapping("/comment")
@Tag(name = "评论模块")
@RequiredArgsConstructor
public class ContentCommentController {

    private final IContentCommentService videoCommentService;

    /**
     * 用户发布评论
     * @param commentDTO
     * @return
     */
    @Operation(summary = "发布评论")
    @PostMapping("/publish")
    public Result<Void> publishComment(@RequestBody CommentDTO commentDTO) {
        videoCommentService.publishComment(commentDTO);
        return Result.success();
    }

    /**
     * 用户删除评论
     * @param commentId 评论id
     * @return
     */
    @Operation(summary = "用户删除评论")
    @PostMapping("/delete")
    public Result<Void> deleteComment(@RequestParam Long commentId) {
        videoCommentService.deleteComment(commentId);
        return Result.success();
    }

    /**
     * 根评论列表: tab=hot(最热, Top N) / latest(最新), 游标分页
     */
    @Operation(summary = "查询评论列表")
    @GetMapping("/list")
    public Result<CommentPageVO> getRootComments(@ModelAttribute CommentListQuery query) {
        return Result.success(videoCommentService.getRootComments(query));
    }

    /**
     * 楼中楼展开: 按时间升序, 游标分页
     */
    @Operation(summary = "查询楼中楼回复")
    @GetMapping("/replies")
    public Result<CommentPageVO> getReplies(@ModelAttribute CommentReplyQuery query) {
        return Result.success(videoCommentService.getReplies(query));
    }
}
