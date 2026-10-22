package cn.diaojiahong.myyoutube.history.service;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.content.service.ContentAccessService;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.history.domain.po.UserViewHistory;
import cn.diaojiahong.myyoutube.history.domain.vo.HistoryVO;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final ContentAccessService access;
    private final UserPort userPort;
    private final IUserFollowService userFollowService;
    private final S3StorageUtil storageUtil;

    @Override
    public void record(Long contentId) {
        Long userId = getUserId();
        access.readable(contentId, userId);
        historyMapper.upsert(userId, contentId);
        return;
    }

    @Override
    public PageResult<HistoryVO> page(ContentPageQuery pageQuery) {
        Long userId = getUserId();
        pageQuery.mediaType();
        int total = historyMapper.selectHistoryCount(userId, pageQuery.getType());
        int totalPages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        int offset = (pageQuery.getPageNo() - 1) * pageQuery.getPageSize();
        List<Long> contentIds = historyMapper.selectHistoryIds(userId, pageQuery.getType(), offset, pageQuery.getPageSize());
        if (contentIds.isEmpty()) {

            return PageResult.of(total, totalPages, List.of());
        }

        // toList 保序: 摘要装配按入参顺序返回, 即 view_time 倒序
        List<ContentSummary> summaries = contentSummaryPort.listByIdsOrdered(
                new ContentSummaryPort.SummaryQuery(contentIds.stream().map(String::valueOf).toList(), userId));
        // 内容被删的条目不再回显, 以摘要装配结果为准
        if (summaries.isEmpty()) {

            return PageResult.of(total, totalPages, List.of());
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
        List<UserSummary> authors = userPort.listByIds(authorIds);
        Map<Long, String> authorNicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> authorAvatarUrls = UserSummaries.toAvatarMap(authors);
        List<Boolean> isFollows = userFollowService.isFollowOnList(authorIds, userId);
        for (int i = 0; i < voList.size(); i++) {

            HistoryVO vo = voList.get(i);
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
            vo.setIsFollow(isFollows.get(i));
        }

        return PageResult.of(total, totalPages, voList);
    }

    @Override
    public void removeOne(Long contentId) {
        Long userId = getUserId();
        lambdaUpdate()
                .eq(UserViewHistory::getUserId, userId)
                .eq(UserViewHistory::getContentId, contentId)
                .remove();
        return;
    }

    @Override
    public void clear() {
        Long userId = getUserId();
        lambdaUpdate().eq(UserViewHistory::getUserId, userId).remove();
        return;
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
