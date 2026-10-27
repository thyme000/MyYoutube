package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminAudioController;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAudioControllerTest {
    final AudioAdminPort audios = mock(AudioAdminPort.class);
    final AdminAudioController controller = new AdminAudioController(audios);
    @AfterEach void clear() { UserContext.removeUser(); }
    @Test void ordinaryUsersCannotListListenOrDecide() {
        UserContext.setUser("9");
        assertThrows(BizException.class, () -> controller.waiting(new PageQuery()));
        assertThrows(BizException.class, () -> controller.detail(1L));
        assertThrows(BizException.class, () -> controller.decide(1L, new AdminAudioController.Decision("PASS", "通过")));
        verifyNoInteractions(audios);
    }
    @Test void actorComesFromAuthenticatedAdminWithoutAnIndexVersion() {
        UserContext.setUser("1"); controller.decide(9L, new AdminAudioController.Decision("REJECT", "封面需处理"));
        verify(audios).decide(9L, new AudioAdminPort.Decision("REJECT", "封面需处理", 1L));
    }
    @Test void httpDecisionNeedsNoVersionAndActorCannotBeSuppliedByCaller() throws Exception {
        UserContext.setUser("1"); when(audios.decide(anyLong(), any())).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/admin/audios/9/decision").contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"BAN\",\"reason\":\"治理封禁\",\"actorId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        verify(audios).decide(9L, new AudioAdminPort.Decision("BAN", "治理封禁", 1L));
    }

    @Test void adminDetailReturnsTheStoredUsageCount() throws Exception {
        UserContext.setUser("1");
        when(audios.detail(9L)).thenReturn(new AudioAdminPort.Detail(
                new AudioAdminPort.Item("9", "音频", "ready", "approved", "manual"),
                null, null, null, null, java.util.List.of(), 23L));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/admin/audios/9")).andExpect(status().isOk()).andExpect(jsonPath("$.data.usageCount").value(23));
    }
}
