package cn.diaojiahong.myyoutube.video.controller;


import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.*;
import cn.diaojiahong.myyoutube.video.domain.vo.*;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 视频信息表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
@RestController
@RequestMapping("/videos")
@Tag(name = "视频模块")
@RequiredArgsConstructor
public class VideoController {

    private final IVideoService videoService;

    /**
     * 用户发布视频
     * @return
     */
    @PutMapping("/publish")
    @Operation(summary ="发布视频")
    public Result<String> publishVideo(@RequestBody VideoDTO videoDTO) {
        return videoService.publishVideo(videoDTO);
    }

    /**
     * 根据条件分页查询视频列表
     * @param pageQuery
     * @return
     */
    @GetMapping("/list")
    @Operation(summary = "获取视频列表")
    public Result<PageResult<VideoListVO>> getVideoList(@ModelAttribute VideoPageQuery pageQuery) {
        return videoService.getVideoList(pageQuery);
    }

    /**
     * 查询视频详情
     * @param videoId 视频id
     * @return
     */
    @GetMapping("/detail/{videoId}")
    @Operation(summary = "获取视频详情")
    public Result<VideoDetailVO> getVideoDetail(@PathVariable Long videoId) {
        return videoService.getVideoDetail(videoId);
    }

    /**
     * 根据作者id分页查询视频列表(包括全部,长视频,短视频)
     * @param authorId 作者id
     * @param pageQuery 分页参数
     * @return
     */
    @GetMapping("/{authorId}")
    @Operation(summary = "获取作者视频列表")
    public Result<PageResult<VideoListVO>> getVideoListByAuthorId(@PathVariable Long authorId, @ModelAttribute VideoPageQuery pageQuery) {
        return videoService.getVideoListByAuthorId(authorId, pageQuery);
    }

    /**
     * 获取我的视频列表
     * @param pageQuery 分页参数
     * @return
     */
    @GetMapping("/me/list")
    @Operation(summary = "获取我的视频列表")
    public Result<PageResult<VideoListVO>> getMyVideoList(@ModelAttribute VideoPageQuery pageQuery) {
        return videoService.getMyVideoList(pageQuery);
    }

    /**
     * 根据用户上传的视频,自动生成封面
     * @param request 视频url
     * @return
     */
    @Operation(summary = "自动生成视频封面")
    @PostMapping("/generate-cover")
    public Result<String> generateCover(@RequestBody VideoCoverMessage request) {
        return videoService.generateVideoCover(request);
    }

    /**
     * 获取短视频列表(按用户喜好)
     * @param pageQuery
     * @return
     */
    @Operation(summary = "获取短视频列表")
    @GetMapping("/shorts/feed")
    public Result<PageResult<ShortsVO>> getShortsFeed(@ModelAttribute PageQuery pageQuery) {
        return videoService.getShortsFeed(pageQuery);
    }

    /**
     * 在redis中记录用户观看长视频的时长记录
     * @param userWatchTimeDTO
     * @return
     */
    @Operation(summary = "用户播放视频时长记录")
    @PutMapping("/playback/resume")
    public Result<Void> updatePlaybackResume(@RequestBody RecordUserWatchTimeDTO userWatchTimeDTO) {
        return videoService.updatePlaybackResume(userWatchTimeDTO);
    }

    /**
     * 在redis中记录用户观看短视频的记录
     * @param userShortsWatchRecords
     * @return
     */
    @Operation(summary = "用户观看短视频记录")
    @PutMapping("/shorts/play")
    public Result<Void> recordShortsPlay(@RequestBody UserShortsWatchRecords userShortsWatchRecords) {
        return videoService.recordShortsPlay(userShortsWatchRecords);
    }

    /**
     * 用户查看他人公开的视频列表
     * @param userId 用户id
     * @param pageQuery 分页参数
     * @return
     */
    @Operation(summary = "用户查看他人公开的视频列表")
    @GetMapping("/{userId}/list")
    public Result<PageResult<VideoListVO>> getOtherUserVideoList(@PathVariable Long userId, @ModelAttribute PageQuery pageQuery) {
        return videoService.getOtherUserVideoList(userId, pageQuery);
    }

    /**
     * 获取关注(所有被关注人)视频列表
     * @param pageQuery
     * @return
     */
    @Operation(summary = "用户查看关注视频列表")
    @GetMapping("/follow/list")
    public Result<PageResult<FollowVideoVO>> getFollowVideoList(@ModelAttribute PageQuery pageQuery) {
        return videoService.getFollowVideoList(pageQuery);
    }

    /**
     * 用户删除视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户删除某视频")
    @DeleteMapping("/{videoId}")
    public Result<Void> deleteVideo(@PathVariable Long videoId) {
        return videoService.deleteVideo(videoId);
    }

    /**
     * 用户私密视频
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户私密视频")
    @PostMapping("/private/{videoId}")
    public Result<Void> privateVideo(@PathVariable Long videoId) {
        return videoService.privateVideo(videoId);
    }

    /**
     * 公开视频(原本在review状态的仍然需要审核)
     * @param videoId
     * @return
     */
    @Operation(summary = "用户公开视频(原本在review状态的仍然需要审核)")
    @PostMapping("/public/{videoId}")
    public Result<String> publicVideo(@PathVariable Long videoId) {
        return videoService.publicVideo(videoId);
    }

    /**
     * 用户开启评论
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户开启评论")
    @PostMapping("/open/comment/{videoId}")
    public Result<Void> openComment(@PathVariable Long videoId) {
        return videoService.openComment(videoId);
    }

    /**
     * 用户关闭评论
     * @param videoId 视频id
     * @return
     */
    @Operation(summary = "用户关闭评论")
    @PostMapping("/close/comment/{videoId}")
    public Result<Void> closeComment(@PathVariable Long videoId) {
        return videoService.closeComment(videoId);
    }
}
