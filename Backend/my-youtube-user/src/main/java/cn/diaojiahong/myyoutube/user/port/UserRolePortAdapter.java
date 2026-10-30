package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserRolePortAdapter implements UserRolePort {
    private final UserMapper users;

    @Override
    public UserRole getRole(Long userId) {
        // 权限直接读库，不能复用用户摘要缓存。
        User user = users.selectOne(Wrappers.<User>lambdaQuery()
                .select(User::getRole)
                .eq(User::getId, userId)
                .eq(User::getIsDelete, 0));

        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在或已删除");
        }

        if (user.getRole() == null) {
            throw new IllegalStateException("用户角色未配置");
        }

        return user.getRole();
    }
}
