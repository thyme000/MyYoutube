package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AuthorizationFixture {
    private AuthorizationFixture() {
    }

    static AdminAuthorization authorization() {
        UserRolePort roles = mock(UserRolePort.class);
        when(roles.getRole(anyLong())).thenReturn(UserRole.USER);
        when(roles.getRole(1L)).thenReturn(UserRole.SUPER_ADMIN);
        return new AdminAuthorization(roles);
    }
}
