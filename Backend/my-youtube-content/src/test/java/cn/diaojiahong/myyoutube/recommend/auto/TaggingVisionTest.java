package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaggingVisionTest {
    private static final String EMPTY_TAGS = "{\"category\":\"OTHER\",\"tags\":[]}";

    private final LlmPort llm = mock(LlmPort.class);

    private final TaggingProperties properties = new TaggingProperties();

    private final ModelCallGuard guard = new ModelCallGuard(new StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new MockEnvironment());

    private final PromptTemplates prompts = new PromptTemplates();

    private final TaggingService service = new TaggingService(
            llm,
            mock(TagPoolHolder.class),
            guard,
            properties,
            prompts);

    private TaggingInput input(String text) {
        return new TaggingInput(text, List.of(new TaggingInput.Image("http://local/image.jpg")));
    }

    @Test
    void disabledVisionKeepsTextButDoesNotSendImageParts() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(EMPTY_TAGS));
        service.generate(input("标题简介"));
        ArgumentCaptor<LlmPort.ChatRequest> request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        LlmPort.Message user = request.getValue().getMessages().get(1);
        assertEquals("标题简介", user.getContent());
        assertNull(user.getParts());
    }

    @Test
    void disabledVisionWithNoTextReturnsLegalEmptyTagsWithoutModelCall() {
        assertEquals(EMPTY_TAGS, service.generate(input(" ")));
        verifyNoInteractions(llm);
    }

    @Test
    void enabledVisionSendsImageOnlyInput() {
        properties.setVisionEnabled(true);
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(EMPTY_TAGS));
        service.generate(input(""));
        ArgumentCaptor<LlmPort.ChatRequest> request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        LlmPort.ContentPart part = request.getValue().getMessages().get(1).getParts().get(0);
        assertEquals("image_url", part.getType());
        assertEquals("http://local/image.jpg", part.getImageUrl().getUrl());
    }

    @Test
    void nextCallUsesPublishedTaggingTemplate() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(EMPTY_TAGS));
        service.generate(input("标题"));
        prompts.replace(templates("新版打标器 {{categories}} {{tagPool}}"));
        service.generate(input("标题"));
        ArgumentCaptor<LlmPort.ChatRequest> requests = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(2)).chat(requests.capture());
        String first = requests.getAllValues().get(0).getMessages().get(0).getContent();
        String second = requests.getAllValues().get(1).getMessages().get(0).getContent();
        assertTrue(first.contains("TECH(科技)"));
        assertFalse(first.contains("新版打标器"));
        assertTrue(second.contains("新版打标器"));
        assertTrue(second.contains("TECH(科技)"));
    }

    // PromptTemplates 要求六个模板键齐全，本用例只关注 tagging-system 的热更新。
    private static String templates(String taggingSystem) {
        return """
                templates:
                  summary-system: |-
                    {{summaryLimit}}{{chapterRule}}
                  summary-no-chapters: |-
                    无
                  tagging-system: |-
                    %s
                  cover-user: |-
                    {{candidateCount}}
                  review-system: |-
                    无
                  hot-search-group-system: |-
                    无
                """.formatted(taggingSystem);
    }

    @Test
    void enabledVisionFailureCannotBecomeEmptySuccess() {
        properties.setVisionEnabled(true);
        when(llm.chat(any())).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, () -> service.generate(input("")));
        verify(llm, times(1)).chat(any());
    }
}
