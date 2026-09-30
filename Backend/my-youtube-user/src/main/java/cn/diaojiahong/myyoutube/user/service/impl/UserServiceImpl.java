package cn.diaojiahong.myyoutube.user.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserLongHistoryVideoVO;
import cn.diaojiahong.myyoutube.user.domain.vo.UserShortsVideoVO;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.search.api.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.video.api.UserVideoPort;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final S3StorageUtil storageUtil;
    private static final Long STORAGE_EXPIRE = 3600 * 24L;// 24小时过期时间
    private final StringRedisTemplate redisTemplate;
    private final UserVideoPort userVideoPort;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public Result<UserVO> getUserInfo(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        user.setAvatarUrl(storageUtil.signGetUrl(user.getAvatarUrl(), STORAGE_EXPIRE));
        user.setBgImage(storageUtil.signGetUrl(user.getBgImage(), STORAGE_EXPIRE));

        return Result.success(BeanUtil.toBean(user, UserVO.class));
    }

    @Override
    public Result<String> updateAvatar(MultipartFile avatar) {
        String upload = getUpdatedUrl(avatar, "avatar");
        if (upload == null) {
            return Result.fail("上传头像失败");
        }
        // 更新用户头像url
        lambdaUpdate()
                .set(User::getAvatarUrl, upload)
                .eq(User::getId, UserContext.getUserId())
                .update();
        return Result.success(upload);
    }

    private String getUpdatedUrl(MultipartFile file, String bizPrefix) {
        byte[] bytes = null;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("更新头像或背景图片失败...", e);
        }
        return storageUtil.uploadForObjectKey(bytes, file.getOriginalFilename(),
                getUserId(), bizPrefix);
    }

    @Override
    public Result<String> updateBgImage(MultipartFile bgImage) {
        String upload = getUpdatedUrl(bgImage, "bg");
        if (upload == null) {
            return Result.fail("上传背景图片失败");
        }
        // 更新用户头像url
        lambdaUpdate()
                .set(User::getBgImage, upload)
                .eq(User::getId, UserContext.getUserId())
                .update();
        return Result.success(upload);
    }

    @Override
    public Result<String> updateUser(Long id, UserDTO userDTO) {
        if (id == null) {
            return Result.fail("用户ID不能为空");
        }
        User user = getById(id);
        String oldName = user.getNickname();
        user.setNickname(userDTO.getNickname() == null || userDTO.getNickname().isEmpty() ?
                oldName : userDTO.getNickname());
        user.setIntroduction(userDTO.getIntroduction() == null || userDTO.getIntroduction().isEmpty() ?
                user.getIntroduction() : userDTO.getIntroduction());
        boolean success = updateById(user);
        if (!success) {
            return Result.fail("服务器繁忙,请稍后再试～");
        }
        // 同步 ES 文档: 发消息, 由 search 侧以 MySQL 为准重建
        rocketMQTemplate.syncSend(MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(user.getId()));

        log.info("用户{},更新用户资料成功...", id);
        return Result.success("修改成功");
    }

    @Override
    public Map<Long, String> getAuthorNicknames(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return listByIds(ids).stream().collect(Collectors.toMap(User::getId, User::getNickname));
    }

    @Override
    public Map<Long, String> getAuthorAvatarUrls(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        return listByIds(authorIds).stream().collect(Collectors.toMap(User::getId, User::getAvatarUrl));
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }


    @Override
    public Result<PageResult<UserLongHistoryVideoVO>> getLongHistory(PageQuery pageQuery) {
        int pageSize = pageQuery.getPageSize();
        int pageNo = pageQuery.getPageNo();
        // 起始索引
        int start = (pageNo - 1) * pageSize;
        // 结束索引
        int end = start + pageSize - 1;
        Long userId = getUserId();

        // 长视频id
        Set<String> longVideoIds = redisTemplate.opsForZSet().reverseRange(
                RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId,
                start, end);
        if (longVideoIds == null || longVideoIds.isEmpty()) {
            // 没有长视频
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        List<UserLongHistoryVideoVO> longHistoryVideoVOs = userVideoPort.listByIdsOrdered(longVideoIds).stream()
                .map(summary -> BeanUtil.toBean(summary, UserLongHistoryVideoVO.class))
                .toList();

        // 过滤掉已经删除的长视频id
        Set<String> dbIds = longHistoryVideoVOs.stream().map(UserLongHistoryVideoVO::getId).collect(Collectors.toSet());
        Set<String> missingIds = new HashSet<>(longVideoIds);
        missingIds.removeAll(dbIds);
        if (!missingIds.isEmpty()) {
            String tsKey = RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId;
            String ptKey = RedisConstants.USER_PLAYBACK_RESUME + userId;
            redisTemplate.opsForZSet().remove(tsKey, missingIds.toArray());
            redisTemplate.opsForHash().delete(ptKey, missingIds.toArray());
        }
        if (longHistoryVideoVOs.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        // 转换视频封面url和视频url
        longHistoryVideoVOs.forEach(vo -> {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
        });

        List<Long> authorIds = longHistoryVideoVOs
                .stream()
                .map(UserLongHistoryVideoVO::getAuthorId)
                .collect(Collectors.toList());
        // 获取作者昵称
        Map<Long, String> authorNicknames = getAuthorNicknames(authorIds);
        // 获取作者头像url
        Map<Long, String> authorAvatarUrls = getAuthorAvatarUrls(authorIds);

        // 填充作者昵称和头像url
        longHistoryVideoVOs.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
        });

        // 获取用户长视频观看时长记录
        Map<Object, Object> playbackResumes = redisTemplate
                .opsForHash()
                .entries(RedisConstants.USER_PLAYBACK_RESUME + userId);

        // 填充观看时长记录
        longHistoryVideoVOs.forEach(vo -> {
            vo.setPlaybackTime(Long.parseLong(playbackResumes.get(vo.getId() == null ? 0L : vo.getId()).toString()));
        });

        // 获取用户关注状态
        Map<Long, Boolean> followMap = getFollowMap(userId, authorIds);
        // 填充关注状态
        longHistoryVideoVOs.forEach(vo -> {
            Boolean isFollow = followMap.get(vo.getAuthorId());
            vo.setIsFollow(isFollow != null && isFollow);
        });

        // 获取点赞状态
        Map<Long, Boolean> collect = userVideoPort.likedMap(userId, dbIds);
        // 填充点赞状态
        longHistoryVideoVOs.forEach(vo -> {
            vo.setIsLike(collect.getOrDefault(Long.valueOf(vo.getId()), false));
        });

        long total = redisTemplate.opsForZSet().zCard(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId);

        long pages = total / pageSize;
        if (total % pageSize != 0) {
            pages++;
        }

        // 返回结果
        return Result.success(PageResult.of(total, pages, longHistoryVideoVOs));
    }

    private Map<Long, Boolean> getFollowMap(Long userId, List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        return userVideoPort.followedMap(userId, authorIds);
    }

    @Override
    public Result<PageResult<UserShortsVideoVO>> getShortsHistory(PageQuery pageQuery) {
        int pageSize = pageQuery.getPageSize();
        int pageNo = pageQuery.getPageNo();
        // 起始索引
        int start = (pageNo - 1) * pageSize;
        // 结束索引
        int end = start + pageSize - 1;
        Long userId = getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }

        // 查询短视频id
        Set<String> shortsIds = redisTemplate.opsForZSet().reverseRange(
                RedisConstants.USER_SHORTS_PLAY + userId,
                start, end);
        if (shortsIds == null || shortsIds.isEmpty()) {
            // 说明没有观看过的短视频
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        List<UserShortsVideoVO> shortsVOs = userVideoPort.listByIdsOrdered(shortsIds).stream()
                .map(summary -> BeanUtil.toBean(summary, UserShortsVideoVO.class))
                .toList();

        // 过滤掉已经删除的短视频id
        Set<String> dbIds = shortsVOs.stream().map(UserShortsVideoVO::getId).collect(Collectors.toSet());
        Set<String> missingIds = new HashSet<>(shortsIds);
        missingIds.removeAll(dbIds);
        if (!missingIds.isEmpty()) {
            String tsKey = RedisConstants.USER_SHORTS_PLAY + userId;
            redisTemplate.opsForZSet().remove(tsKey, missingIds.toArray());
        }
        if (shortsVOs.isEmpty()) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        // 转换视频封面url和视频url
        shortsVOs.forEach(vo -> {
            vo.setCoverUrl(storageUtil.signGetUrl(vo.getCoverUrl(), STORAGE_EXPIRE));
            vo.setVideoUrl(storageUtil.signGetUrl(vo.getVideoUrl(), STORAGE_EXPIRE));
        });

        List<Long> authorIds = shortsVOs
                .stream()
                .map(UserShortsVideoVO::getAuthorId)
                .collect(Collectors.toList());
        // 获取作者昵称
        Map<Long, String> authorNicknames = getAuthorNicknames(authorIds);
        // 获取作者头像url
        Map<Long, String> authorAvatarUrls = getAuthorAvatarUrls(authorIds);

        // 填充作者昵称和头像url
        shortsVOs.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(storageUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), STORAGE_EXPIRE));
        });

        // 获取用户关注状态
        Map<Long, Boolean> followMap = getFollowMap(userId, authorIds);
        // 填充关注状态
        shortsVOs.forEach(vo -> {
            Boolean isFollow = followMap.get(vo.getAuthorId());
            vo.setIsFollow(isFollow != null && isFollow);
        });

        // 获取点赞状态
        Map<Long, Boolean> collect = userVideoPort.likedMap(userId, shortsIds);
        // 填充点赞状态
        shortsVOs.forEach(vo -> {
            vo.setIsLike(collect.getOrDefault(Long.valueOf(vo.getId()), false));
        });

        long total = redisTemplate.opsForZSet().zCard(RedisConstants.USER_SHORTS_PLAY + userId);

        long pages = total / pageSize;
        if (total % pageSize != 0) {
            pages++;
        }

        // 返回结果
        return Result.success(PageResult.of(total, pages, shortsVOs));
    }

    @Override
    public Result<UserVO> getCurrentUserInfo() {
        Long userId = getUserId();
        User userInfo = getById(userId);

        userInfo.setAvatarUrl(storageUtil.signGetUrl(userInfo.getAvatarUrl(), STORAGE_EXPIRE));
        userInfo.setBgImage(storageUtil.signGetUrl(userInfo.getBgImage(), STORAGE_EXPIRE));

        return Result.success(BeanUtil.toBean(userInfo, UserVO.class));
    }

}
