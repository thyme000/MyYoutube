package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchUserVO;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchContentVO;
import cn.diaojiahong.myyoutube.search.service.ISearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
@Tag(name = "搜索模块")
public class SearchController {

    private final ISearchService searchService;

    @Operation(
            summary = "搜索内容",
            description = "可搜索标题、描述、标签、用户昵称等。条数由服务端决定：综合首批用配置的首批数量，最新排序和后续页用配置的每页条数。过滤或补取限制可导致短页。返回 items、nextCursor、hasMore，是否继续只看 hasMore；不返回总数")
    @GetMapping("/video")
    public Result<CursorPage<SearchContentVO, String>> searchContent(@ModelAttribute ContentSearchCursorQuery query) {
        return Result.success(searchService.searchContent(query));
    }

    @Operation(summary = "用户搜索用户", description = "可搜索用户昵称、用户简介等")
    @GetMapping("/user")
    public Result<PageResult<SearchUserVO>> searchUser(@ModelAttribute SearchUserPageQuery pageQuery) {
        return Result.success(searchService.searchUser(pageQuery));
    }

    @Operation(summary = "根据用户输入提示搜索")
    @GetMapping("/prompt")
    public Result<List<String>> prompt(@RequestParam String keyword) {
        return Result.success(searchService.prompt(keyword));
    }

    @Operation(summary = "根据标签搜索内容", description = "游标分页，过滤不可见内容，预算用尽时可能返回短页")
    @GetMapping("/tag")
    public Result<CursorPage<SearchContentVO, String>> searchByTag(@ModelAttribute TagSearchCursorQuery pageQuery) {
        return Result.success(searchService.searchByTag(pageQuery));
    }

    @Operation(summary = "热搜榜")
    @GetMapping("/hot")
    public Result<List<String>> hot() {
        return Result.success(searchService.hot());
    }
}
