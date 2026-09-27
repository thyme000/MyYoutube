package cn.diaojiahong.myyoutube.video.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.video.api.VideoAdminPort;
import cn.diaojiahong.myyoutube.video.api.dto.VideoSummary;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * video 服务内部接口: 管理端视频操作
 */
@RestController
@RequestMapping("/internal/admin/video")
@RequiredArgsConstructor
public class InternalAdminVideoController {

    private final IVideoService videoService;

    @PostMapping("/mark-status")
    public Result<Boolean> markStatus(@RequestBody VideoAdminPort.MarkStatus mark) {
        boolean success = videoService.lambdaUpdate()
                .set(Video::getStatus, mark.getStatusCode())
                .eq(Video::getId, mark.getVideoId())
                .update();
        return Result.success(success);
    }

    @PostMapping("/get")
    public Result<VideoSummary> getVideo(@RequestBody VideoAdminPort.VideoId id) {
        Video video = videoService.getById(id.getVideoId());
        if (video == null) {
            return Result.success(null);
        }
        return Result.success(toSummary(video));
    }

    @PostMapping("/page")
    public Result<PageResult<VideoSummary>> pageVideos(@RequestBody VideoAdminPort.AdminVideoQuery query) {
        var page = videoService.lambdaQuery()
                .eq(query.getStatusCode() != null, Video::getStatus, query.getStatusCode())
                .eq(query.getUserId() != null, Video::getAuthorId, query.getUserId())
                .eq(query.getTypeCode() != null, Video::getVideoType, query.getTypeCode())
                .eq(query.getCategoryCode() != null, Video::getCategory, query.getCategoryCode())
                .orderByDesc(Video::getCreateTime)
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Video>(
                        query.getPageNo(), query.getPageSize()));
        List<VideoSummary> summaries = page.getRecords().stream().map(InternalAdminVideoController::toSummary).toList();
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), summaries));
    }

    private static VideoSummary toSummary(Video video) {
        VideoSummary s = new VideoSummary();
        s.setId(video.getId());
        s.setTitle(video.getTitle());
        s.setDescription(video.getDescription());
        s.setCoverUrl(video.getCoverUrl());
        s.setVideoUrl(video.getVideoUrl());
        s.setDuration(video.getDuration());
        s.setLikeCount(video.getLikeCount());
        s.setPlayCount(video.getPlayCount());
        s.setShareCount(video.getShareCount());
        s.setCommentCount(video.getCommentCount());
        s.setAllowComment(video.getAllowComment());
        s.setAuthorId(video.getAuthorId());
        s.setCreateTime(video.getCreateTime());
        s.setTags(video.getTags());
        s.setStatus(video.getStatus() != null ? video.getStatus().getCode() : null);
        s.setCategoryCode(video.getCategory() != null ? video.getCategory().getCode() : null);
        return s;
    }
}
