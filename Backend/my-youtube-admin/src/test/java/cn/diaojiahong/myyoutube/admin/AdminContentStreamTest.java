package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminContentStreamController;
import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.user.api.UserRolePort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.video.api.ContentAdminPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminContentStreamTest {
    private final UserRolePort roles = mock(UserRolePort.class);
    private final ContentAdminPort content = mock(ContentAdminPort.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        UserContext.setUser("9");
        when(roles.getRole(9L)).thenReturn(UserRole.ADMIN);
        mvc = MockMvcBuilders.standaloneSetup(new AdminContentStreamController(new AdminAuthorization(roles), content))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").header(PlaybackTokens.INTERNAL_HEADER, "review-ticket")).build();
    }

    @AfterEach
    void clear() { UserContext.removeUser(); }

    @Test
    void adminGetsUncachedPlaylistsWithDirectStorageUrls() throws Exception {
        when(content.masterPlaylist(any())).thenReturn("#EXTM3U\n720/index.m3u8?playbackToken=review-ticket\n");
        mvc.perform(get("/admin/content/123/streams/master.m3u8")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/vnd.apple.mpegurl"));
        String playlist = "#EXTM3U\nhttps://storage.test/video.mp4?signature=test\n";
        when(content.playlist(any())).thenReturn(playlist);
        mvc.perform(get("/admin/content/123/streams/720/index.m3u8")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(playlist));
        verify(content).playlist(new ContentAdminPort.ReviewStream(123L, 9L, 720, "review-ticket"));
        verify(roles, times(2)).getRole(9L);
        mvc.perform(get("/admin/content/123/streams/720/media.mp4")).andExpect(status().isNotFound());
    }

    @Test
    void roleDowngradeStopsTheNextPlaylistRefresh() throws Exception {
        when(content.playlist(any())).thenReturn("#EXTM3U\nhttps://storage.test/video.mp4?signature=test\n");
        mvc.perform(get("/admin/content/123/streams/720/index.m3u8")).andExpect(status().isOk());
        when(roles.getRole(9L)).thenReturn(UserRole.USER);
        mvc.perform(get("/admin/content/123/streams/720/index.m3u8")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(content, times(1)).playlist(any());
        verify(roles, times(2)).getRole(9L);
    }
}
