package cn.diaojiahong.myyoutube.collect.controller;

import cn.diaojiahong.myyoutube.collect.domain.vo.CollectVO;
import cn.diaojiahong.myyoutube.collect.service.IContentCollectService;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 内容收藏 前端控制器(视频/动态同池)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
@RestController
@RequestMapping("/collect")
@RequiredArgsConstructor
@Tag(name = "收藏模块")
public class ContentCollectController {

    private final IContentCollectService contentCollectService;

    @Operation(summary = "收藏内容")
    @PutMapping("/{contentId}")
    public Result<Void> collect(@PathVariable Long contentId) {
        contentCollectService.collect(contentId);
        return Result.success();
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/{contentId}")
    public Result<Void> cancelCollect(@PathVariable Long contentId) {
        contentCollectService.cancelCollect(contentId);
        return Result.success();
    }

    @Operation(summary = "我的收藏分页")
    @GetMapping("/page")
    public Result<PageResult<CollectVO>> page(@ModelAttribute ContentPageQuery pageQuery) {
        return Result.success(contentCollectService.page(pageQuery));
    }

    @Operation(summary = "我是否收藏了该内容")
    @GetMapping("/{contentId}/status")
    public Result<Boolean> status(@PathVariable Long contentId) {
        return Result.success(contentCollectService.status(contentId));
    }
}
