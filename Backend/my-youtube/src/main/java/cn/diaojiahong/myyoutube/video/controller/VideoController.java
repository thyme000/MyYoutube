package cn.diaojiahong.myyoutube.video.controller;


import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoDetailVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;
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
}
