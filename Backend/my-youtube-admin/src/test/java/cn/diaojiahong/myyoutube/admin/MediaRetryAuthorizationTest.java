package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.admin.controller.AdminMediaRetryController;
import cn.diaojiahong.myyoutube.transcode.api.MediaRetryPort;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

class MediaRetryAuthorizationTest {
    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    @Test
    void allRetryOperationsRequireAdmin() {
        MediaRetryPort port=mock(MediaRetryPort.class);
        var controller=new AdminMediaRetryController(port, AuthorizationFixture.authorization());
        var request=new MediaRetryPort.Request("one",List.of(2L));
        UserContext.setUser("2");
        assertThrows(BizException.class,() -> controller.create(request));
        assertThrows(BizException.class,() -> controller.current());
        assertThrows(BizException.class,() -> controller.history(null,20));
        assertThrows(BizException.class,() -> controller.batch(1L));
        assertThrows(BizException.class,() -> controller.items(1L));
        verifyNoInteractions(port);
        UserContext.setUser("1");
        when(port.create(request,1L)).thenReturn(new MediaRetryPort.Admission(
                false,
                "RETRY_BATCH_RUNNING",
                "当前已有批次在运行",
                null));
        assertEquals(409,controller.create(request).getStatusCode().value());
        verify(port).create(request,1L);
    }

    @Test
    void httpQueryBindsOptionalFiltersAndCreationReturnsAccepted() throws Exception {
        MediaRetryPort port=mock(MediaRetryPort.class);
        var controller=new AdminMediaRetryController(port, AuthorizationFixture.authorization());
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        UserContext.setUser("1");
        when(port.query(any())).thenReturn(new CursorPage<>(List.of(),null));
        mvc.perform(get("/admin/media-tasks").param("taskType","SUMMARY").param("size","20"))
                .andExpect(status().isOk());
        verify(port).query(argThat(q -> "SUMMARY".equals(q.taskType()) && q.size()==20 && q.phase()==null));
        when(port.history(any(),any())).thenReturn(new CursorPage<>(List.of(),null));
        mvc.perform(get("/admin/media-retry-batches").param("cursor","9007199254740993").param("size","20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        verify(port).history(9007199254740993L,20);
        when(port.create(any(),eq(1L))).thenReturn(new MediaRetryPort.Admission(true,"RETRY_BATCH_CREATED","已登记",null));
        mvc.perform(post("/admin/media-retry-batches").contentType("application/json")
                .content("{\"requestId\":\"one\",\"taskIds\":[2]}"))
                .andExpect(status().isAccepted());
    }
}
