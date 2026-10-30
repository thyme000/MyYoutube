package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminController;
import cn.diaojiahong.myyoutube.admin.service.IAdminService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentAdminAuthorizationTest {
    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    @Test
    void contentOperationsRequireAdminAndUseStringIdentity() {
        ContentAdminPort port = mock(ContentAdminPort.class);
        var controller = new AdminController(mock(IAdminService.class), port, AuthorizationFixture.authorization());
        assertThrows(BizException.class, () -> controller.content(1L));
        UserContext.setUser("2");
        assertThrows(BizException.class, () -> controller.media(1L));
        verifyNoInteractions(port);
        UserContext.setUser("1");
        controller.content(1L);
        controller.media(1L);
        verify(port).media(any());
        verify(port).contentStatus(any());
    }
}
