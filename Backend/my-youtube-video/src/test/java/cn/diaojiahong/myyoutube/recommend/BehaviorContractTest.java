package cn.diaojiahong.myyoutube.recommend;

import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.recommend.controller.UserVideoBehaviorController;
import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.service.IUserVideoBehaviorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BehaviorContractTest {
    private IUserVideoBehaviorService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(IUserVideoBehaviorService.class);
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new UserVideoBehaviorController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build()))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPOSE", "expose", "UNKNOWN"})
    void rejectsUnsupportedEventsBeforeCallingService(String event) throws Exception {
        mvc.perform(post("/behavior/content").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(event)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAY", "FINISH", "LIKE", "SKIP", "DISLIKE"})
    void acceptsSupportedEvents(String event) throws Exception {
        mvc.perform(post("/behavior/content").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(event)))
                .andExpect(status().isOk());
        verify(service).report(argThat(dto -> dto.getEvent() == EventEnum.valueOf(event)
                && dto.getContentId() == 123L && dto.getWatchDuration() == 1000L
                && dto.getWatchTime() != null));
    }

    @Test
    void rejectsMalformedJsonBeforeCallingService() throws Exception {
        mvc.perform(post("/behavior/content").contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(service);
    }

    private String payload(String event) {
        return """
                {"contentId":"123","event":"%s","watchTime":"2026-10-17 10:00:00","watchDuration":1000}
                """.formatted(event);
    }
}
