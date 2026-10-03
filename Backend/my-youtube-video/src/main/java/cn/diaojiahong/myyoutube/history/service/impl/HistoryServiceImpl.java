package cn.diaojiahong.myyoutube.history.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.history.domain.po.UserViewHistory;
import cn.diaojiahong.myyoutube.history.domain.vo.HistoryVO;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户浏览历史 服务实现: 装配链与赞过列表同构(摘要端口跨类型 + 作者/封面签名补全)
 * </p>
 *
 * @author thyme
 * @since 2026-10-03
 */
@Service
@RequiredArgsConstructor
public class HistoryServiceImpl extends ServiceImpl<UserViewHistoryMapper, UserViewHistory> implements IHistoryService {

    private static final int STORAGE_EXPIRE = 24 * 60 * 60;

    private final UserViewHistoryMapper historyMapper;
    private final ContentSummaryPort contentSummaryPort;
    private final IContentService contentService;
    private final UserPort userPort;
    private final IUserFollowService userFollowService;
    private final S3StorageUtil storageUtil;

    @Override
    public Result<Void> record(Long contentId) {
        Long userId = getUserId();
        if (contentService.getById(contentId) == null) {
            return Result.fail("内容不存在");
        }
        historyMapper.upsert(userId, contentId);
        return Result.success();
    }

    @Override
    public Result<PageResult<HistoryVO>> page(PageQuery pageQuery) {
        Long userId = getUserId();
        int offset = (pageQuery.getPageNo() - 1) * pageQuery.getPageSize();
        List<Long> contentIds = historyMapper.selectHistoryIds(userId, offset, pageQuery.getPageSize());
        if (contentIds.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        // LinkedHashSet 保序: 摘要装配按入参迭代序返回, 即 view_time 倒序
        List<ContentSummary> summaries = contentSummaryPort.listByIdsOrdered(
                contentIds.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new)));
        // 内容被删的条目不再回显, 以摘要装配结果为准
        if (summaries.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        Map<Long, LocalDateTime> viewTimeById = lambdaQuery()
                .eq(UserViewHistory::getUserId, userId)
                .in(UserViewHistory::getContentId, contentIds)
                .list().stream()
                .collect(Collectors.toMap(UserViewHistory::getContentId, UserViewHistory::getViewTime));

        List<HistoryVO> voList = summaries.stream().map(s -> {
            HistoryVO vo = BeanUtil.toBean(s, HistoryVO.class);
            vo.setId(String.valueOf(s.getId()));
            vo.setViewTime(viewTimeById.get(s.getId()));
            return vo;
        }).toList();

        List<Long> authorIds = voList.stream().map(HistoryVO::getAuthorId).toList();
        Map<Long, String> authorNicknames = userPort.getNicknames(authorIds);
        Map<Long, String> authorAvatarUrls = userPort.getAvatars(authorIds);
        List<Boolean> isFollows = userFollowService.isFollowOnList(authorIds, userId);
        for (int i = 0; i < voList.size(); i++) {
            HistoryVO vo = voList.get(i);
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
            vo.setIsFollow(isFollows.get(i));
        }

        int total = historyMapper.selectHistoryCount(userId);
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        return Result.success(PageResult.of(total, totalPages, voList));
    }

    @Override
    public Result<Void> removeOne(Long contentId) {
        Long userId = getUserId();
        lambdaUpdate()
                .eq(UserViewHistory::getUserId, userId)
                .eq(UserViewHistory::getContentId, contentId)
                .remove();
        return Result.success();
    }

    @Override
    public Result<Void> clear() {
        Long userId = getUserId();
        lambdaUpdate().eq(UserViewHistory::getUserId, userId).remove();
        return Result.success();
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
