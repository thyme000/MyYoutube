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
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
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
    private final RocketMQTemplate rocketMQTemplate;
    private final EsSyncPublisher esSyncPublisher;
    private final UserSummaryCache summaryCache;

    @Override
    public UserVO getUserInfo(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(BizException.BIZ_ERROR, "用户不存在");
        }

        user.setAvatarUrl(storageUtil.signGetUrl(user.getAvatarUrl(), STORAGE_EXPIRE));
        user.setBgImage(storageUtil.signGetUrl(user.getBgImage(), STORAGE_EXPIRE));

        return BeanUtil.toBean(user, UserVO.class);
    }

    @Override
    public String updateAvatar(MultipartFile avatar) {
        String upload = getUpdatedUrl(avatar, "avatar");
        if (upload == null) {
            throw new BizException(BizException.BIZ_ERROR, "上传头像失败");
        }
        // 更新用户头像url
        lambdaUpdate()
                .set(User::getAvatarUrl, upload)
                .eq(User::getId, UserContext.getUserId())
                .update();
        summaryCache.evict(Long.valueOf(UserContext.getUserId()));
        return storageUtil.signGetUrl(upload, STORAGE_EXPIRE);
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
    public String updateBgImage(MultipartFile bgImage) {
        String upload = getUpdatedUrl(bgImage, "bg");
        if (upload == null) {
            throw new BizException(BizException.BIZ_ERROR, "上传背景图片失败");
        }
        // 更新用户头像url
        lambdaUpdate()
                .set(User::getBgImage, upload)
                .eq(User::getId, UserContext.getUserId())
                .update();
        return storageUtil.signGetUrl(upload, STORAGE_EXPIRE);
    }

    @Override
    public String updateUser(Long id, UserDTO userDTO) {
        if (id == null) {
            throw new BizException(BizException.BIZ_ERROR, "用户ID不能为空");
        }
        User user = getById(id);
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户不存在");
        }
        String oldName = user.getNickname();
        user.setNickname(userDTO.getNickname() == null || userDTO.getNickname().isEmpty() ?
                oldName : userDTO.getNickname());
        user.setIntroduction(userDTO.getIntroduction() == null || userDTO.getIntroduction().isEmpty() ?
                user.getIntroduction() : userDTO.getIntroduction());
        boolean success = updateById(user);
        if (!success) {
            throw new BizException(BizException.BIZ_ERROR, "服务器繁忙,请稍后再试～");
        }
        summaryCache.evict(user.getId());
        // 同步 ES 文档: 发消息, 由 search 侧以 MySQL 为准重建
        esSyncPublisher.syncUserDoc(user.getId());

        log.info("用户{},更新用户资料成功...", id);
        return "修改成功";
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


    // getShortsHistory 已随 user_shorts_play 行为记录一并退役(统一内容池一期)

    @Override
    public UserVO getCurrentUserInfo() {
        Long userId = getUserId();
        User userInfo = getById(userId);

        userInfo.setAvatarUrl(storageUtil.signGetUrl(userInfo.getAvatarUrl(), STORAGE_EXPIRE));
        userInfo.setBgImage(storageUtil.signGetUrl(userInfo.getBgImage(), STORAGE_EXPIRE));

        return BeanUtil.toBean(userInfo, UserVO.class);
    }

}
