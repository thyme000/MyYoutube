package cn.diaojiahong.myyoutube.user.service.impl;

import cn.diaojiahong.myyoutube.common.constants.RedisConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.dto.UserHistoryPageQuery;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserLongHistoryVideoVO;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.domain.vo.ShortsVO;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
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

    private final AliOssUtil aliOssUtil;
    private static final Long OSS_EXPIRE = 3600 * 24L;// 24小时过期时间
    private final StringRedisTemplate redisTemplate;
    private final VideoMapper videoMapper;

    @Override
    public Result<UserVO> getUserInfo(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        user.setAvatarUrl(aliOssUtil.signGetUrl(user.getAvatarUrl(), OSS_EXPIRE));
        user.setBgImage(aliOssUtil.signGetUrl(user.getBgImage(), OSS_EXPIRE));

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
                .eq(User::getId, UserContext.getUser().getId())
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
        return aliOssUtil.uploadForObjectKey(bytes, file.getOriginalFilename(),
                UserContext.getUser().getId(), bizPrefix);
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
                .eq(User::getId, UserContext.getUser().getId())
                .update();
        return Result.success(upload);
    }

    @Override
    public Result<String> updateUser(Long id, UserDTO userDTO) {
        if (id == null) {
            return Result.fail("用户ID不能为空");
        }
        boolean success = lambdaUpdate()
                .set(User::getNickname, userDTO.getNickname())
                .set(User::getIntroduction, userDTO.getIntroduction())
                .eq(User::getId, id)
                .update();
        if (!success) {
            return Result.fail("服务器繁忙,请稍后再试～");
        }
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

    @Override
    public Result<PageResult<UserLongHistoryVideoVO>> getLongHistory(UserHistoryPageQuery userHistoryPageQuery) {
        int pageSize = userHistoryPageQuery.getPageSize();
        int pageNo = userHistoryPageQuery.getPageNo();
        // 起始索引
        int start = (pageNo - 1) * pageSize;
        // 结束索引
        int end = start + pageSize - 1;
        Long userId = userHistoryPageQuery.getUserId();
        if (userId == null) {
            return Result.fail("用户ID不能为空");
        }

        // 长视频id
        Set<String> longVideoIds = redisTemplate.opsForZSet().reverseRange(
                RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId,
                start, end);
        if (longVideoIds == null || longVideoIds.isEmpty()) {
            // 没有长视频
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        List<Video> longVideos = videoMapper.selectOrderedList(longVideoIds);

        // 过滤掉已经删除的长视频id
        Set<String> dbIds = longVideos.stream().map(v -> String.valueOf(v.getId())).collect(Collectors.toSet());
        Set<String> missingIds = new HashSet<>(longVideoIds);
        missingIds.removeAll(dbIds);
        if (!missingIds.isEmpty()) {
            String tsKey = RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId;
            String ptKey = RedisConstants.USER_PLAYBACK_RESUME + userId;
            redisTemplate.opsForZSet().remove(tsKey, missingIds.toArray());
            redisTemplate.opsForHash().delete(ptKey, missingIds.toArray());
        }

        // 转换成VO
        List<UserLongHistoryVideoVO> longHistoryVideoVOs = longVideos.stream()
                .map(video -> BeanUtil.toBean(video, UserLongHistoryVideoVO.class))
                .toList();

        // 转换视频封面url和视频url
        longHistoryVideoVOs.forEach(vo -> {
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_EXPIRE));
            vo.setVideoUrl(aliOssUtil.signGetUrl(vo.getVideoUrl(), OSS_EXPIRE));
        });

        // 获取作者昵称
        Map<Long, String> authorNicknames = getAuthorNicknames(
                longHistoryVideoVOs
                        .stream()
                        .map(UserLongHistoryVideoVO::getAuthorId)
                        .collect(Collectors.toList()));
        // 获取作者头像url
        Map<Long, String> authorAvatarUrls = getAuthorAvatarUrls(
                longHistoryVideoVOs
                        .stream()
                        .map(UserLongHistoryVideoVO::getAuthorId)
                        .collect(Collectors.toList()));

        // 填充作者昵称和头像url
        longHistoryVideoVOs.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), OSS_EXPIRE));
        });

        // 获取用户长视频观看时长记录
        Map<Object, Object> playbackResumes = redisTemplate
                .opsForHash()
                .entries(RedisConstants.USER_PLAYBACK_RESUME + userId);

        // 填充观看时长记录
        longHistoryVideoVOs.forEach(vo -> {
            vo.setPlaybackTime(Long.parseLong(playbackResumes.get(vo.getId() == null ? 0L : vo.getId()).toString()));
        });

        long total = redisTemplate.opsForZSet().zCard(RedisConstants.USER_PLAYBACK_RESUME_TIMESTAMP + userId);

        long pages = total / pageSize;
        if (total % pageSize != 0) {
            pages++;
        }

        // 返回结果
        return Result.success(PageResult.of(total, pages, longHistoryVideoVOs));
    }

    @Override
    public Result<PageResult<ShortsVO>> getShortsHistory(UserHistoryPageQuery userHistoryPageQuery) {
        int pageSize = userHistoryPageQuery.getPageSize();
        int pageNo = userHistoryPageQuery.getPageNo();
        // 起始索引
        int start = (pageNo - 1) * pageSize;
        // 结束索引
        int end = start + pageSize - 1;
        Long userId = userHistoryPageQuery.getUserId();
        if (userId == null) {
            return Result.fail("用户ID不能为空");
        }

        // 查询短视频id
        Set<String> shortsIds = redisTemplate.opsForZSet().reverseRange(
                RedisConstants.USER_SHORTS_PLAY + userId,
                start, end);
        if (shortsIds == null || shortsIds.isEmpty()) {
            // 说明没有观看过的短视频
            return Result.success(PageResult.of(0, 0, List.of()));
        }

        List<Video> shortVideos = videoMapper.selectOrderedList(shortsIds);

        // 过滤掉已经删除的短视频id
        Set<String> dbIds = shortVideos.stream().map(v -> String.valueOf(v.getId())).collect(Collectors.toSet());
        Set<String> missingIds = new HashSet<>(shortsIds);
        missingIds.removeAll(dbIds);
        if (!missingIds.isEmpty()) {
            String tsKey = RedisConstants.USER_SHORTS_PLAY + userId;
            redisTemplate.opsForZSet().remove(tsKey, missingIds.toArray());
        }

        // 转换成VO
        List<ShortsVO> shortsVOs = shortVideos.stream()
                .map(video -> BeanUtil.toBean(video, ShortsVO.class))
                .toList();

        // 转换视频封面url和视频url
        shortsVOs.forEach(vo -> {
            vo.setCoverUrl(aliOssUtil.signGetUrl(vo.getCoverUrl(), OSS_EXPIRE));
            vo.setVideoUrl(aliOssUtil.signGetUrl(vo.getVideoUrl(), OSS_EXPIRE));
        });

        // 获取作者昵称
        Map<Long, String> authorNicknames = getAuthorNicknames(
                shortsVOs
                        .stream()
                        .map(ShortsVO::getAuthorId)
                        .collect(Collectors.toList()));
        // 获取作者头像url
        Map<Long, String> authorAvatarUrls = getAuthorAvatarUrls(
                shortsVOs
                        .stream()
                        .map(ShortsVO::getAuthorId)
                        .collect(Collectors.toList()));

        // 填充作者昵称和头像url
        shortsVOs.forEach(vo -> {
            vo.setAuthorNickname(authorNicknames.get(vo.getAuthorId()));
            vo.setAuthorAvatarUrl(aliOssUtil.signGetUrl(authorAvatarUrls.get(vo.getAuthorId()), OSS_EXPIRE));
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
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        User userInfo = getById(user.getId());

        userInfo.setAvatarUrl(aliOssUtil.signGetUrl(userInfo.getAvatarUrl(), OSS_EXPIRE));
        userInfo.setBgImage(aliOssUtil.signGetUrl(userInfo.getBgImage(), OSS_EXPIRE));

        return Result.success(BeanUtil.toBean(userInfo, UserVO.class));
    }

}
