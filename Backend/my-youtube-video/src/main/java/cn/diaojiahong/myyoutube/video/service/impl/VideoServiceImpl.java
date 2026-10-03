package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.po.ContentLike;
import cn.diaojiahong.myyoutube.like.mapper.ContentLikeMapper;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.search.api.RecommendPort;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.common.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.api.message.ContentDocMessage;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.domain.dto.*;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum.*;

/**
 * 视频信息表 服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements IVideoService {

    private final UserPort userPort;
    private final RocketMQTemplate rocketMQTemplate;
    private final S3StorageUtil storageUtil;
    private final IContentTagService contentTagService;
    private final RecommendPort recommendPort;
    private final StringRedisTemplate redisTemplate;
    private final IUserFollowService userFollowService;
    private final ContentLikeMapper contentLikeMapper;
    private final ContentMapper contentMapper;
    private final IContentService contentService;
    private final ContentDocPort contentDocPort;
    private final ObjectMapper objectMapper;

    private static final long STORAGE_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    @Transactional
    @Override
    public Result<String> publishVideo(VideoDTO videoDTO) {
        if (StrUtils.isEmpty(videoDTO.getVideoUrl())) {
            return Result.fail("视频地址不能为空～");
        }

        Long userId = getUserId();

        Video video = BeanUtil.toBean(videoDTO, Video.class);
        video.setVideoUrl(storageUtil.normalizeToObjectKey(video.getVideoUrl()));

        video.setAuthorId(userId);
        // 上传即公开, 不接受客户端指定可见性; 私密走发布后的设为私密入口
        video.setVisibility(VideoVisibilityEnum.PUBLIC);

        if (video.getDuration() == null) {
            return Result.fail("视频时长不能为空～");
        }

        // 判断用户有没有输入标签
        if (StrUtils.isEmpty(videoDTO.getTags())) {
            // 使用标题填充
            video.setTags(videoDTO.getTitle().replaceAll("\\s+", ","));
        }
        video.setCreateTime(LocalDateTime.now());
        video.setStatus(REVIEW);
        // 预分配雪花id, content 池与领域表共用; 事务消息回查时按它判断入库结果
        video.setId(IdWorker.getId());

        // 事务消息: half 消息先发出, 本地事务入库成功才提交, 保证"视频入库"与"封面任务"原子性
        VideoCoverMessage coverMsg = VideoCoverMessage.builder()
                .videoId(video.getId())
                .userId(userId)
                .videoObjectKey(videoDTO.getVideoUrl())
                .build();
        Message<VideoCoverMessage> message = MessageBuilder.withPayload(coverMsg)
                .setHeader("videoId", video.getId().toString())
                .build();
        rocketMQTemplate.sendMessageInTransaction(MqConstants.TOPIC_VIDEO_COVER, message, video);

        return Result.success(video.getId() + "/" + "视频发布成功,等待审核中....");
    }

    @Transactional
    @Override
    public void persistNewVideo(Video video) {
        // content 池先行: 同一雪花id下先有"内容"再有领域记录
        Content content = new Content()
                .setId(video.getId())
                .setType(ContentTypeEnum.VIDEO)
                .setAuthorId(video.getAuthorId())
                .setStatus(video.getStatus() != null ? video.getStatus().getCode() : null)
                .setCreateTime(video.getCreateTime());
        contentMapper.insert(content);
        save(video);
        // 标签入库, 并延迟同步 ES content 文档(短时间多次变更天然合并)
        List<Long> tagIds = contentTagService.saveTags(video.getTags(), video.getId());
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_CONTENT,
                ContentDocMessage.builder()
                        .type(ContentTypeEnum.VIDEO.getCode()).contentId(video.getId())
                        .title(video.getTitle()).description(video.getDescription())
                        .coverUrl(video.getCoverUrl()).tags(video.getTags())
                        .categoryCode(video.getCategory() != null ? video.getCategory().getCode() : null)
                        .statusCode(video.getStatus() != null ? video.getStatus().getCode() : null)
                        .visibilityCode(video.getVisibility() != null ? video.getVisibility().getCode() : null)
                        .authorId(video.getAuthorId())
                        .createTime(video.getCreateTime()).tagIds(tagIds)
                        .build(), 5);
    }

    @Override
    public Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery) {
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
                List<Video> videos = lambdaQuery().in(Video::getId, ids)
                        .eq(Video::getIsDelete, 0)
                        .list();
                // 保持 ES 返回的顺序 (listByIds不保证返回的顺序)
                Map<Long, Video> map = videos.stream()
                        .collect(Collectors.toMap(Video::getId, v -> v));
                Map<Long, Content> contents = loadContents(videos);
                List<VideoListVO> list = ids.stream()
                        .map(map::get)
                        .filter(Objects::nonNull)
                        .map(v -> VideoVoConverter.toListVo(v, contents.get(v.getId())))
                        .toList();
                fillAuthorNicknames(list);
                fillAuthorAvatarUrls(list);
                signListCoverUrlsForVideoListVO(list);
                signListVideoUrlsForVideoListVO(list);

                // 近似值
                long total = list.size() < pageQuery.getPageSize() ?
                        (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + list.size() :
                        (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
                long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

                return Result.success(PageResult.of(total, pages, list));
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
            result = baseMapper.selectPageSorted(pageQuery.toPage(), userId,
                    pageQuery.getCategory() != null ? pageQuery.getCategory().getCode() : null,
                    pageQuery.getBegin(), pageQuery.getEnd(),
                    pageQuery.getTags() != null && !pageQuery.getTags().isBlank() ? pageQuery.getTags().trim() : null,
                    sort.name());
        } else {
            q.orderByDesc(Video::getCreateTime);
            result = page(pageQuery.toPage(), q.eq(Video::getStatus, PUBLISHED));
        }
        List<VideoListVO> list = toListVosWithCounts(result.getRecords());
        fillAuthorNicknames(list);
        fillAuthorAvatarUrls(list);
        signListCoverUrlsForVideoListVO(list);
        signListVideoUrlsForVideoListVO(list);
        return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
    }

    private Map<Long, Content> loadContents(List<Video> videos) {
        List<Long> ids = videos.stream().map(Video::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return contentService.listByIds(ids).stream()
                .collect(Collectors.toMap(Content::getId, c -> c));
    }

    private List<VideoListVO> toListVosWithCounts(List<Video> videos) {
        Map<Long, Content> contents = loadContents(videos);
        return videos.stream()
                .map(v -> VideoVoConverter.toListVo(v, contents.get(v.getId())))
                .collect(Collectors.toList());
    }

    private void fillAuthorAvatarUrls(List<VideoListVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, String> map = userPort
                .getAvatars(list.stream()
                        .map(VideoListVO::getAuthorId)
                        .collect(Collectors.toList()));
        list.forEach(vo ->
                vo.setAuthorAvatarUrl(storageUtil.signGetUrl(map.getOrDefault(vo.getAuthorId(), null),
                        STORAGE_READ_SIGN_EXPIRE_SECONDS)));
    }

    @Override
    public Result<VideoDetailVO> getVideoDetail(Long videoId) {
        Long userId = getUserId();
        Video video = getById(videoId);

        if (video == null || (video.getIsDelete() != null && video.getIsDelete() != 0)) {
            return Result.fail("视频不存在或未发布～");
        }
        if (!PUBLISHED.getCode().equals(video.getStatus().getCode())) {
            return Result.fail("视频已私密或已被封禁～");
        }
        // 私密视频仅作者本人可看(直链直达也拦)
        if (video.getVisibility() == VideoVisibilityEnum.PRIVATE && !video.getAuthorId().equals(userId)) {
            return Result.fail("无权查看该视频～");
        }

        VideoDetailVO detailVo = VideoVoConverter.toDetailVo(video, contentService.getById(videoId));
        detailVo.setVideoUrl(storageUtil.signGetUrl(detailVo.getVideoUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setCoverUrl(storageUtil.signGetUrl(detailVo.getCoverUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));

        // 查看历史播放记录,看是否有播放记录,如果有完成续播功能
        String playTimeStr = (String) redisTemplate.opsForHash().get(RedisConstants.USER_PLAYBACK_RESUME + userId, videoId.toString());
        Long playTime = (playTimeStr != null && !playTimeStr.isBlank()) ? Long.parseLong(playTimeStr) : null;
        if (playTime != null) {
            // 说明存在播放记录,则设置播放时间
            detailVo.setPlaybackTime(playTime);
        }

        // 获取作者的信息
        UserSummary author = userPort.getUser(video.getAuthorId());
        if (author == null) {
            return Result.fail("作者不存在");
        }
        detailVo.setAuthorFanCount(author.getFans());
        detailVo.setAuthorAvatarUrl(storageUtil.signGetUrl(author.getAvatarUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setAuthorNickname(author.getNickname());

        return Result.success(detailVo);
    }


    @Override
    public Result<PageResult<VideoListVO>> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery) {
        Page<Video> result = lambdaQuery()
                .eq(Video::getIsDelete, 0)
                .eq(Video::getAuthorId, authorId)
                .eq(Video::getStatus, PUBLISHED)
                .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                .orderByDesc(Video::getCreateTime)
                .page(pageQuery.toPage());
        List<VideoListVO> list = toListVosWithCounts(result.getRecords());
        fillAuthorNicknames(list);
        signListCoverUrlsForVideoListVO(list);
        return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
    }

    @Override
    public Result<PageResult<VideoListVO>> getMyVideoList(VideoPageQuery pageQuery) {
        Long userId = getUserId();

        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getAuthorId, userId);
        q.eq(Video::getIsDelete, 0);
        q.orderByDesc(Video::getCreateTime);
        Page<Video> list = page(pageQuery.toPage(), q);

        List<VideoListVO> result = toListVosWithCounts(list.getRecords());
        // 封面url
        signListCoverUrlsForVideoListVO(result);
        // 视频url
        signListVideoUrlsForVideoListVO(result);

        // 查看点赞状态
        List<Long> videoIds = result.stream().map(v -> {
            return Long.valueOf(v.getId());
        }).toList();
        List<Long> isLike = isLike(userId, videoIds);
        // 转换为map
        Map<Long, Boolean> likeMap = null;
        if (isLike == null || isLike.isEmpty()) {
            likeMap = Collections.emptyMap();
        } else {
            likeMap = isLike.stream().collect(Collectors.toMap(v -> v, v -> true));
        }
        // 填充点赞信息
        for (VideoListVO vo : result) {
            vo.setIsLike(likeMap.getOrDefault(Long.valueOf(vo.getId()), false));
        }

        log.info("查询用户 {} 的视频列表，共 {} 条", userId, result.size());
        return Result.success(PageResult.of(list.getTotal(), list.getPages(), result));
    }

    private void signListVideoUrlsForVideoListVO(List<VideoListVO> list) {
        if (list.isEmpty()) {
            return;
        }
        for (VideoListVO vo : list) {
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    @Override
    public Result<String> generateVideoCover(VideoCoverMessage videoCoverMessage) {
        // 异步生成视频封面
        rocketMQTemplate.syncSend(MqConstants.TOPIC_VIDEO_COVER, videoCoverMessage);
        log.info("发送视频封面消息, 视频id: {}", videoCoverMessage.getVideoId());
        return Result.success("发布成功");
    }

    @Override
    public Result<PageResult<ShortsVO>> getShortsFeed(PageQuery pageQuery) {
        Long userId = getUserId();
        // 连播池不过滤形态, 全量混排
        List<Long> videoIds = recommendPort.recommend(new RecommendPort.RecommendQuery(
                userId, null, pageQuery.getPageNo(), pageQuery.getPageSize()));
        if (videoIds.isEmpty()) {
            // 直接返回空列表
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }

        // 查询视频信息
        List<Video> videos = lambdaQuery().in(Video::getId, videoIds)
                .eq(Video::getIsDelete, 0)
                .list();
        Map<Long, Video> map = videos.stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
        Map<Long, Content> contents = loadContents(videos);
        List<ShortsVO> voList = videoIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(v -> VideoVoConverter.toShortsVo(v, contents.get(v.getId())))
                .toList();

        // 获取所有作者id
        List<Long> authorIds = voList.stream().map(ShortsVO::getAuthorId).toList();
        if (!authorIds.isEmpty()) {
            // 查询作者昵称
            Map<Long, String> authorNicknames = userPort.getNicknames(authorIds);
            // 作者头像url
            Map<Long, String> authorAvatarUrls = userPort.getAvatars(authorIds);
            // 填充
            voList.forEach(vo -> vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId())));
            voList.forEach(vo -> {
                vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_READ_SIGN_EXPIRE_SECONDS));
            });
            // 签名封面url
            signListCoverUrlsForShortsVO(voList);
            // 签名视频url
            signListVideoUrlForShortsVO(voList);

            // 填充是否关注了该作者
            List<Boolean> followOnList = userFollowService.isFollowOnList(authorIds, userId);
            for (int i = 0; i < voList.size(); i++) {
                Boolean isFollow = followOnList.get(i);
                voList.get(i).setIsFollow(isFollow != null && isFollow);
            }
        }

        // 查看用户是否点赞了这些作品
        List<Long> collect = isLike(userId, videoIds);
        // 转map
        Map<Long, Boolean> likeMap = collect.stream().collect(Collectors.toMap(id -> id, id -> true));
        // 填充
        voList.forEach(vo -> vo.setIsLike(likeMap.getOrDefault(Long.valueOf(vo.getId()), false)));

        // 近似值
        long total = voList.size() < pageQuery.getPageSize() ?
                (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + voList.size() :
                (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return Result.success(PageResult.of(total, pages, voList));
    }

    private List<Long> isLike(Long userId, List<Long> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<ContentLike> list = new LambdaQueryChainWrapper<>(contentLikeMapper)
                .eq(ContentLike::getIsLiked, 1)
                .eq(ContentLike::getUserId, userId)
                .in(ContentLike::getContentId, videoIds).list();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(ContentLike::getContentId).toList();
    }

    @Override
    public Result<Void> updatePlaybackResume(RecordUserWatchTimeDTO userWatchTimeDTO) {
        Long userId = userWatchTimeDTO.getUserId();
        // 记录当前的时间戳
        redisTemplate.opsForZSet().add(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId,
                userWatchTimeDTO.getVideoId().toString(),
                System.currentTimeMillis());
        // 记录用户观看的时长记录
        redisTemplate.opsForHash().put(RedisConstants.USER_PLAYBACK_RESUME + userId,
                userWatchTimeDTO.getVideoId().toString(), userWatchTimeDTO.getPlayTime().toString());

        // 设置过期时间
        redisTemplate.expire(RedisConstants.USER_PLAYBACK_RESUME + userId, RedisConstants.USER_PLAYBACK_RESUME_EXPIRE, TimeUnit.DAYS);
        redisTemplate.expire(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId, RedisConstants.USER_PLAYBACK_RESUME_EXPIRE, TimeUnit.DAYS);

        return Result.success();
    }

    @Override
    public Result<PageResult<VideoListVO>> getOtherUserVideoList(Long userId, PageQuery pageQuery) {
        // 查询他人公开的视频
        Page<Video> page = lambdaQuery().eq(Video::getAuthorId, userId)
                .eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC)
                .eq(Video::getIsDelete, false)
                .eq(Video::getStatus, VideoStatusEnum.PUBLISHED)
                .orderByDesc(Video::getCreateTime)
                .page(pageQuery.toPage());
        List<Video> videoList = page.getRecords();
        if (videoList == null || videoList.isEmpty()) {
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }
        // 转换为VO
        List<VideoListVO> voList = toListVosWithCounts(videoList);

        // 获取作者昵称
        UserSummary author = userPort.getUser(userId);
        if (author == null) {
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }
        // 填充作者昵称
        voList.forEach(vo -> vo.setAuthorNickname(author.getNickname()));
        // 签名视频封面url
        signListCoverUrlsForVideoListVO(voList);
        // 签名视频url
        signListVideoUrlsForVideoListVO(voList);

        // 填充是否点过赞
        List<Long> likedIds = isLike(getUserId(), videoList.stream().map(Video::getId).toList());
        Map<Long, Boolean> collect = likedIds.stream().collect(Collectors.toMap(v -> v, v -> true));
        for (int i = 0; i < voList.size(); i++) {
            voList.get(i).setIsLike(collect.getOrDefault(videoList.get(i).getId(), false));
        }

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<PageResult<FollowVideoVO>> getFollowVideoList(PageQuery pageQuery) {
        // 获取用户信息
        Long userId = getUserId();
        // 获取关注用户列表
        List<Long> followIds = userFollowService.getFollowIds(userId);
        if (followIds == null || followIds.isEmpty()) {
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }
        List<Long> videoIds = recommendPort.recommendForFollow(new RecommendPort.FollowRecommendQuery(
                followIds, userId, pageQuery.getPageNo(), pageQuery.getPageSize()));
        if (videoIds == null || videoIds.isEmpty()) {
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }

        // 查询视频
        Set<String> collect = videoIds.stream().map(String::valueOf).collect(Collectors.toSet());
        List<Video> videos = baseMapper.selectOrderedList(collect);

        // 转换为VO(计数从 content 池取)
        Map<Long, Content> contents = loadContents(videos);
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
        // 填充作者昵称
        Map<Long, String> idToNick = userPort.getNicknames(videoIds);
        for (FollowVideoVO vo : voList) {
            vo.setAuthorNickname(idToNick.get(vo.getAuthorId()));
        }
        // 填充作者头像url
        Map<Long, String> idToAvatar = userPort.getAvatars(videoIds);
        for (FollowVideoVO vo : voList) {
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(idToAvatar.get(vo.getAuthorId()), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
        // 签名视频封面url
        for (FollowVideoVO vo : voList) {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
        // 签名视频url
        for (FollowVideoVO vo : voList) {
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }

        // 获取点赞状态
        List<Long> likedIds = isLike(userId, videoIds);
        Map<Long, Boolean> c = likedIds.stream().collect(Collectors.toMap(v -> v, v -> true));
        for (FollowVideoVO vo : voList) {
            vo.setIsLike(c.getOrDefault(Long.valueOf(vo.getId()), false));
        }

        // 近似值
        long total = voList.size() < pageQuery.getPageSize() ?
                (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + voList.size() :
                (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return Result.success(PageResult.of(total, pages, voList));
    }

    @Transactional
    @Override
    public Result<Void> deleteVideo(Long videoId) {
        Long userId = getUserId();
        boolean success = lambdaUpdate().eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId)
                .set(Video::getIsDelete, true).update();
        if (!success) {
            return Result.fail("删除失败,请稍后再试");
        }
        // content 池无删除标记, 不同步摘除的话池分发与创作中心会把已删视频查回来
        contentService.removeById(videoId);
        // ES 尽力删除, 失败不影响 DB 删除, 列表查询以 DB is_delete 为准
        try {
            contentDocPort.deleteDoc(videoId);
        } catch (Exception e) {
            log.warn("ES 文档删除失败, videoId={}", videoId, e);
        }
        return Result.success();
    }

    @Override
    public Result<Void> privateVideo(Long videoId) {
        Long userId = getUserId();
        boolean success = lambdaUpdate().eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId)
                .set(Video::getVisibility, VideoVisibilityEnum.PRIVATE).update();
        if (!success) {
            return Result.fail("修改失败");
        }
        // 在es中更新这个文档
        contentDocPort.syncVisibility(videoId, new VisibilitySync(null, null, null, "private", false));
        return Result.success();
    }

    @Override
    public Result<String> publicVideo(Long videoId) {
        Long userId = getUserId();
        Video video = lambdaQuery().eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId)
                .one();
        if (video == null) {
            return Result.fail("该视频不存在");
        }
        if (video.getStatus().equals(BANNED)) {
            return Result.fail("该视频已被封禁,无法修改");
        }
        if (video.getStatus().equals(REVIEW)) {
            boolean success = lambdaUpdate().eq(Video::getId, videoId)
                    .eq(Video::getAuthorId, userId)
                    .set(Video::getVisibility, VideoVisibilityEnum.PUBLIC).update();
            if (!success) {
                return Result.fail("修改失败");
            } else {
                // 在es中更新这个文档
                contentDocPort.syncVisibility(videoId, new VisibilitySync(null, null, null, "public", false));
                return Result.success("修改成功,等待审核中");
            }
        }
        // 在es中更新这个文档
        contentDocPort.syncVisibility(videoId, new VisibilitySync(video.getTitle(), video.getDescription(), video.getTags(), "public", true));
        return Result.success("修改成功");
    }

    @Override
    public Result<Void> openComment(Long videoId) {
        Long userId = getUserId();
        boolean success = lambdaUpdate().eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId)
                .set(Video::getAllowComment, 1).update();
        if (!success) {
            return Result.fail("修改失败");
        }
        return Result.success();
    }

    @Override
    public Result<Void> closeComment(Long videoId) {
        Long userId = getUserId();
        boolean success = lambdaUpdate().eq(Video::getId, videoId)
                .eq(Video::getAuthorId, userId)
                .set(Video::getAllowComment, 0).update();
        if (!success) {
            return Result.fail("修改失败");
        }
        return Result.success();
    }

    private static Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }


    /**
     * 批量查询作者昵称，避免前端仅展示「用户 作者id」。
     */
    private void fillAuthorNicknames(List<VideoListVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> ids = list.stream()
                .map(VideoListVO::getAuthorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        List<UserSummary> users = userPort.listByIds(ids);
        Map<Long, String> idToNick = users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(UserSummary::getId, u -> u.getNickname() != null ? u.getNickname() : "", (a, b) -> a));
        for (VideoListVO vo : list) {
            Long aid = vo.getAuthorId();
            if (aid != null && idToNick.containsKey(aid)) {
                String nick = idToNick.get(aid);
                if (nick != null && !nick.isBlank()) {
                    vo.setAuthorNickname(nick);
                }
            }
        }
    }

    private void signListCoverUrlsForVideoListVO(List<VideoListVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (VideoListVO vo : list) {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    private void signListCoverUrlsForShortsVO(List<ShortsVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ShortsVO vo : list) {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    private void signListVideoUrlForShortsVO(List<ShortsVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ShortsVO vo : list) {
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        }
    }

}
