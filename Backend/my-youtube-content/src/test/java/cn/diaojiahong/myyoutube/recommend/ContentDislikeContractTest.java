package cn.diaojiahong.myyoutube.recommend;

import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.recommend.controller.ContentDislikeController;
import cn.diaojiahong.myyoutube.recommend.service.ContentDislikeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentDislikeContractTest {
    private final ContentDislikeService dislikes = mock(ContentDislikeService.class);
    private MockMvc mvc;

    @BeforeEach
    void signIn() {
        UserContext.setUser("7");
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new ContentDislikeController(dislikes))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .build();
    }

    @AfterEach
    void signOut() {
        UserContext.removeUser();
    }

    @Test
    void markAndReadReturnTheCurrentExpiry() throws Exception {
        long expiresAt = 1_800_000_000_000L;
        when(dislikes.mark(7L, 123L, 1000L)).thenReturn(expiresAt);
        when(dislikes.activeExpiry(7L, 123L)).thenReturn(expiresAt);

        mvc.perform(post("/like/dislike/123").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchDuration\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(Long.toString(expiresAt)));
        mvc.perform(get("/like/dislike/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(Long.toString(expiresAt)));
        verify(dislikes).mark(7L, 123L, 1000L);
    }

    @Test
    void invalidWatchDurationIsRejectedBeforeWriting() throws Exception {
        mvc.perform(post("/like/dislike/123").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"watchDuration\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(dislikes);
    }
}
