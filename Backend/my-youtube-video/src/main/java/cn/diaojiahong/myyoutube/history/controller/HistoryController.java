package cn.diaojiahong.myyoutube.history.controller;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.history.domain.vo.HistoryVO;
import cn.diaojiahong.myyoutube.history.service.IHistoryService;
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
 * 用户浏览历史 前端控制器(视频/动态同池)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
@Tag(name = "浏览历史模块")
public class HistoryController {

    private final IHistoryService historyService;

    @Operation(summary = "记录观看历史(重复观看自动置顶)")
    @PutMapping("/{contentId}")
    public Result<Void> record(@PathVariable Long contentId) {
        historyService.record(contentId);
        return Result.success();
    }

    @Operation(summary = "我的浏览历史分页")
    @GetMapping("/page")
    public Result<PageResult<HistoryVO>> page(@ModelAttribute PageQuery pageQuery) {
        return Result.success(historyService.page(pageQuery));
    }

    @Operation(summary = "删除单条历史")
    @DeleteMapping("/{contentId}")
    public Result<Void> removeOne(@PathVariable Long contentId) {
        historyService.removeOne(contentId);
        return Result.success();
    }

    @Operation(summary = "清空我的历史")
    @DeleteMapping
    public Result<Void> clear() {
        historyService.clear();
        return Result.success();
    }
}
