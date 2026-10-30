package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchUserVO;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchVideoVO;
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

    /**
     * 用户搜索视频内容(可以搜索视频标题、描述、标签、用户昵称等)
     * @param query
     * @return
     */
    @Operation(
            summary = "搜索内容",
            description = "综合首批返回上限为 max(pageSize, 配置首批数量)，后续仅关键词。默认配置下 pageSize=10 时首批最多 30 条、后续最多 10 条；最新排序始终最多 pageSize 条。过滤或补取限制可导致短页，返回 items/nextCursor，不返回总数")
    @GetMapping("/video")
    public Result<CursorPage<SearchVideoVO, String>> searchVideo(@ModelAttribute ContentSearchCursorQuery query) {
        return Result.success(searchService.searchVideo(query));
    }

    /**
     * 用户搜索用户(可以搜索用户昵称、用户简介等)
     * @param pageQuery
     * @return
     */
    @Operation(summary = "用户搜索用户")
    @GetMapping("/user")
    public Result<PageResult<SearchUserVO>> searchUser(@ModelAttribute SearchUserPageQuery pageQuery) {
        return Result.success(searchService.searchUser(pageQuery));
    }

    /**
     * 根据用户输入进行联想提示搜索
     * @param keyword
     * @return
     */
    @Operation(summary = "根据用户输入提示搜索")
    @GetMapping("/prompt")
    public Result<List<String>> prompt(@RequestParam String keyword) {
        return Result.success(searchService.prompt(keyword));
    }


    /**
     * 用户点击标签,根据标签进行视频搜索
     * @param pageQuery
     * @return
     */
    @Operation(summary = "根据标签搜索内容", description = "游标分页，过滤不可见内容，预算用尽时可能返回短页")
    @GetMapping("/tag")
    public Result<CursorPage<SearchVideoVO, String>> searchByTag(@ModelAttribute TagSearchCursorQuery pageQuery) {
        return Result.success(searchService.searchByTag(pageQuery));
    }

    /**
     * 热搜榜
     * @return
     */
    @Operation(summary = "热搜榜")
    @GetMapping("/hot")
    public Result<List<String>> hot() {
        return Result.success(searchService.hot());
    }
}
