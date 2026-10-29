package cn.diaojiahong.myyoutube.admin.security;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.exception.DependencyException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

@Component
@RequiredArgsConstructor
public class AdminAuthorization {
    private static final String CURRENT_IDENTITY = AdminAuthorization.class.getName() + ".identity";
    private final UserRolePort roles;

    public Long requireAdmin() {
        Identity identity = identity();
        if (!identity.role().isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
        return identity.userId();
    }

    public Long requireSuperAdmin() {
        Identity identity = identity();
        if (identity.role() != UserRole.SUPER_ADMIN) {
            throw new BizException(ErrorCode.SUPER_ADMIN_REQUIRED);
        }
        return identity.userId();
    }

    private Identity identity() {
        Long userId = UserContext.requireUserId();
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object cached = request == null ? null : request.getAttribute(CURRENT_IDENTITY, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof Identity identity && identity.userId().equals(userId)) {
            return identity;
        }

        UserRole role;
        try {
            role = roles.getRole(userId);
        } catch (BizException error) {
            if (error.getErrorCode() == ErrorCode.NOT_FOUND) {
                throw new BizException(ErrorCode.FORBIDDEN, "账号不存在或已删除");
            }
            throw error;
        }
        if (role == null) {
            throw new DependencyException(ErrorCode.DEPENDENCY_FAILURE, "用户服务未返回角色", null);
        }

        // 仅在本次请求中复用校验结果，下一次请求仍重新查库。
        Identity identity = new Identity(userId, role);
        if (request != null) {
            request.setAttribute(CURRENT_IDENTITY, identity, RequestAttributes.SCOPE_REQUEST);
        }
        return identity;
    }

    private record Identity(Long userId, UserRole role) {}
}
