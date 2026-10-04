package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.FollowVideoVO;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoDetailVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoListVO;
import cn.diaojiahong.myyoutube.video.domain.vo.VideoVoConverter;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IPlaybackService;
import cn.diaojiahong.myyoutube.video.service.IVideoQueryService;
import cn.diaojiahong.myyoutube.video.service.VideoListAssembler;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.PUBLISHED;

/**
 * 视频查询: 推荐流/普通分页/详情/作者列表/我的作品/连播/关注流
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoQueryServiceImpl implements IVideoQueryService {

    private final VideoMapper videoMapper;
    private final RecommendPort recommendPort;
    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final IContentService contentService;
    private final IUserFollowService userFollowService;
    private final IPlaybackService playbackService;
    private final VideoListAssembler assembler;

    @Override
    public PageResult<VideoListVO> getVideoList(VideoPageQuery pageQuery) {
        Long userId = getUserId();

        // 推荐视频
        if (pageQuery.getSort() == VideoSortEnum.RECOMMENDED) {

            List<Long> ids = recommendPort.recommend(new RecommendPort.RecommendQuery(
                    userId,
                    pageQuery.getCategory() != null ? pageQuery.getCategory().getCode() : null,
                    pageQuery.getPageNo(),
                    pageQuery.getPageSize()
            ));
            if (!ids.isEmpty()) {

                // 用 MySQL 按 ids 批量查详情（保持字段一致）
                List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                        .in(Video::getId, ids)
                        .eq(Video::getIsDelete, 0));
                // 保持 ES 返回的顺序 (listByIds不保证返回的顺序)
                Map<Long, Video> map = videos.stream()
                        .collect(Collectors.toMap(Video::getId, v -> v));
                Map<Long, Content> contents = assembler.loadContents(videos);
                List<VideoListVO> list = ids.stream()
                        .map(map::get)
                        .filter(Objects::nonNull)
                        .map(v -> VideoVoConverter.toListVo(v, contents.get(v.getId())))
                        .toList();
                assembler.fillAuthors(list, VideoListVO::getAuthorId,
                        VideoListVO::setAuthorNickname, VideoListVO::setAuthorAvatarUrl);
                assembler.signUrls(list, VideoListVO::getCoverUrl, VideoListVO::setCoverUrl);
                assembler.signUrls(list, VideoListVO::getVideoUrl, VideoListVO::setVideoUrl);

                // 近似值
                long total = list.size() < pageQuery.getPageSize() ?
                        (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + list.size() :
                        (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
                long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

                return PageResult.of(total, pages, list);
            }

        }

        // 普通视频
        Page<Video> page = pageQuery.toPage();
        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getIsDelete, 0);
        q.eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC);

        if (pageQuery.getCategory() != null) {

            q.eq(Video::getCategory, pageQuery.getCategory());
        }
        if (pageQuery.getAuthorId() != null) {

            q.eq(Video::getAuthorId, pageQuery.getAuthorId());
        }
        if (pageQuery.getBegin() != null) {

            q.ge(Video::getCreateTime, pageQuery.getBegin());
        }
        if (pageQuery.getEnd() != null) {

            q.le(Video::getCreateTime, pageQuery.getEnd());
        }
        if (pageQuery.getTags() != null && !pageQuery.getTags().isBlank()) {

            q.like(Video::getTags, pageQuery.getTags().trim());
        }

        // 排除自己的视频
        q.ne(Video::getAuthorId, userId);

        Page<Video> result;
        VideoSortEnum sort = pageQuery.getSort();
        if (sort == VideoSortEnum.MOST_LIKED || sort == VideoSortEnum.MOST_SHARED
                || sort == VideoSortEnum.MOST_PLAYED || sort == VideoSortEnum.RECOMMENDED) {

            // 计数列在 content 表, 计数类排序走联表查询
            result = videoMapper.selectPageSorted(pageQuery.toPage(), userId,
                    pageQuery.getCategory() != null ? pageQuery.getCategory().getCode() : null,
                    pageQuery.getBegin(), pageQuery.getEnd(),
                    pageQuery.getTags() != null && !pageQuery.getTags().isBlank() ? pageQuery.getTags().trim() : null,
                    sort.name());
        } else {
            q.orderByDesc(Video::getCreateTime);
            result = videoMapper.selectPage(pageQuery.toPage(), q.eq(Video::getStatus, PUBLISHED));
        }
        List<VideoListVO> list = assembler.toListVosWithCounts(result.getRecords());
        assembler.fillAuthors(list, VideoListVO::getAuthorId,
                VideoListVO::setAuthorNickname, VideoListVO::setAuthorAvatarUrl);
        assembler.signUrls(list, VideoListVO::getCoverUrl, VideoListVO::setCoverUrl);
        assembler.signUrls(list, VideoListVO::getVideoUrl, VideoListVO::setVideoUrl);
        return PageResult.of(result.getTotal(), result.getPages(), list);
    }

    @Override
    public VideoDetailVO getVideoDetail(Long videoId) {
        Long userId = getUserId();
        Video video = videoMapper.selectById(videoId);

        if (video == null || (video.getIsDelete() != null && video.getIsDelete() != 0)) {

            throw new BizException(BizException.BIZ_ERROR, "视频不存在或未发布～");
        }
        if (!PUBLISHED.getCode().equals(video.getStatus().getCode())) {

            throw new BizException(BizException.BIZ_ERROR, "视频已私密或已被封禁～");
        }
        // 私密视频仅作者本人可看(直链直达也拦)
        if (video.getVisibility() == VideoVisibilityEnum.PRIVATE && !video.getAuthorId().equals(userId)) {

            throw new BizException(BizException.BIZ_ERROR, "无权查看该视频～");
        }

        VideoDetailVO detailVo = VideoVoConverter.toDetailVo(video, contentService.getById(videoId));
        detailVo.setVideoUrl(storageUtil.signGetUrl(detailVo.getVideoUrl(), VideoListAssembler.STORAGE_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setCoverUrl(storageUtil.signGetUrl(detailVo.getCoverUrl(), VideoListAssembler.STORAGE_READ_SIGN_EXPIRE_SECONDS));

        // 查看历史播放记录,看是否有播放记录,如果有完成续播功能
        Long playTime = playbackService.getResumeTime(userId, videoId);
        if (playTime != null) {

            // 说明存在播放记录,则设置播放时间
            detailVo.setPlaybackTime(playTime);
        }

        // 获取作者的信息
        UserSummary author = userPort.getUser(video.getAuthorId());
        if (author == null) {

            throw new BizException(BizException.BIZ_ERROR, "作者不存在");
        }
        detailVo.setAuthorFanCount(author.getFans());
        detailVo.setAuthorAvatarUrl(storageUtil.signGetUrl(author.getAvatarUrl(), VideoListAssembler.STORAGE_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setAuthorNickname(author.getNickname());

        return detailVo;
    }

    @Override
    public PageResult<VideoListVO> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery) {
        Page<Video> result = videoMapper.selectPage(pageQuery.toPage(),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getIsDelete, 0)
                        .eq(Video::getAuthorId, authorId)
                        .eq(Video::getStatus, PUBLISHED)
                        .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                        .orderByDesc(Video::getCreateTime));
        List<VideoListVO> list = assembler.toListVosWithCounts(result.getRecords());
        assembler.fillAuthors(list, VideoListVO::getAuthorId,
                VideoListVO::setAuthorNickname, VideoListVO::setAuthorAvatarUrl);
        assembler.signUrls(list, VideoListVO::getCoverUrl, VideoListVO::setCoverUrl);
        return PageResult.of(result.getTotal(), result.getPages(), list);
    }

    @Override
    public PageResult<VideoListVO> getMyVideoList(VideoPageQuery pageQuery) {
        Long userId = getUserId();

        Page<Video> list = videoMapper.selectPage(pageQuery.toPage(),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getIsDelete, 0)
                        .orderByDesc(Video::getCreateTime));

        List<VideoListVO> result = assembler.toListVosWithCounts(list.getRecords());
        // 封面url
        assembler.signUrls(result, VideoListVO::getCoverUrl, VideoListVO::setCoverUrl);
        // 视频url
        assembler.signUrls(result, VideoListVO::getVideoUrl, VideoListVO::setVideoUrl);

        // 填充点赞信息
        List<Long> videoIds = result.stream().map(v -> {
            return Long.valueOf(v.getId());
        }).toList();
        Map<Long, Boolean> likeMap = assembler.likedMap(userId, videoIds);
        for (VideoListVO vo : result) {

            vo.setIsLike(likeMap.getOrDefault(Long.valueOf(vo.getId()), false));
        }

        log.info("查询用户 {} 的视频列表，共 {} 条", userId, result.size());
        return PageResult.of(list.getTotal(), list.getPages(), result);
    }

    @Override
    public PageResult<ShortsVO> getShortsFeed(PageQuery pageQuery) {
        Long userId = getUserId();
        // 连播池不过滤形态, 全量混排
        List<Long> videoIds = recommendPort.recommend(new RecommendPort.RecommendQuery(
                userId, null, pageQuery.getPageNo(), pageQuery.getPageSize()));
        if (videoIds.isEmpty()) {

            // 直接返回空列表
            return PageResult.of(0,0, Collections.emptyList());
        }

        // 查询视频信息
        List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .in(Video::getId, videoIds)
                .eq(Video::getIsDelete, 0));
        Map<Long, Video> map = videos.stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
        Map<Long, Content> contents = assembler.loadContents(videos);
        List<ShortsVO> voList = videoIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(v -> VideoVoConverter.toShortsVo(v, contents.get(v.getId())))
                .toList();

        // 获取所有作者id
        List<Long> authorIds = voList.stream().map(ShortsVO::getAuthorId).toList();
        if (!authorIds.isEmpty()) {

            // 一次批量取作者摘要, 本地投影昵称与头像
            assembler.fillAuthors(voList, ShortsVO::getAuthorId,
                    ShortsVO::setAuthorNickname, ShortsVO::setAuthorAvatarUrl);
            // 签名封面url
            assembler.signUrls(voList, ShortsVO::getCoverUrl, ShortsVO::setCoverUrl);
            // 签名视频url
            assembler.signUrls(voList, ShortsVO::getVideoUrl, ShortsVO::setVideoUrl);

            // 填充是否关注了该作者
            List<Boolean> followOnList = userFollowService.isFollowOnList(authorIds, userId);
            for (int i = 0; i < voList.size(); i++) {

                Boolean isFollow = followOnList.get(i);
                voList.get(i).setIsFollow(isFollow != null && isFollow);
            }
        }

        // 查看用户是否点赞了这些作品
        Map<Long, Boolean> likeMap = assembler.likedMap(userId, videoIds);
        voList.forEach(vo -> vo.setIsLike(likeMap.getOrDefault(Long.valueOf(vo.getId()), false)));

        // 近似值
        long total = voList.size() < pageQuery.getPageSize() ?
                (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + voList.size() :
                (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return PageResult.of(total, pages, voList);
    }

    @Override
    public PageResult<VideoListVO> getOtherUserVideoList(Long userId, PageQuery pageQuery) {
        // 查询他人公开的视频
        Page<Video> page = videoMapper.selectPage(pageQuery.toPage(),
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                        .eq(Video::getIsDelete, false)
                        .eq(Video::getStatus, VideoStatusEnum.PUBLISHED)
                        .orderByDesc(Video::getCreateTime));
        List<Video> videoList = page.getRecords();
        if (videoList == null || videoList.isEmpty()) {

            return PageResult.of(0,0, Collections.emptyList());
        }
        // 转换为VO
        List<VideoListVO> voList = assembler.toListVosWithCounts(videoList);

        // 获取作者昵称
        UserSummary author = userPort.getUser(userId);
        if (author == null) {

            return PageResult.of(0,0, Collections.emptyList());
        }
        // 填充作者昵称
        voList.forEach(vo -> vo.setAuthorNickname(author.getNickname()));
        // 签名视频封面url
        assembler.signUrls(voList, VideoListVO::getCoverUrl, VideoListVO::setCoverUrl);
        // 签名视频url
        assembler.signUrls(voList, VideoListVO::getVideoUrl, VideoListVO::setVideoUrl);

        // 填充是否点过赞
        Map<Long, Boolean> likeMap = assembler.likedMap(getUserId(), videoList.stream().map(Video::getId).toList());
        for (int i = 0; i < voList.size(); i++) {

            voList.get(i).setIsLike(likeMap.getOrDefault(videoList.get(i).getId(), false));
        }

        return PageResult.of(page.getTotal(), page.getPages(), voList);
    }

    @Override
    public PageResult<FollowVideoVO> getFollowVideoList(PageQuery pageQuery) {
        // 获取用户信息
        Long userId = getUserId();
        // 获取关注用户列表
        List<Long> followIds = userFollowService.getFollowIds(userId);
        if (followIds == null || followIds.isEmpty()) {

            return PageResult.of(0,0, Collections.emptyList());
        }
        List<Long> videoIds = recommendPort.recommendForFollow(new RecommendPort.FollowRecommendQuery(
                followIds, userId, pageQuery.getPageNo(), pageQuery.getPageSize()));
        if (videoIds == null || videoIds.isEmpty()) {

            return PageResult.of(0,0, Collections.emptyList());
        }

        // 查询视频
        Set<String> collect = videoIds.stream().map(String::valueOf).collect(Collectors.toSet());
        List<Video> videos = videoMapper.selectOrderedList(collect);

        // 转换为VO(计数从 content 池取)
        Map<Long, Content> contents = assembler.loadContents(videos);
        List<FollowVideoVO> voList = videos.stream()
                .map(v -> {
                    FollowVideoVO vo = BeanUtil.toBean(v, FollowVideoVO.class);
                    Content c = contents.get(v.getId());
                    if (c != null) {

                        vo.setLikeCount(c.getLikeCount());
                        vo.setPlayCount(c.getPlayCount());
                        vo.setShareCount(c.getShareCount());
                        vo.setCommentCount(c.getCommentCount());
                    }
                    return vo;
                }).toList();
        // 一次批量取作者摘要, 本地投影昵称与头像(注意按作者id查, 不是内容id)
        assembler.fillAuthors(voList, FollowVideoVO::getAuthorId,
                FollowVideoVO::setAuthorNickname, FollowVideoVO::setAuthorAvatarUrl);
        // 签名视频封面url
        assembler.signUrls(voList, FollowVideoVO::getCoverUrl, FollowVideoVO::setCoverUrl);
        // 签名视频url
        assembler.signUrls(voList, FollowVideoVO::getVideoUrl, FollowVideoVO::setVideoUrl);

        // 获取点赞状态
        Map<Long, Boolean> likeMap = assembler.likedMap(userId, videoIds);
        for (FollowVideoVO vo : voList) {

            vo.setIsLike(likeMap.getOrDefault(Long.valueOf(vo.getId()), false));
        }

        // 近似值
        long total = voList.size() < pageQuery.getPageSize() ?
                (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + voList.size() :
                (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return PageResult.of(total, pages, voList);
    }

    private static Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
