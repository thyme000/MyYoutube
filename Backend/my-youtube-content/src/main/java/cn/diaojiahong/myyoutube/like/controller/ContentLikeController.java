package cn.diaojiahong.myyoutube.like.controller;

import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.like.domain.vo.LikedContentVO;
import cn.diaojiahong.myyoutube.like.service.IContentLikeService;
import cn.diaojiahong.myyoutube.mq.message.LikeCommentMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 内容点赞表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@RestController
@RequestMapping("/like")
@Tag(name = "点赞模块")
@RequiredArgsConstructor
public class ContentLikeController {

    private final IContentLikeService contentLikeService;

    @Operation(summary = "用户点赞内容")
    @PutMapping("/{contentId}")
    public Result<Void> likeContent(@PathVariable Long contentId) {
        contentLikeService.likeContent(contentId);
        return Result.success();
    }

    @Operation(summary = "用户取消点赞内容")
    @DeleteMapping("/{contentId}")
    public Result<Void> cancelLikeContent(@PathVariable Long contentId) {
        contentLikeService.cancelLikeContent(contentId);
        return Result.success();
    }

    @Operation(summary = "用户查看点赞内容")
    @GetMapping("/{userId}/list")
    public Result<PageResult<LikedContentVO>> getLikedContentList(
            @PathVariable Long userId,
            @ModelAttribute ContentPageQuery pageQuery) {
        return Result.success(contentLikeService.getLikedContentList(userId, pageQuery));
    }

    @Operation(summary = "用户是否给这个内容点过赞")
    @GetMapping("/{contentId}/status")
    public Result<Boolean> getLikeStatus(@PathVariable Long contentId) {
        return Result.success(contentLikeService.getLikeStatus(contentId));
    }

    @Operation(summary = "用户给评论点赞")
    @PostMapping("/comment/{commentId}")
    public Result<Void> likeComment(@PathVariable Long commentId) {
        contentLikeService.likeComment(commentId);
        return Result.success();
    }

    @DeleteMapping("/comment/{commentId}")
    @Operation(summary = "用户给评论取消点赞")
    public Result<Void> cancelLikeComment(@PathVariable Long commentId) {
        contentLikeService.cancelLikeComment(commentId);
        return Result.success();
    }
}
