package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminHotSearchHistoryController;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HotSearchHistoryAuthorizationTest {
    final HotSearchHistoryPort port = mock(HotSearchHistoryPort.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminHotSearchHistoryController(port)).build();
    final String root = "/admin/hot-search/";
    final String date = "2026-10-20";

    @AfterEach void cleanup() { UserContext.removeUser(); }

    @Test void allFiveRoutesRequireTheExistingAdministratorIdentity() throws Exception {
        for (String user : List.of("", "2")) {
            UserContext.setUser(user);
            for (String route : List.of("dates", "groups", "keywords", "users", "events")) {
                mvc.perform(get(root + route).param("date", date).param("groupId", "g").param("keywordId", "7"))
                        .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(port);
    }

    @Test void authorizedQueriesForwardOnlyTheRequestedScopeAndCursor() throws Exception {
        UserContext.setUser("1");
        when(port.dates(null, 20)).thenReturn(new Page<>(List.of(), null));
        when(port.groups(date, "8", 10)).thenReturn(new Page<>(List.of(), "18"));
        when(port.keywords(date, "g", null, 20)).thenReturn(new Page<>(List.of(), null));
        when(port.users(date, "g", 7L, null, 20)).thenReturn(new Page<>(List.of(), null));
        when(port.events(date, "g", 7L, 9L, "100.5", 10)).thenReturn(new Page<>(List.of(), null));
        mvc.perform(get(root + "dates")).andExpect(status().isOk());
        mvc.perform(get(root + "groups").param("date", date).param("cursor", "8").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").value("18"));
        mvc.perform(get(root + "keywords").param("date", date).param("groupId", "g")).andExpect(status().isOk());
        mvc.perform(get(root + "users").param("date", date).param("groupId", "g").param("keywordId", "7")).andExpect(status().isOk());
        mvc.perform(get(root + "events").param("date", date).param("groupId", "g").param("keywordId", "7")
                .param("userId", "9").param("cursor", "100.5").param("size", "10")).andExpect(status().isOk());
        verify(port).dates(null, 20);
        verify(port).groups(date, "8", 10);
        verify(port).keywords(date, "g", null, 20);
        verify(port).users(date, "g", 7L, null, 20);
        verify(port).events(date, "g", 7L, 9L, "100.5", 10);
        verifyNoMoreInteractions(port);
    }

    @Test void remoteValidationAndAvailabilityFailuresKeepTheirHttpStatus() throws Exception {
        UserContext.setUser("1");
        Request request = Request.create(Request.HttpMethod.GET, "http://search", Map.of(), null, StandardCharsets.UTF_8, null);
        for (int status : new int[]{400, 503}) {
            doThrow(FeignException.errorStatus("groups",
                    Response.builder().request(request).status(status).reason("unavailable").build())).when(port).groups(date, null, 20);
            mvc.perform(get(root + "groups").param("date", date)).andExpect(status().is(status));
        }
    }
}
