package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminCounterDriftController;
import cn.diaojiahong.myyoutube.common.counter.CounterDrift;
import cn.diaojiahong.myyoutube.common.counter.CounterRepairRequest;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserCounterAdminPort;
import cn.diaojiahong.myyoutube.content.api.ContentCounterAdminPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCounterDriftAuthorizationTest {

    private final ContentCounterAdminPort contents = mock(ContentCounterAdminPort.class);
    private final UserCounterAdminPort users = mock(UserCounterAdminPort.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminCounterDriftController(
                    contents, users, AuthorizationFixture.authorization()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    @Test
    void anonymousAndNonAdminCannotListOrRepair() throws Exception {
        for (String user : List.of("", "2")) {
            UserContext.setUser(user);
            mvc.perform(get("/admin/counter-drifts").param("type", "content:like"))
                    .andExpect(status().is(user.isEmpty() ? 401 : 403));
            mvc.perform(post("/admin/counter-drifts/repair")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"content:like\",\"ids\":[11]}"))
                    .andExpect(status().is(user.isEmpty() ? 401 : 403));
        }
        verifyNoInteractions(contents, users);
    }

    @Test
    void routesVideoAndUserTypesToTheServiceThatOwnsTheTable() throws Exception {
        UserContext.setUser("1");
        when(contents.drifts("content:like", 9L, 20))
                .thenReturn(new CursorPage<>(List.of(new CounterDrift(11, 5, 1, 4)), 11L));
        when(users.drifts("user:fans", null, 20))
                .thenReturn(new CursorPage<>(List.of(new CounterDrift(2, 0, 1, -1)), null));

        mvc.perform(get("/admin/counter-drifts").param("type", "content:like").param("cursor", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].diff").value(4))
                .andExpect(jsonPath("$.data.nextCursor").value(11));
        mvc.perform(get("/admin/counter-drifts").param("type", "user:fans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(2));

        verify(contents).drifts("content:like", 9L, 20);
        verify(users).drifts("user:fans", null, 20);
    }

    @Test
    void repairForwardsTheSelectedIds() throws Exception {
        UserContext.setUser("1");
        when(contents.repair(new CounterRepairRequest("content:share", List.of(11L)))).thenReturn(1);

        mvc.perform(post("/admin/counter-drifts/repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"content:share\",\"ids\":[11]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        verify(contents).repair(new CounterRepairRequest("content:share", List.of(11L)));
        verifyNoInteractions(users);
    }

    @Test
    void unknownTypeIsRejectedBeforeCallingEitherService() throws Exception {
        UserContext.setUser("1");

        mvc.perform(get("/admin/counter-drifts").param("type", "nope"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(contents, users);
    }
}
