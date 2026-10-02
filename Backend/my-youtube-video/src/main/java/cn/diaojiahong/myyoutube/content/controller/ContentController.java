package cn.diaojiahong.myyoutube.content.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.service.IContentFeedService;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一内容池消费端接口: 连播流与首页混排网格(视频/动态同池)
 */
@RestController
@RequestMapping("/contents")
@RequiredArgsConstructor
public class ContentController {

    private final IContentFeedService contentFeedService;

    /**
     * 统一消费流(连播): 推荐召回全量混排
     */
    @GetMapping("/feed")
    public Result<PageResult<ContentFeedVO>> feed(@ModelAttribute PageQuery pageQuery) {
        return Result.success(contentFeedService.feed(getUserId(), pageQuery));
    }

    /**
     * 首页混排网格: sort=recommended 走推荐, 其余按计数/时间排序; category 过滤时仅视频
     */
    @GetMapping("/list")
    public Result<PageResult<ContentFeedVO>> list(@ModelAttribute PageQuery pageQuery,
                                                  @RequestParam(required = false) VideoSortEnum sort,
                                                  @RequestParam(required = false) String category) {
        return Result.success(contentFeedService.list(getUserId(), sort, category, pageQuery));
    }

    private static Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
