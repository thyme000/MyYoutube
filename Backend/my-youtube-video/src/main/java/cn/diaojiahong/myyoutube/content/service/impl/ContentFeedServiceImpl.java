package cn.diaojiahong.myyoutube.content.service.impl;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.content.service.IContentFeedService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.constants.ContentLikeConstants;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.ContentSummaryPort;
import cn.diaojiahong.myyoutube.video.api.dto.ContentSummary;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    @Override
    public PageResult<ContentFeedVO> feed(Long userId, PageQuery pageQuery) {
        List<Long> ids = recommendPort.recommend(new RecommendPort.RecommendQuery(
                userId, null, pageQuery.getPageNo(), pageQuery.getPageSize()));
        List<ContentFeedVO> list = assemble(userId, ids.stream().map(String::valueOf).collect(Collectors.toSet()));
        return approximatePage(list, pageQuery);
    }

    @Override
    public PageResult<ContentFeedVO> list(Long userId, VideoSortEnum sort, String category, PageQuery pageQuery) {
        Set<String> ids;
        long total;
        if (sort == VideoSortEnum.RECOMMENDED) {
            ids = recommendPort.recommend(new RecommendPort.RecommendQuery(
                    userId, category, pageQuery.getPageNo(), pageQuery.getPageSize()))
                    .stream().map(String::valueOf).collect(Collectors.toSet());
            total = pageQuery.getPageNo() * (long) pageQuery.getPageSize() + 1;
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

        Map<Long, String> nicknames = userPort.getNicknames(summaries.stream().map(ContentSummary::getAuthorId).toList());
        Map<Long, String> avatars = userPort.getAvatars(summaries.stream().map(ContentSummary::getAuthorId).toList());
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
