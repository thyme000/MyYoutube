package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.*;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.vo.*;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {
    private final IContentFeedService feeds;
    private final ContentReadService reads;
    private final ContentSettingsService settings;
    private final ContentDownloadService downloads;
    private final ContentPlaybackService playback;

    @GetMapping("/{contentId}/playback")
    public Result<ContentPlaybackVO> playback(@PathVariable Long contentId) {
        return Result.success(playback.get(contentId, userId()));
    }
    @PutMapping("/{contentId}/playback")
    public Result<Void> playback(@PathVariable Long contentId, @RequestBody cn.diaojiahong.myyoutube.content.domain.dto.ContentPlaybackDTO body) {
        playback.save(contentId, userId(), body); return Result.success();
    }

    @GetMapping("/{contentId}")
    public Result<ContentDetailVO> detail(@PathVariable Long contentId) {
        return Result.success(reads.detail(contentId, userId()));
    }
    @GetMapping("/{contentId}/subtitles")
    public Result<String> subtitles(@PathVariable Long contentId) {
        return Result.success(reads.subtitles(contentId, userId()));
    }
    @PutMapping("/{contentId}/settings")
    public Result<Void> settings(@PathVariable Long contentId, @RequestBody ContentSettingsService.Settings body) {
        settings.update(contentId, userId(), body); return Result.success();
    }
    @DeleteMapping("/{contentId}")
    public Result<Void> delete(@PathVariable Long contentId) {
        settings.delete(contentId, userId()); return Result.success();
    }
    @PostMapping("/{contentId}/download")
    public Result<ContentDownloadService.Download> download(@PathVariable Long contentId) {
        return Result.success(downloads.create(contentId, userId()));
    }
    @GetMapping("/download-tasks/{taskId}")
    public Result<ContentDownloadService.Download> downloadStatus(@PathVariable Long taskId) {
        return Result.success(downloads.status(taskId, userId()));
    }
    @GetMapping("/author/{authorId}")
    public Result<PageResult<ContentFeedVO>> byAuthor(@PathVariable Long authorId, @ModelAttribute ContentPageQuery query) {
        return Result.success(feeds.byAuthor(userId(), authorId, query));
    }
    @GetMapping("/follow/list")
    public Result<PageResult<ContentFeedVO>> follow(@ModelAttribute ContentPageQuery query) {
        return Result.success(feeds.followFeed(userId(), query));
    }
    @GetMapping("/feed")
    public Result<PageResult<ContentFeedVO>> feed(@ModelAttribute ContentPageQuery query) {
        return Result.success(feeds.feed(userId(), query));
    }
    @GetMapping("/list")
    public Result<PageResult<ContentFeedVO>> list(@ModelAttribute ContentPageQuery query, @RequestParam(required = false) String sort,
                                                @RequestParam(required = false) String category) {
        var order = ContentSortEnum.fromField(sort);
        if (sort != null && order == null)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "排序方式无效");
        return Result.success(feeds.list(userId(), order, category, query));
    }
    @GetMapping("/mine")
    public Result<PageResult<ContentFeedVO>> mine(@ModelAttribute ContentPageQuery query) {
        return Result.success(feeds.mine(userId(), query));
    }
    private static Long userId() {
        String id = UserContext.getUserId();
        if (id == null) throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        return Long.valueOf(id);
    }
}
