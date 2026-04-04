package cn.diaojiahong.myyoutube.comment.controller;


import cn.diaojiahong.myyoutube.comment.domain.dto.CommentDTO;
import cn.diaojiahong.myyoutube.comment.domain.dto.CommentPageQuery;
import cn.diaojiahong.myyoutube.comment.domain.vo.CommentVO;
import cn.diaojiahong.myyoutube.comment.service.IVideoCommentService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
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
public class VideoCommentController {

    private final IVideoCommentService videoCommentService;

    /**
     * 用户发布评论
     * @param commentDTO
     * @return
     */
    @Operation(summary = "发布评论")
    @PostMapping("/publish")
    public Result<Void> publishComment(@RequestBody CommentDTO commentDTO) {
        return videoCommentService.publishComment(commentDTO);
    }

    /**
     * 用户删除评论
     * @param commentId 评论id
     * @return
     */
    @Operation(summary = "用户删除评论")
    @PostMapping("/delete")
    public Result<Void> deleteComment(@RequestParam Long commentId) {
        return videoCommentService.deleteComment(commentId);
    }

    /**
     * 用户查询评论列表
     * @param commentPageQuery
     * @return
     */
    @Operation(summary = "用户查询评论列表")
    @GetMapping("/list")
    public Result<PageResult<CommentVO>> getCommentList(@ModelAttribute CommentPageQuery commentPageQuery) {
        return videoCommentService.getCommentList(commentPageQuery);
    }


}
