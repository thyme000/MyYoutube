package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminAudioController;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminAudioControllerTest {
    final AudioAdminPort audios = mock(AudioAdminPort.class);
    final AdminAudioController controller = new AdminAudioController(audios, AuthorizationFixture.authorization());
    @AfterEach void clear() { UserContext.removeUser(); }
    @Test void ordinaryUsersCannotListListenOrDecide() {
        UserContext.setUser("9");
        assertThrows(BizException.class, () -> controller.page(new PageQuery()));
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

    @Test void adminDetailReturnsTheStoredHistoricalAuthorCount() throws Exception {
        UserContext.setUser("1");
        when(audios.detail(9L)).thenReturn(new AudioAdminPort.Detail(
                new AudioAdminPort.Item("9", "音频", "ready", "approved", "manual", 23L),
                null, null, null, null, java.util.List.of()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/admin/audios/9")).andExpect(status().isOk()).andExpect(jsonPath("$.data.audio.historicalAuthorCount").value(23));
    }

    @Test void adminCanPageAllAudiosWithStoredHistoricalCounts() throws Exception {
        UserContext.setUser("1");
        when(audios.page(any())).thenReturn(PageResult.of(11, 2,
                java.util.List.of(new AudioAdminPort.Item("9007199254740993", "已通过音频", "ready", "approved", "manual", 23L))));
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(get("/admin/audios").param("pageNo", "2").param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.pages").value(2))
                .andExpect(jsonPath("$.data.list[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.list[0].historicalAuthorCount").value(23));
        verify(audios).page(argThat(query -> query.getPageNo() == 2 && query.getPageSize() == 10));
    }

    @Test void invalidAudioPaginationIsRejectedBeforeCallingThePort() throws Exception {
        UserContext.setUser("1");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/admin/audios").param("pageNo", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/audios").param("pageSize", "101")).andExpect(status().isBadRequest());
        verifyNoInteractions(audios);
    }

    @Test void busyReviewReturnsConflictAndAnExplicitRetryMessage() throws Exception {
        UserContext.setUser("1");
        String message = "音频审核暂时繁忙，本次操作未完成，请稍后重试";
        when(audios.decide(anyLong(), any())).thenThrow(new BizException(
                ErrorCode.STATE_CONFLICT, message));
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/admin/audios/9/decision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"BAN\",\"reason\":\"治理封禁\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.message").value(message));
    }
}
