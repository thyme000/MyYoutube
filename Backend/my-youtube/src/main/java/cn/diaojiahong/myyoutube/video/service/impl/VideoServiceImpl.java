package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.like.domain.po.VideoLike;
import cn.diaojiahong.myyoutube.like.mapper.VideoLikeMapper;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.recommend.service.IVideoTagService;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.mq.message.PictureToVideoMessage;
import cn.diaojiahong.myyoutube.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.*;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoVisibilityEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.*;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum.LONG_VIDEO;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoTypeEnum.SHORT_VIDEO;

/**
 * 视频信息表 服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements IVideoService {

    private final IUserService userService;
    private final RabbitTemplate rabbitTemplate;
    private final S3StorageUtil storageUtil;
    private final IVideoTagService videoTagService;
    private final IRecommendService recommendService;
    private final StringRedisTemplate redisTemplate;
    private final IUserFollowService userFollowService;
    private final VideoLikeMapper videoLikeMapper;
    private final VideoDocRepository videoDocRepository;
    private final ObjectMapper objectMapper;

    private static final int SHORT_VIDEO_DURATION = 300;
    private static final int MERGE_PICTURE_MAX_IMAGES = 50;
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

        if (video.getDuration() == null) {
            return Result.fail("视频时长不能为空～");
        }
        if (video.getDuration() <= SHORT_VIDEO_DURATION) {
            video.setVideoType(SHORT_VIDEO.getCode());
        } else {
            video.setVideoType(LONG_VIDEO.getCode());
        }

        // 判断用户有没有输入标签
        if (StrUtils.isEmpty(videoDTO.getTags())) {
            // 使用标题填充
            video.setTags(videoDTO.getTitle().replaceAll("\\s+", ","));
        }
        video.setCreateTime(LocalDateTime.now());
        video.setStatus(REVIEW);
        save(video);

        // 异步保存到标签表中
        videoTagService.saveTagsAsync(video.getTags(), video.getId(), video);

        // 发送消息给RabbitMQ,用于自动生成视频封面
        rabbitTemplate.convertAndSend(RabbitMqConstants.VIDEO_COVER_EXCHANGE,
                RabbitMqConstants.VIDEO_COVER_ROUTING_KEY,
                VideoCoverMessage.builder()
                        .videoId(video.getId())
                        .userId(userId)
                        .videoObjectKey(videoDTO.getVideoUrl())
                        .build());

        return Result.success(video.getId() + "/" + "视频发布成功,等待审核中....");
    }

    @Override
    public Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery) {
        Long userId = getUserId();

        // 推荐视频
        if (pageQuery.getSort() == VideoSortEnum.RECOMMENDED) {
            List<Long> ids = recommendService.recommend(
                    userId,
                    pageQuery.getCategory() != null ? pageQuery.getCategory().getCode() : null,
                    pageQuery.getVideoType() != null ? pageQuery.getVideoType().getCode() : null,
                    pageQuery.getPageNo(),
                    pageQuery.getPageSize()
            );
            if (!ids.isEmpty()) {
                // 用 MySQL 按 ids 批量查详情（保持字段一致）
                List<Video> videos = listByIds(ids);
                // 保持 ES 返回的顺序 (listByIds不保证返回的顺序)
                Map<Long, Video> map = videos.stream()
                        .collect(Collectors.toMap(Video::getId, v -> v));
                List<VideoListVO> list = ids.stream()
                        .map(map::get)
                        .filter(Objects::nonNull)
                        .map(VideoVoConverter::toListVo)
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

        applySort(q, pageQuery.getSort());
        Page<Video> result = page(page, q.eq(Video::getStatus, PUBLISHED));
        List<VideoListVO> list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                .collect(Collectors.toList());
        fillAuthorNicknames(list);
        fillAuthorAvatarUrls(list);
        signListCoverUrlsForVideoListVO(list);
        signListVideoUrlsForVideoListVO(list);
        return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
    }

    private void fillAuthorAvatarUrls(List<VideoListVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, String> map = userService
                .getAuthorAvatarUrls(list.stream()
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

        if (video == null ) {
            return Result.fail("视频不存在或未发布～");
        }
        if (!PUBLISHED.getCode().equals(video.getStatus().getCode())) {
            return Result.fail("视频已私密或已被封禁～");
        }

        VideoDetailVO detailVo = VideoVoConverter.toDetailVo(video);
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
        User author = userService.getById(video.getAuthorId());
        detailVo.setAuthorFanCount(author.getFans());
        detailVo.setAuthorAvatarUrl(storageUtil.signGetUrl(author.getAvatarUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setAuthorNickname(author.getNickname());

        return Result.success(detailVo);
    }


    @Override
    public Result<PageResult<VideoListVO>> getVideoListByAuthorId(Long authorId, VideoPageQuery pageQuery) {
        Page<Video> page = pageQuery.toPage();
        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getIsDelete, 0);
        q.eq(Video::getAuthorId, authorId);
        q.eq(Video::getStatus, PUBLISHED);
        q.eq(Video::getVisibility, VideoVisibilityEnum.PUBLIC);
        q.orderByDesc(Video::getCreateTime);
        // 判断是查询视频的类型
        List<VideoListVO> list;
        Page<Video> result;
        if (pageQuery.getVideoType() == null) {
            // 查询所有
            result = page(page, q);
            list = result.getRecords().stream()
                    .map(VideoVoConverter::toListVo)
                    .toList();
            fillAuthorNicknames(list);
            signListCoverUrlsForVideoListVO(list);
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        } else {
            // 查询短视频或长视频
            result = page(page, q.eq(Video::getVideoType, pageQuery.getVideoType().getCode()));
            list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                    .toList();
            fillAuthorNicknames(list);
            signListCoverUrlsForVideoListVO(list);
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        }
    }

    @Override
    public Result<PageResult<VideoListVO>> getMyVideoList(VideoPageQuery pageQuery) {
        Long userId = getUserId();
        Page<Video> page = pageQuery.toPage();

        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getAuthorId, userId);
        q.orderByDesc(Video::getCreateTime);
        if (pageQuery.getVideoType() != null) {
            q.eq(Video::getVideoType, pageQuery.getVideoType().getCode());
        }
        if (pageQuery.getVisibility() != null) {
            // 查询指定可见性
            q.eq(Video::getVisibility, pageQuery.getVisibility().getCode());
        }
        Page<Video> list = page(page, q);

        List<VideoListVO> result = list.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                .toList();
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
        // 发送消息给rabbitMQ,异步去获取视频封面
        rabbitTemplate.convertAndSend(RabbitMqConstants.VIDEO_COVER_EXCHANGE,
                RabbitMqConstants.VIDEO_COVER_ROUTING_KEY, videoCoverMessage);
        log.info("发送视频封面消息到 rabbitMQ, 视频id: {}", videoCoverMessage.getVideoId());
        return Result.success("发布成功");
    }

    @Override
    public Result<PageResult<ShortsVO>> getShortsFeed(PageQuery pageQuery) {
        Long userId = getUserId();
        // 获得推荐短视频id
        List<Long> videoIds = recommendService.recommend(userId,
                null, SHORT_VIDEO.getCode(),
                pageQuery.getPageNo(), pageQuery.getPageSize());
        if (videoIds.isEmpty()) {
            // 直接返回空列表
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }

        // 查询视频信息
        List<Video> videos = listByIds(videoIds);
        Map<Long, Video> map = videos.stream()
                .collect(Collectors.toMap(Video::getId, v -> v));
        List<ShortsVO> voList = videoIds.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(VideoVoConverter::toShortsVo)
                .toList();

        // 获取所有作者id
        List<Long> authorIds = voList.stream().map(ShortsVO::getAuthorId).toList();
        if (!authorIds.isEmpty()) {
            // 查询作者昵称
            Map<Long, String> authorNicknames = userService.getAuthorNicknames(authorIds);
            // 作者头像url
            Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(authorIds);
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
        List<VideoLike> list = new LambdaQueryChainWrapper<>(videoLikeMapper)
                .eq(VideoLike::getIsLiked, 1)
                .eq(VideoLike::getUserId, userId)
                .in(VideoLike::getVideoId, videoIds).list();
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(VideoLike::getVideoId).toList();
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
    public Result<Void> recordShortsPlay(UserShortsWatchRecords userShortsWatchRecords) {
        Long userId = userShortsWatchRecords.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户id不能为空");
        }
        Set<Long> shortIds = userShortsWatchRecords.getShortIds();
        if (shortIds == null || shortIds.isEmpty()) {
            // 短视频id集合为空，直接返回成功
            return Result.success();
        }

        // 批量插入 ZSet(当前时间戳)
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        for (Long id : shortIds) {
            tuples.add(new DefaultTypedTuple<>(id.toString(), (double) System.currentTimeMillis()));
        }
        redisTemplate.opsForZSet().add(RedisConstants.USER_SHORTS_PLAY + userId, tuples);
        // 过期时间30天
        redisTemplate.expire(RedisConstants.USER_SHORTS_PLAY + userId, RedisConstants.USER_SHORTS_PLAY_EXPIRE, TimeUnit.DAYS);

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
        List<VideoListVO> voList = videoList.stream().map(v -> BeanUtil.toBean(v, VideoListVO.class)).toList();

        // 获取作者昵称
        User author = userService.getById(userId);
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
        List<Long> videoIds = recommendService.recommendForFollow(followIds, userId , pageQuery.getPageNo(), pageQuery.getPageSize());
        if (videoIds == null || videoIds.isEmpty()) {
            return Result.success(PageResult.of(0,0, Collections.emptyList()));
        }

        // 查询视频
        Set<String> collect = videoIds.stream().map(String::valueOf).collect(Collectors.toSet());
        List<Video> videos = baseMapper.selectOrderedList(collect);

        // 转换为VO
        List<FollowVideoVO> voList = videos.stream().map(v -> BeanUtil.toBean(v, FollowVideoVO.class)).toList();
        // 填充作者昵称
        Map<Long, String> idToNick = userService.getAuthorNicknames(videoIds);
        for (FollowVideoVO vo : voList) {
            vo.setAuthorNickname(idToNick.get(vo.getAuthorId()));
        }
        // 填充作者头像url
        Map<Long, String> idToAvatar = userService.getAuthorAvatarUrls(videoIds);
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
        // 在es中删除这个文档
        videoDocRepository.deleteById(videoId);
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
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            doc.setVisibility("private");
            doc.setSuggest(new Completion());
            videoDocRepository.save(doc);
        });
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
                videoDocRepository.findById(videoId).ifPresent(doc -> {
                    doc.setVisibility("public");
                    videoDocRepository.save(doc);
                });
                return Result.success("修改成功,等待审核中");
            }
        }
        // 在es中更新这个文档
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            List<String> list = new ArrayList<>(List.of(
                    video.getTitle(),
                    video.getDescription(),
                    doc.getAuthorName()
            ));
            list.addAll(Arrays.asList(video.getTags().split(",")));
            doc.setVisibility("public");
            doc.setSuggest(new Completion(list));
            videoDocRepository.save(doc);
        });
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

    @Override
    public Result<String> mergePictures(PictureToVideoDTO pictureForVideoDTO) {
        if (pictureForVideoDTO == null || pictureForVideoDTO.getObjectKeys() == null
                || pictureForVideoDTO.getObjectKeys().isEmpty()) {
            return Result.fail("图片列表不能为空");
        }
        if (pictureForVideoDTO.getObjectKeys().size() > MERGE_PICTURE_MAX_IMAGES) {
            return Result.fail("图片数量不能超过 " + MERGE_PICTURE_MAX_IMAGES + " 张");
        }
        Long userId = getUserId();
        List<String> keys = pictureForVideoDTO.getObjectKeys().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(storageUtil::normalizeToObjectKey)
                .collect(Collectors.toList());
        if (keys.isEmpty()) {
            return Result.fail("图片列表不能为空");
        }
        for (String k : keys) {
            if (!objectKeyBelongsToUser(userId, k)) {
                return Result.fail("存在无权使用的图片资源");
            }
        }
        String audioKey = null;
        if (StringUtils.hasText(pictureForVideoDTO.getAudioObjectKey())) {
            audioKey = storageUtil.normalizeToObjectKey(pictureForVideoDTO.getAudioObjectKey().trim());
            if (!objectKeyBelongsToUser(userId, audioKey)) {
                return Result.fail("无权使用该音频资源");
            }
        }

        String jobId = UUID.randomUUID().toString().replace("-", "");
        MergePictureJobRedisPayload pending = MergePictureJobRedisPayload.builder()
                .userId(userId)
                .status(MergePictureJobStatusVO.STATUS_PENDING)
                .build();
        try {
            redisTemplate.opsForValue().set(
                    RedisConstants.MERGE_PICTURE_JOB + jobId,
                    objectMapper.writeValueAsString(pending),
                    RedisConstants.MERGE_PICTURE_JOB_EXPIRE_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (JsonProcessingException e) {
            log.warn("mergePictures 写入 Redis 失败", e);
            return Result.fail("创建任务失败");
        }

        PictureToVideoMessage message = new PictureToVideoMessage(jobId, userId, keys, audioKey);
        rabbitTemplate.convertAndSend(RabbitMqConstants.MERGE_PICTURES_EXCHANGE,
                RabbitMqConstants.MERGE_PICTURES_ROUTING_KEY, message);
        return Result.success(jobId);
    }

    @Override
    public Result<MergePictureJobStatusVO> getMergePictureJobStatus(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return Result.fail("任务不存在");
        }
        Long userId = getUserId();
        String json = redisTemplate.opsForValue().get(RedisConstants.MERGE_PICTURE_JOB + jobId.trim());
        if (!StringUtils.hasText(json)) {
            return Result.fail("任务不存在或已过期");
        }
        try {
            MergePictureJobRedisPayload p = objectMapper.readValue(json, MergePictureJobRedisPayload.class);
            if (p == null || p.getUserId() == null || !p.getUserId().equals(userId)) {
                return Result.fail("无权查看该任务");
            }
            MergePictureJobStatusVO vo = MergePictureJobStatusVO.builder()
                    .status(p.getStatus())
                    .videoObjectKey(p.getVideoObjectKey())
                    .errorMessage(p.getErrorMessage())
                    .secondsPerImage(p.getSecondsPerImage())
                    .durationSeconds(p.getDurationSeconds())
                    .build();
            return Result.success(vo);
        } catch (JsonProcessingException e) {
            log.warn("getMergePictureJobStatus 解析失败 jobId={}", jobId, e);
            return Result.fail("任务状态异常");
        }
    }

    /**
     * 与 S3StorageUtil目录规则一致：uploads/{biz}/{userId}/...
     */
    private boolean objectKeyBelongsToUser(Long userId, String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        String k = objectKey.startsWith("uploads/") ? objectKey.trim() : storageUtil.normalizeToObjectKey(objectKey);
        if (!k.startsWith("uploads/")) {
            return false;
        }
        String[] parts = k.split("/");
        if (parts.length < 3) {
            return false;
        }
        return parts[2].equals(String.valueOf(userId));
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
        List<User> users = userService.listByIds(ids);
        Map<Long, String> idToNick = users.stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : "", (a, b) -> a));
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

    private static void applySort(LambdaQueryWrapper<Video> q, VideoSortEnum sort) {
        if (sort == null) {
            q.orderByDesc(Video::getCreateTime);
            return;
        }
        switch (sort) {
            case LATEST -> q.orderByDesc(Video::getCreateTime);
            case MOST_LIKED -> q.orderByDesc(Video::getLikeCount);
            case MOST_SHARED -> q.orderByDesc(Video::getShareCount);
            case MOST_PLAYED -> q.orderByDesc(Video::getPlayCount);
            case RECOMMENDED -> q.orderByDesc(Video::getPlayCount);
            default -> q.orderByDesc(Video::getCreateTime);
        }
    }
}
