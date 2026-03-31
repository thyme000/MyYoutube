package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants;
import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.StrUtils;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.service.IRecommendService;
import cn.diaojiahong.myyoutube.recommend.service.IVideoTagService;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.mq.message.VideoCoverMessage;
import cn.diaojiahong.myyoutube.video.domain.dto.RecordUserWatchTimeDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.UserShortsWatchRecords;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoDTO;
import cn.diaojiahong.myyoutube.video.domain.dto.VideoPageQuery;
import cn.diaojiahong.myyoutube.video.domain.enums.VideoSortEnum;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.*;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.PUBLISHED;
import static cn.diaojiahong.myyoutube.video.domain.enums.VideoStatusEnum.REVIEW;
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
    private final AliOssUtil aliOssUtil;
    private final IVideoTagService videoTagService;
    private final IRecommendService recommendService;
    private final StringRedisTemplate redisTemplate;

    private static final int SHORT_VIDEO_DURATION = 300;
    private static final long OSS_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    @Transactional
    @Override
    public Result<String> publishVideo(VideoDTO videoDTO) {
        if (StrUtils.isEmpty(videoDTO.getVideoUrl())) {
            return Result.fail("视频地址不能为空～");
        }

        UserDTO user = UserContext.getUser();
        if (user == null) {
            return Result.fail("用户未登录,请先登录～");
        }

        Video video = BeanUtil.toBean(videoDTO, Video.class);
        video.setVideoUrl(aliOssUtil.normalizeToObjectKey(video.getVideoUrl()));

        video.setAuthorId(user.getId());

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
                        .userId(UserContext.getUser().getId())
                        .videoOssObjectKey(videoDTO.getVideoUrl())
                        .build());

        return Result.success(video.getId() + "/" + "视频发布成功,等待审核中....");
    }

    @Override
    public Result<PageResult<VideoListVO>> getVideoList(VideoPageQuery pageQuery) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户信息获取失败");
        }

        // 推荐视频
        if (pageQuery.getSort() == VideoSortEnum.RECOMMENDED) {
            List<Long> ids = recommendService.recommend(
                    user.getId(),
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
                signListCoverUrlsForVideoListVO(list);
                signListVideoUrlsForVideoListVO(list);
                // TODO暂时用 list.size() < pageSize 来判断是否还有下一页
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
        q.ne(Video::getAuthorId, user.getId());

        applySort(q, pageQuery.getSort());
        Page<Video> result = page(page, q.eq(Video::getStatus, PUBLISHED));
        List<VideoListVO> list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                .collect(Collectors.toList());
        fillAuthorNicknames(list);
        signListCoverUrlsForVideoListVO(list);
        signListVideoUrlsForVideoListVO(list);
        return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
    }

    @Override
    public Result<VideoDetailVO> getVideoDetail(Long videoId) {
        if (UserContext.getUser() == null) {
            throw new BizException(BizException.USER_ERROR, "用户信息获取失败");
        }
        Video video = getById(videoId);

        if (video == null ) {
            return Result.fail("视频不存在或未发布～");
        }
        if (!PUBLISHED.getCode().equals(video.getStatus().getCode())) {
            return Result.fail("视频已私密或已被封禁～");
        }

        VideoDetailVO detailVo = VideoVoConverter.toDetailVo(video);
        detailVo.setVideoUrl(aliOssUtil.signGetUrl(detailVo.getVideoUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));
        detailVo.setCoverUrl(aliOssUtil.signGetUrl(detailVo.getCoverUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));

        // 查看历史播放记录,看是否有播放记录,如果有完成续播功能
        String playTimeStr = (String) redisTemplate.opsForHash().get(RedisConstants.USER_PLAYBACK_RESUME + UserContext.getUser().getId(), videoId.toString());
        Long playTime = (playTimeStr != null && !playTimeStr.isBlank()) ? Long.parseLong(playTimeStr) : null;
        if (playTime != null) {
            // 说明存在播放记录,则设置播放时间
            detailVo.setPlaybackTime(playTime);
        }

        // 获取作者的信息
        User author = userService.getById(video.getAuthorId());
        detailVo.setAuthorFanCount(author.getFans());
        detailVo.setAuthorAvatarUrl(author.getAvatarUrl());
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
        q.orderByDesc(Video::getCreateTime);
        // 判断是查询视频的类型
        List<VideoListVO> list;
        Page<Video> result;
        if (pageQuery.getVideoType().getCode().equals(LONG_VIDEO.getCode())) {
            // 查询所有
            result = page(page, q);
            list = result.getRecords().stream()
                    .map(VideoVoConverter::toListVo)
                    .toList();
            fillAuthorNicknames(list);
            signListCoverUrlsForVideoListVO(list);
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        } else if (pageQuery.getVideoType().getCode().equals(SHORT_VIDEO.getCode())) {
            // 查询短视频
            result = page(page, q.eq(Video::getVideoType, SHORT_VIDEO.getCode()));
            list = result.getRecords().stream()
                .map(VideoVoConverter::toListVo)
                    .toList();
            fillAuthorNicknames(list);
            signListCoverUrlsForVideoListVO(list);
            return Result.success(PageResult.of(result.getTotal(), result.getPages(), list));
        } else {
            // 查询长视频
            result = page(page, q.eq(Video::getVideoType, LONG_VIDEO.getCode()));
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
        Page<Video> page = pageQuery.toPage();

        LambdaQueryWrapper<Video> q = new LambdaQueryWrapper<>();
        q.eq(Video::getAuthorId, UserContext.getUser().getId());
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
        log.info("查询用户 {} 的视频列表，共 {} 条", UserContext.getUser().getId(), result.size());
        return Result.success(PageResult.of(list.getTotal(), list.getPages(), result));
    }

    private void signListVideoUrlsForVideoListVO(List<VideoListVO> list) {
        if (list.isEmpty()) {
            return;
        }
        list.forEach(vo -> vo.setVideoUrl(aliOssUtil.signGetUrl(vo.getVideoUrl(), OSS_READ_SIGN_EXPIRE_SECONDS)));
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
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户信息获取失败");
        }
        // 获得推荐短视频id
        List<Long> videoIds = recommendService.recommend(user.getId(),
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
        // 查询作者昵称
        Map<Long, String> authorNicknames = userService.getAuthorNicknames(authorIds);
        // 作者头像url
        Map<Long, String> authorAvatarUrls = userService.getAuthorAvatarUrls(authorIds);
        // 填充
        voList.forEach(vo -> vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId())));
        voList.forEach(vo -> {
            vo.setAuthorAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), OSS_READ_SIGN_EXPIRE_SECONDS));
        });
        // 签名封面url
        signListCoverUrlsForShortsVO(voList);
        // 签名视频url
        signListVideoUrlForShortsVO(voList);

        // TODO暂时用 list.size() < pageSize 来判断是否还有下一页
        long total = voList.size() < pageQuery.getPageSize() ?
                (long)(pageQuery.getPageNo() - 1) * pageQuery.getPageSize() + voList.size() :
                (long)pageQuery.getPageNo() * pageQuery.getPageSize() + 1;
        long pages = (total + pageQuery.getPageSize() - 1) / pageQuery.getPageSize();

        return Result.success(PageResult.of(total, pages, voList));
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
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    private void signListCoverUrlsForShortsVO(List<ShortsVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ShortsVO vo : list) {
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    private void signListVideoUrlForShortsVO(List<ShortsVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (ShortsVO vo : list) {
            vo.setVideoUrl(aliOssUtil.signGetUrl(vo.getVideoUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));
        }
    }

    private void signMyListCoverUrls(List<MyVideoListVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (MyVideoListVO vo : list) {
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_READ_SIGN_EXPIRE_SECONDS));
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
