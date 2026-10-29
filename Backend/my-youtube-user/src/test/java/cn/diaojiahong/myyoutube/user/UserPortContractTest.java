package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.cache.UserSummaryCache;
import cn.diaojiahong.myyoutube.user.controller.InternalUserController;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.vo.UserVO;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserPortContractTest {
    private final IUserService users = mock(IUserService.class);
    private final CounterBuffer counters = mock(CounterBuffer.class);
    private final UserSummaryCache cache = mock(UserSummaryCache.class);
    private final UserPortAdapter adapter = new UserPortAdapter(users, counters, cache);

    @Test void followsStillUseTheirCounterBuffer() {
        adapter.adjustCounters(List.of(new CounterAdjust(7L, "fans", 1), new CounterAdjust(8L, "attentionCount", -1)));
        verify(counters).add(CounterType.USER_FANS, 7L, 1);
        verify(counters).add(CounterType.USER_ATTENTION, 8L, -1);
        assertThrows(IllegalArgumentException.class,
                () -> adapter.adjustCounters(List.of(new CounterAdjust(7L, "contentCount", 1))));
        verifyNoMoreInteractions(counters);
    }

    @Test void retiredContentCounterEndpointIsUnavailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new InternalUserController(adapter)).build()
                .perform(post("/internal/user/counters/content-event")
                        .contentType("application/json").content("{\"eventId\":\"old\",\"userId\":7,\"delta\":1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(users, counters, cache);
    }

    @Test void userRepresentationsDoNotExposeARedundantContentCount() {
        var json = new ObjectMapper();
        for (Object representation : List.of(new User(), new UserVO(), new UserSummary())) {
            assertTrue(json.valueToTree(representation).has("fans"));
            assertFalse(json.valueToTree(representation).has("contentCount"));
        }
    }
}
