package cn.diaojiahong.myyoutube.user.service.impl;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
    private static final Long OSS_EXPIRE = 60 * 2L;// 2小时过期时间

    @Override
    public Result<UserVO> getUserInfo() {
        UserDTO userDTO = UserContext.getUser();

        User user = getById(userDTO.getId());

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

}
