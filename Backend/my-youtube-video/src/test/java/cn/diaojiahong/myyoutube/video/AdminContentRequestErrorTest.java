package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.content.service.ContentReadService;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.content.strategy.ContentTypeStrategyRegistry;
import cn.diaojiahong.myyoutube.video.controller.InternalAdminContentController;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.diaojiahong.myyoutube.video.service.ReviewDecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminContentRequestErrorTest {
    final IContentService content = mock(IContentService.class);

    final ReviewDecisionService decisions = mock(ReviewDecisionService.class);

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalAdminContentController(
            mock(ContentUnderstandingService.class), decisions, content,
            mock(ContentTypeStrategyRegistry.class), mock(ContentReadService.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void invalidFiltersAndReviewStatesFailBeforeAccessingContent() throws Exception {
        for (String input : new String[]{"{\"type\":\"invalid\"}", "{\"statusCode\":\"invalid\"}"}) {
            mvc.perform(post("/internal/admin/content/page").contentType("application/json").content(input))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        }
        mvc.perform(post("/internal/admin/content/mark-status").contentType("application/json")
                        .content("{\"statusCode\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(content, decisions);
    }
}
