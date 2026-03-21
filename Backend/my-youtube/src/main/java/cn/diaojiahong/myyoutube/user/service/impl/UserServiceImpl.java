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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * <p>
 * 用户核心表（视频发布者/网站登录用户） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final AliOssUtil aliOssUtil;

    @Override
    public Result<UserVO> getUserInfo() {
        UserDTO userDTO = UserContext.getUser();

        User user = getById(userDTO.getId());

        return Result.success(BeanUtil.toBean(user, UserVO.class));
    }

    @Override
    public Result<String> updateAvatar(MultipartFile avatar) {
        String upload = updateFile(avatar, "avatar");
        if (upload == null) {
            return Result.fail("上传头像失败");
        }
        // 更新用户头像url
        User user = getById(UserContext.getUser().getId());
        user.setAvatarUrl(upload);
        updateById(user);

        return Result.success(upload);
    }

    private String updateFile(MultipartFile file, String bizPrefix) {
        byte[] bytes = null;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return null;
        }
        String originalFilename = file.getOriginalFilename();
        Long userId = UserContext.getUser().getId();
        return aliOssUtil.upload(bytes, originalFilename, userId, bizPrefix);
    }

    @Override
    public Result<String> updateBgImage(MultipartFile bgImage) {
        String upload = updateFile(bgImage, "bg");
        if (upload == null) {
            return Result.fail("上传背景图片失败");
        }
        // 更新用户背景图片(bgImage);
        User user = getById(UserContext.getUser().getId());
        user.setBgImage(upload);
        updateById(user);

        return Result.success(upload);
    }
}
