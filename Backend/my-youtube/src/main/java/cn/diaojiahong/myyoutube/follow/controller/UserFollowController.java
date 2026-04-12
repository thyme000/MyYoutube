package cn.diaojiahong.myyoutube.follow.controller;


import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserRelationshipVO;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 用户关注关系表 前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@RestController
@RequestMapping("/follow")
@Tag(name = "关注模块")
@RequiredArgsConstructor
public class UserFollowController {

    private final IUserFollowService userFollowService;

    /**
     * 关注用户
     * @param authorId
     * @return
     */
    @Operation(summary = "关注用户")
    @PostMapping("/{authorId}")
    public Result<Void> follow(@PathVariable Long authorId) {
        return userFollowService.follow(authorId);
    }

    /**
     * 取关用户
     * @param authorId
     * @return
     */
    @Operation(summary = "取关用户")
    @DeleteMapping("/{authorId}")
    public Result<Void> cancelFollow(@PathVariable Long authorId) {
        return userFollowService.cancelFollow(authorId);
    }

    /**
     * 获取用户的关注列表
     * @return
     */
    @Operation(summary = "获取用户的关注列表")
    @GetMapping("/list")
    public Result<PageResult<UserRelationshipVO>> getUserFollowList(@ModelAttribute PageQuery pageQuery) {
        return userFollowService.getUserFollowList(pageQuery);
    }


    /**
     * 查看用户是否关注了该作者
     * @param authorId 作者id
     * @return
     */
    @Operation(summary = "查看用户是否关注了该作者")
    @GetMapping("/isFollow/{authorId}")
    public Result<Boolean> isFollow(@PathVariable Long authorId) {
        return userFollowService.isFollow(authorId);
    }


    /**
     * 用户查看粉丝列表
     * @param pageQuery 分页参数
     * @return
     */
    @Operation(summary = "用户查看粉丝列表")
    @GetMapping("/fans")
    public Result<PageResult<UserRelationshipVO>> getFansList(@ModelAttribute PageQuery pageQuery) {
        return userFollowService.getFansList(pageQuery);
    }

    /**
     * 用户移除某位粉丝
     * @param followerId 粉丝id
     * @return
     */
    @Operation(summary = "用户移除粉丝")
    @PostMapping("/remove/{followerId}")
    public Result<Void> removeFan(@PathVariable Long followerId) {
        return userFollowService.removeFan(followerId);
    }

    /**
     * 查询用户的互关用户
     * @param pageQuery
     * @return
     */
    @Operation(summary = "查询互关用户")
    @GetMapping("/interrelated")
    public Result<PageResult<UserRelationshipVO>> interrelated(@ModelAttribute PageQuery pageQuery) {
        return userFollowService.getInterrelated(pageQuery);
    }
}
