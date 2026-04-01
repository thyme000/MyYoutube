package cn.diaojiahong.myyoutube.like.controller;


import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.like.domain.vo.LikeVideoVO;
import cn.diaojiahong.myyoutube.like.service.IVideoLikeService;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 视频点赞表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@RestController
@RequestMapping("/like")
@Tag(name = "点赞模块")
@RequiredArgsConstructor
public class VideoLikeController {

    private final IVideoLikeService videoLikeService;

    /**
     * 用户点赞视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户点赞视频")
    @PutMapping("/{videoId}")
    public Result<Void> likeVideo(@PathVariable Long videoId) {
        return videoLikeService.likeVideo(videoId);
    }

    /**
     * 用户取消点赞视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户取消点赞视频")
    @DeleteMapping("/{videoId}")
    public Result<Void> cancelLikeVideo(@PathVariable Long videoId) {
        return videoLikeService.cancelLikeVideo(videoId);
    }

    /**
     * 用户不喜欢(踩过)这个视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户不喜欢(踩过)这个视频")
    @PostMapping("/dislike/{videoId}")
    public Result<Void> dislikeVideo(@PathVariable Long videoId) {
        return videoLikeService.dislikeVideo(videoId);
    }

    /**
     * 获取用户喜欢列表
     * @param userId 用户id
     * @param pageQuery 分页查询参数
     * @return
     */
    @Operation(summary = "用户查看点赞视频")
    @GetMapping("/{userId}/list")
    public Result<PageResult<LikeVideoVO>> getLikeVideoList(@PathVariable Long userId, @ModelAttribute PageQuery pageQuery) {
        return videoLikeService.getLikeVideoList(userId, pageQuery);
    }
}
