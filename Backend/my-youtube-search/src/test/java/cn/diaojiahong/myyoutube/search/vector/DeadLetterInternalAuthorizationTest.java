package cn.diaojiahong.myyoutube.search.vector;

import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.search.api.ContentVectorDeadLetterPort;
import cn.diaojiahong.myyoutube.search.controller.InternalContentVectorDeadLetterController;
import cn.diaojiahong.myyoutube.security.AuthConstants;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeadLetterInternalAuthorizationTest {
    private final ContentVectorDeadLetterService service = mock(ContentVectorDeadLetterService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalContentVectorDeadLetterController(
                    service, new InternalApiCredentials("internal-test-token")))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final String path = "/internal/dead-letters/content-vectors/messages";
    private final String reference = "{\"brokerName\":\"broker\",\"queueId\":0,\"offset\":5}";

    @Test
    void missingOrInvalidInternalCredentialsCannotReachTheService() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path + "/replay").param("actorId", "1")
                        .contentType("application/json").content(reference))
                .andExpect(status().isUnauthorized());

        for (String token : List.of("", "wrong")) {
            mvc.perform(get(path).header(AuthConstants.INTERNAL_TOKEN_HEADER, token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path + "/replay").param("actorId", "1")
                            .header(AuthConstants.INTERNAL_TOKEN_HEADER, token)
                            .contentType("application/json").content(reference))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
    }

    @Test
    void trustedInternalCallerCanQueryAndForwardTheRealActor() throws Exception {
        when(service.query(null, 20)).thenReturn(new ContentVectorDeadLetterPort.Page(List.of(), null, true));
        when(service.replay(any(), eq(42L))).thenReturn(new ContentVectorDeadLetterPort.ReplayResult(
                ContentVectorDeadLetterPort.ReplayStatus.ACCEPTED, 7L, "已登记"));

        mvc.perform(get(path).header(AuthConstants.INTERNAL_TOKEN_HEADER, "internal-test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post(path + "/replay").param("actorId", "42")
                        .header(AuthConstants.INTERNAL_TOKEN_HEADER, "internal-test-token")
                        .contentType("application/json").content(reference))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));

        verify(service).query(null, 20);
        verify(service).replay(new ContentVectorDeadLetterPort.Reference("broker", 0, 5L), 42L);
    }
}
