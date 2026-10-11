package cn.diaojiahong.myyoutube.content.service.impl;

import cn.diaojiahong.myyoutube.common.page.*;
import cn.diaojiahong.myyoutube.content.domain.dto.ContentPageQuery;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.service.*;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.content.support.ContentCards;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentFeedServiceImpl implements IContentFeedService {
    private static final long SIGN_SECONDS = 7200;
    private final IContentService contents;
    private final ContentLikeMapper likes;
    private final IUserFollowService follows;
    private final UserPort users;
    private final S3StorageUtil storage;
    private final RecommendPort recommendations;
    private final ContentTypeStrategyRegistry strategies;

    @Override public PageResult<ContentFeedVO> feed(Long userId, ContentPageQuery query) {
        return recommended(userId, null, query.mediaType(), query);
    }
    @Override public PageResult<ContentFeedVO> list(Long userId, ContentSortEnum sort, String category, ContentPageQuery query) {
        var type = query.mediaType();
        if (sort == ContentSortEnum.RECOMMENDED) return recommended(userId, category, type, query);
        var filter = published().eq(Content::getVisibility, "public").ne(userId != null, Content::getAuthorId, userId)
                .eq(category != null && !category.isBlank(), Content::getCategoryCode, category)
                .eq(type != null, Content::getType, type);
        if (sort == ContentSortEnum.MOST_LIKED) filter.orderByDesc(Content::getLikeCount);
        else if (sort == ContentSortEnum.MOST_SHARED) filter.orderByDesc(Content::getShareCount);
        else if (sort == ContentSortEnum.MOST_PLAYED) filter.orderByDesc(Content::getPlayCount);
        else filter.orderByDesc(Content::getCreateTime);
        return page(userId, filter, query);
    }
    @Override public PageResult<ContentFeedVO> mine(Long userId, ContentPageQuery query) {
        var type = query.mediaType();
        return page(userId, new LambdaQueryWrapper<Content>().eq(type != null, Content::getType, type).eq(Content::getIsDelete, 0).eq(Content::getAuthorId, userId).orderByDesc(Content::getCreateTime), query);
    }
    @Override public PageResult<ContentFeedVO> byAuthor(Long viewer, Long author, ContentPageQuery query) {
        var type = query.mediaType();
        return page(viewer, published().eq(type != null, Content::getType, type).eq(Content::getAuthorId, author)
                .eq(!author.equals(viewer), Content::getVisibility, "public").orderByDesc(Content::getCreateTime), query);
    }
    @Override public PageResult<ContentFeedVO> followFeed(Long userId, ContentPageQuery query) {
        var type = query.mediaType();
        var ids = follows.getFollowIds(userId);
        if (ids.isEmpty()) return PageResult.of(0, 0, List.of());
        return page(userId, published().eq(type != null, Content::getType, type).in(Content::getAuthorId, ids).eq(Content::getVisibility, "public").orderByDesc(Content::getCreateTime), query);
    }
    private PageResult<ContentFeedVO> page(Long viewer, LambdaQueryWrapper<Content> filter, ContentPageQuery query) {
        var page = contents.page(query.toPage(), filter);
        return PageResult.of(page.getTotal(), page.getPages(), assemble(viewer, page.getRecords()));
    }
    private PageResult<ContentFeedVO> recommended(Long viewer, String category, ContentTypeEnum type, ContentPageQuery query) {
        var ids = recommendations.recommend(new RecommendPort.RecommendQuery(viewer, type == null ? null : type.getCode(), category, query.getPageNo(), query.getPageSize()));
        if (ids.isEmpty()) return PageResult.of(0, 0, List.of());
        var byId = contents.list(published().in(Content::getId, ids).eq(Content::getVisibility, "public")
                .eq(type != null, Content::getType, type).eq(category != null && !category.isBlank(), Content::getCategoryCode, category))
                .stream().collect(Collectors.toMap(Content::getId, c -> c));
        var rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        long total = (long) (query.getPageNo() - 1) * query.getPageSize() + ids.size() + (ids.size() == query.getPageSize() ? 1 : 0);
        return PageResult.of(total, (total + query.getPageSize() - 1) / query.getPageSize(), assemble(viewer, rows));
    }
    public List<ContentFeedVO> assemble(Long viewer, List<Content> rows) {
        if (rows.isEmpty()) return List.of();
        var authorIds = rows.stream().map(Content::getAuthorId).distinct().toList();
        var authors = users.listByIds(authorIds);
        var names = UserSummaries.toNicknameMap(authors);
        var avatars = UserSummaries.toAvatarMap(authors);
        var followed = viewer == null ? List.<Boolean>of() : follows.isFollowOnList(authorIds, viewer);
        var followMap = new HashMap<Long, Boolean>();
        for (int i = 0; i < followed.size(); i++) followMap.put(authorIds.get(i), followed.get(i));
        var liked = viewer == null ? Set.<Long>of() : likes.selectList(new LambdaQueryWrapper<ContentLike>()
                .eq(ContentLike::getUserId, viewer).eq(ContentLike::getIsLiked, 1).in(ContentLike::getContentId, rows.stream().map(Content::getId).toList()))
                .stream().map(ContentLike::getContentId).collect(Collectors.toSet());
        var media = new HashMap<Long, cn.diaojiahong.myyoutube.common.media.ContentMedia>();
        for (var group : rows.stream().collect(Collectors.groupingBy(Content::getType)).entrySet())
            media.putAll(strategies.get(group.getKey()).loadMedia(group.getValue().stream().map(Content::getId).toList()));
        var result = new ArrayList<ContentFeedVO>();
        for (var row : rows) {
            if (!media.containsKey(row.getId())) continue;
            var vo = BeanUtil.copyProperties(ContentCards.summary(row, media.get(row.getId())), ContentFeedVO.class);
            vo.setAuthorNickname(names.get(row.getAuthorId()));
            vo.setAuthorAvatarUrl(storage.signGetUrl(avatars.get(row.getAuthorId()), SIGN_SECONDS));
            vo.setCoverUrl(storage.signGetUrl(vo.getCoverUrl(), SIGN_SECONDS));
            vo.setVideoUrl(storage.signGetUrl(vo.getVideoUrl(), SIGN_SECONDS));
            vo.setIsLike(liked.contains(row.getId())); vo.setIsFollow(Boolean.TRUE.equals(followMap.get(row.getAuthorId())));
            result.add(vo);
        }
        return result;
    }
    private LambdaQueryWrapper<Content> published() {
        return new LambdaQueryWrapper<Content>().eq(Content::getIsDelete, 0).eq(Content::getStatus, "published");
    }
}
