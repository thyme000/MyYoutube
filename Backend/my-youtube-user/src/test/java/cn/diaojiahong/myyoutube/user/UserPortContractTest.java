package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
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

    private final UserSummaryCache cache = mock(UserSummaryCache.class);

    private final UserPortAdapter adapter = new UserPortAdapter(users, cache, mock(EsSyncPublisher.class));

    @Test
    void retiredCounterAdjustEndpointIsUnavailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new InternalUserController(adapter)).build()
                .perform(post("/internal/user/counters/adjust")
                        .contentType("application/json").content("[]"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(users, cache);
    }

    @Test
    void retiredContentCounterEndpointIsUnavailable() throws Exception {
        MockMvcBuilders.standaloneSetup(new InternalUserController(adapter)).build()
                .perform(post("/internal/user/counters/content-event")
                        .contentType("application/json").content("{\"eventId\":\"old\",\"userId\":7,\"delta\":1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(users, cache);
    }

    @Test
    void receivedLikesEndpointDelegatesToTheAdapter() throws Exception {
        UserPortAdapter receivedLikes = mock(UserPortAdapter.class);
        when(receivedLikes.overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 4, 2))))
                .thenReturn(List.of(7L));

        MockMvcBuilders.standaloneSetup(new InternalUserController(receivedLikes)).build()
                .perform(post("/internal/user/received-likes")
                        .contentType("application/json")
                        .content("[{\"userId\":7,\"likeCount\":4,\"version\":2}]"))
                .andExpect(status().isOk());
        verify(receivedLikes).overwriteReceivedLikes(List.of(new ReceivedLikeUpdate(7, 4, 2)));
    }

    @Test
    void userRepresentationsDoNotExposeARedundantContentCount() {
        var json = new ObjectMapper();
        for (Object representation : List.of(new User(), new UserVO(), new UserSummary())) {
            assertTrue(json.valueToTree(representation).has("fans"));
            assertFalse(json.valueToTree(representation).has("contentCount"));
        }
    }
}
