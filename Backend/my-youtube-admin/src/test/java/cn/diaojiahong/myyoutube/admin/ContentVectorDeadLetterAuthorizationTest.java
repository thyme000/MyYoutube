package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminContentVectorDeadLetterController;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.feign.FeignExceptionHandler;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort.*;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentVectorDeadLetterAuthorizationTest {
    private final ContentVectorDeadLetterPort port = mock(ContentVectorDeadLetterPort.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminContentVectorDeadLetterController(
            port,
            AuthorizationFixture.authorization()))
            .setControllerAdvice(new GlobalExceptionHandler(), new FeignExceptionHandler())
            .build();
    private final String path = "/admin/dead-letters/content-vectors/messages";
    private final String reference = "{\"brokerName\":\"broker\",\"queueId\":0,\"offset\":5}";

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    @Test
    void anonymousAndNonAdminCannotAccessEitherEndpoint() throws Exception {
        for (String user : List.of("", "2")) {
            UserContext.setUser(user);
            mvc.perform(get(path)).andExpect(status().is(user.isEmpty() ? 401 : 403));
            mvc.perform(post(path + "/replay").contentType("application/json").content(reference))
                    .andExpect(status().is(user.isEmpty() ? 401 : 403));
        }
        verifyNoInteractions(port);
    }

    @Test
    void queryForwardsCursorAndSizeUnderTheNewRoute() throws Exception {
        UserContext.setUser("1");
        when(port.query("cursor", 10)).thenReturn(new Page(List.of(), "next", true));
        mvc.perform(get(path).param("cursor", "cursor").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextCursor").value("next"));
        verify(port).query("cursor", 10);
    }

    @Test
    void acceptedIsDistinctFromCompletedAndUnconfirmedDelivery() throws Exception {
        UserContext.setUser("1");
        when(port.replay(any(), eq(1L))).thenReturn(new ReplayResult(ReplayStatus.ACCEPTED, 9L, "等待后台构建"),
                new ReplayResult(ReplayStatus.ALREADY_COMPLETED, 9L, "已有向量"),
                new ReplayResult(ReplayStatus.DELIVERY_UNCONFIRMED, 9L, "未确认"));
        mvc.perform(post(path + "/replay").contentType("application/json").content(reference))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mvc.perform(post(path + "/replay").contentType("application/json").content(reference))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/replay").contentType("application/json").content(reference))
                .andExpect(status().isServiceUnavailable());
        verify(port, times(3)).replay(new Reference("broker", 0, 5L), 1L);
    }

    @Test
    void unrecognizedRemote400IsNotAUserValidationError() throws Exception {
        UserContext.setUser("1");
        Request request = Request.create(
                Request.HttpMethod.GET,
                "http://search",
                Map.of(),
                null,
                StandardCharsets.UTF_8,
                null);
        when(port.query(null, 0)).thenThrow(FeignException.errorStatus(
                "query",
                Response.builder().request(request).status(400).reason("invalid size").build()));
        mvc.perform(get(path).param("size", "0")).andExpect(status().isBadGateway());
    }
}
