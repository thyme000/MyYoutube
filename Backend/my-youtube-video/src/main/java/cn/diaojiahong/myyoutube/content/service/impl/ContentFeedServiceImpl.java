package cn.diaojiahong.myyoutube.content.service.impl;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategy;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.content.service.IContentFeedService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentFeedServiceImpl implements IContentFeedService {

    private static final long URL_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    private final IContentService contentService;
    private final ContentSummaryPort contentSummaryPort;
    private final ContentLikeMapper contentLikeMapper;
    private final IUserFollowService userFollowService;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final RecommendPort recommendPort;
    private final VideoMapper videoMapper;
    private final ContentTypeStrategyRegistry strategyRegistry;

    @Override
    public PageResult<ContentFeedVO> feed(Long userId, PageQuery pageQuery) {
        List<Long> ids = recommendPort.recommend(new RecommendPort.RecommendQuery(
                userId, null, null, pageQuery.getPageNo(), pageQuery.getPageSize()));
        List<ContentFeedVO> list = assemble(userId, ids.stream().map(String::valueOf).collect(Collectors.toSet()));
        return approximatePage(list, pageQuery);
    }

    @Override
    public PageResult<ContentFeedVO> list(Long userId, VideoSortEnum sort, String category, PageQuery pageQuery) {
        Set<String> ids;
        long total;
        if (sort == VideoSortEnum.RECOMMENDED) {
            ids = recommendPort.recommend(new RecommendPort.RecommendQuery(
                    userId, null, category, pageQuery.getPageNo(), pageQuery.getPageSize()))
                    .stream().map(String::valueOf).collect(Collectors.toSet());
            // 推荐流没有真实总数: 满页时提示"还有下一页", 不足一页即到底;
            // 无条件 +1 会让前端在召回为空后仍无限翻页
            total = ids.size() < pageQuery.getPageSize()
                    ? (long) (pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + ids.size()
                    : pageQuery.getPageNo() * (long) pageQuery.getPageSize() + 1;
        } else if (category != null && !category.isBlank()) {
            // 分类是视频域字段: 分类过滤时仅视频
            Page<Video> page = videoMapper.selectPage(pageQuery.toPage(),
                    new LambdaQueryWrapper<Video>()
                            .select(Video::getId)
                            .eq(Video::getIsDelete, 0)
                            .eq(Video::getStatus, VideoStatusEnum.PUBLISHED)
                            .eq(Video::getVisibility, "public")
                            .eq(Video::getCategory, category)
                            .ne(Video::getAuthorId, userId)
                            .orderByDesc(Video::getCreateTime));
            ids = page.getRecords().stream().map(v -> String.valueOf(v.getId())).collect(Collectors.toSet());
            total = page.getTotal();
        } else {
            LambdaQueryWrapper<Content> q = new LambdaQueryWrapper<Content>()
                    .eq(Content::getStatus, VideoStatusEnum.PUBLISHED.getCode())
                    .eq(Content::getVisibility, "public")
                    .ne(Content::getAuthorId, userId);
            applyContentSort(q, sort);
            Page<Content> page = contentService.page(pageQuery.toPage(), q);
            ids = page.getRecords().stream().map(c -> String.valueOf(c.getId())).collect(Collectors.toSet());
            total = page.getTotal();
        }
        List<ContentFeedVO> list = assemble(userId, ids);
        PageResult<ContentFeedVO> result = PageResult.of(total,
                (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize(), list);
        return result;
    }

    @Override
    public PageResult<ContentFeedVO> mine(Long userId, PageQuery pageQuery) {
        // 我的内容: 不做公开性过滤, 创作中心要展示审核状态
        Page<Content> page = contentService.page(pageQuery.toPage(),
                new LambdaQueryWrapper<Content>()
                        .eq(Content::getAuthorId, userId)
                        .orderByDesc(Content::getCreateTime));
        List<Long> ids = page.getRecords().stream().map(Content::getId).toList();
        if (ids.isEmpty()) {
            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        Map<Long, Content> contents = page.getRecords().stream()
                .collect(Collectors.toMap(Content::getId, Function.identity()));

        UserSummary author = userPort.getUser(userId);
        String nickname = author != null ? author.getNickname() : null;
        String avatarUrl = author != null ? author.getAvatarUrl() : null;

        Map<Long, Object> rows = loadRowsByContent(page.getRecords());
        List<ContentFeedVO> list = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Content c = contents.get(id);
            Object row = rows.get(id);
            if (c == null || row == null) {
                continue;
            }
            ContentTypeStrategy strategy = strategyRegistry.get(c.getType());
            if (strategy == null) {
                continue;
            }
            ContentFeedVO vo = new ContentFeedVO();
            vo.setId(String.valueOf(id));
            vo.setType(strategy.type().getCode());
            vo.setLikeCount(c.getLikeCount());
            vo.setPlayCount(c.getPlayCount());
            vo.setCommentCount(c.getCommentCount());
            vo.setShareCount(c.getShareCount());
            vo.setCreateTime(c.getCreateTime());
            vo.setAuthorId(userId);
            vo.setAuthorNickname(nickname);
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(avatarUrl, URL_SIGN_EXPIRE_SECONDS));
            strategy.fillFeedVO(vo, c, row, true, URL_SIGN_EXPIRE_SECONDS);
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), page.getPages(), list);
    }

    @Override
    public PageResult<ContentFeedVO> byAuthor(Long viewerId, Long authorId, PageQuery pageQuery) {
        boolean self = viewerId != null && viewerId.equals(authorId);
        LambdaQueryWrapper<Content> q = new LambdaQueryWrapper<Content>()
                .eq(Content::getAuthorId, authorId)
                .eq(Content::getStatus, VideoStatusEnum.PUBLISHED.getCode());
        if (!self) {
            q.eq(Content::getVisibility, "public");
        }
        q.orderByDesc(Content::getCreateTime);
        Page<Content> page = contentService.page(pageQuery.toPage(), q);
        if (page.getRecords().isEmpty()) {
            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        List<Boolean> follows = viewerId == null ? List.of(Boolean.FALSE)
                : userFollowService.isFollowOnList(List.of(authorId), viewerId);
        Map<Long, Boolean> followByAuthor = Map.of(authorId, !follows.isEmpty() && Boolean.TRUE.equals(follows.get(0)));
        List<ContentFeedVO> list = buildContentFeedRows(page.getRecords(), viewerId, followByAuthor);
        return PageResult.of(page.getTotal(), page.getPages(), list);
    }

    @Override
    public PageResult<ContentFeedVO> followFeed(Long userId, PageQuery pageQuery) {
        List<Long> followIds = userFollowService.getFollowIds(userId);
        if (followIds == null || followIds.isEmpty()) {
            return PageResult.of(0, 0, List.of());
        }
        Page<Content> page = contentService.page(pageQuery.toPage(),
                new LambdaQueryWrapper<Content>()
                        .in(Content::getAuthorId, followIds)
                        .eq(Content::getStatus, VideoStatusEnum.PUBLISHED.getCode())
                        .eq(Content::getVisibility, "public")
                        .orderByDesc(Content::getCreateTime));
        if (page.getRecords().isEmpty()) {
            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        Map<Long, Boolean> followByAuthor = followIds.stream()
                .collect(Collectors.toMap(Function.identity(), id -> true));
        List<ContentFeedVO> list = buildContentFeedRows(page.getRecords(), userId, followByAuthor);
        return PageResult.of(page.getTotal(), page.getPages(), list);
    }

    /**
     * content 页行 → 卡片 VO: 补齐视频/动态媒体字段、作者信息与点赞态; followByAuthor 提供关注态
     */
    private List<ContentFeedVO> buildContentFeedRows(List<Content> rows, Long viewerId, Map<Long, Boolean> followByAuthor) {
        List<Long> ids = rows.stream().map(Content::getId).toList();
        Map<Long, Object> rowMap = loadRowsByContent(rows);
        List<Long> authorIds = rows.stream().map(Content::getAuthorId).distinct().toList();
        List<UserSummary> authors = userPort.listByIds(authorIds);
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> avatars = UserSummaries.toAvatarMap(authors);
        Set<Long> likedIds = viewerId == null ? Set.of() : contentLikeMapper.selectList(
                        new LambdaQueryWrapper<ContentLike>()
                                .eq(ContentLike::getUserId, viewerId)
                                .eq(ContentLike::getIsLiked, ContentLikeConstants.LIKE)
                                .in(ContentLike::getContentId, ids))
                .stream().map(ContentLike::getContentId).collect(Collectors.toSet());

        List<ContentFeedVO> list = new ArrayList<>(rows.size());
        for (Content c : rows) {
            Long id = c.getId();
            Object row = rowMap.get(id);
            if (row == null) {
                continue;
            }
            ContentTypeStrategy strategy = strategyRegistry.get(c.getType());
            if (strategy == null) {
                continue;
            }
            ContentFeedVO vo = new ContentFeedVO();
            vo.setId(String.valueOf(id));
            vo.setType(strategy.type().getCode());
            vo.setLikeCount(c.getLikeCount());
            vo.setPlayCount(c.getPlayCount());
            vo.setCommentCount(c.getCommentCount());
            vo.setShareCount(c.getShareCount());
            vo.setCreateTime(c.getCreateTime());
            vo.setAuthorId(c.getAuthorId());
            vo.setAuthorNickname(nicknames.get(c.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(avatars.get(c.getAuthorId()), URL_SIGN_EXPIRE_SECONDS));
            vo.setIsLike(likedIds.contains(id));
            vo.setIsFollow(Boolean.TRUE.equals(followByAuthor.get(c.getAuthorId())));
            strategy.fillFeedVO(vo, c, row, false, URL_SIGN_EXPIRE_SECONDS);
            list.add(vo);
        }
        return list;
    }

    /** 按内容行的类型分派各策略批量取领域实体, key=contentId */
    private Map<Long, Object> loadRowsByContent(List<Content> rows) {
        Map<ContentTypeEnum, Set<Long>> idsByType = new EnumMap<>(ContentTypeEnum.class);
        for (Content c : rows) {
            idsByType.computeIfAbsent(c.getType(), k -> new HashSet<>()).add(c.getId());
        }
        Map<Long, Object> rowMap = new HashMap<>();
        for (Map.Entry<ContentTypeEnum, Set<Long>> entry : idsByType.entrySet()) {
            ContentTypeStrategy strategy = strategyRegistry.get(entry.getKey());
            if (strategy == null) {
                continue;
            }
            rowMap.putAll(strategy.loadRows(entry.getValue()));
        }
        return rowMap;
    }

    private void applyContentSort(LambdaQueryWrapper<Content> q, VideoSortEnum sort) {
        if (sort == VideoSortEnum.MOST_LIKED) {
            q.orderByDesc(Content::getLikeCount);
        } else if (sort == VideoSortEnum.MOST_SHARED) {
            q.orderByDesc(Content::getShareCount);
        } else if (sort == VideoSortEnum.MOST_PLAYED) {
            q.orderByDesc(Content::getPlayCount);
        } else {
            q.orderByDesc(Content::getCreateTime);
        }
    }

    /**
     * ids → 摘要 → 卡片 VO: 签名媒体地址, 填作者昵称/头像/点赞/关注状态
     */
    private List<ContentFeedVO> assemble(Long userId, Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ContentSummary> summaries = contentSummaryPort.listByIdsOrdered(ids);
        if (summaries.isEmpty()) {
            return List.of();
        }

        List<UserSummary> authors = userPort.listByIds(summaries.stream().map(ContentSummary::getAuthorId).toList());
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> avatars = UserSummaries.toAvatarMap(authors);
        Set<Long> likedIds = likedContentIds(userId, summaries);
        List<Boolean> follows = userFollowService.isFollowOnList(
                summaries.stream().map(ContentSummary::getAuthorId).toList(), userId);

        List<ContentFeedVO> list = new ArrayList<>(summaries.size());
        for (int i = 0; i < summaries.size(); i++) {
            ContentSummary s = summaries.get(i);
            ContentFeedVO vo = new ContentFeedVO();
            vo.setId(String.valueOf(s.getId()));
            vo.setType(s.getType());
            vo.setTitle(s.getTitle());
            vo.setDescription(s.getDescription());
            vo.setCoverUrl(storageUtil.signGetUrl(s.getCoverUrl(), URL_SIGN_EXPIRE_SECONDS));
            vo.setVideoUrl(s.getVideoUrl() != null
                    ? storageUtil.signGetUrl(s.getVideoUrl(), URL_SIGN_EXPIRE_SECONDS) : null);
            vo.setDuration(s.getDuration());
            vo.setTotalDurationMs(s.getTotalDurationMs());
            vo.setLikeCount(s.getLikeCount());
            vo.setPlayCount(s.getPlayCount());
            vo.setCommentCount(s.getCommentCount());
            vo.setShareCount(s.getShareCount());
            vo.setAuthorId(s.getAuthorId());
            vo.setAuthorNickname(nicknames.get(s.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(avatars.get(s.getAuthorId()), URL_SIGN_EXPIRE_SECONDS));
            vo.setCreateTime(s.getCreateTime());
            vo.setIsLike(likedIds.contains(s.getId()));
            Boolean follow = follows.get(i);
            vo.setIsFollow(follow != null && follow);
            list.add(vo);
        }
        return list;
    }

    private Set<Long> likedContentIds(Long userId, List<ContentSummary> summaries) {
        List<Long> ids = summaries.stream().map(ContentSummary::getId).toList();
        List<ContentLike> likes = contentLikeMapper.selectList(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getUserId, userId)
                .eq(ContentLike::getIsLiked, ContentLikeConstants.LIKE)
                .in(ContentLike::getContentId, ids));
        return likes.stream().map(ContentLike::getContentId).collect(Collectors.toSet());
    }

    private PageResult<ContentFeedVO> approximatePage(List<ContentFeedVO> list, PageQuery pageQuery) {
        long total = list.size() < pageQuery.getPageSize()
                ? (long) (pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + list.size()
                : (long) pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();
        return PageResult.of(total, pages, list);
    }
}
