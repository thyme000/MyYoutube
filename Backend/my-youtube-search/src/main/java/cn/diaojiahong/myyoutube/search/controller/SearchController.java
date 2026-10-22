package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoByTagPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
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
    @Operation(summary = "用户搜索视频")
    @GetMapping("/video")
    public Result<PageResult<SearchVideoVO>> searchVideo(@ModelAttribute SearchVideoPageQuery query) {
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
    @Operation(summary = "用户点击标签,根据标签进行视频搜索")
    @GetMapping("/tag")
    public Result<PageResult<SearchVideoVO>> searchByTag(@ModelAttribute SearchVideoByTagPageQuery pageQuery) {
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
