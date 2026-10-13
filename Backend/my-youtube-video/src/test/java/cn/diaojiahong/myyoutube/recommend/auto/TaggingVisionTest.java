package cn.diaojiahong.myyoutube.recommend.auto;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import org.junit.jupiter.api.Test;
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
    private final TaggingService service = new TaggingService(llm, mock(TagPoolHolder.class), guard, properties, prompts);

    private TaggingInput input(String text) {
        return new TaggingInput(text, List.of(new TaggingInput.Image("http://local/image.jpg")));
    }

    @Test
    void disabledVisionKeepsTextButDoesNotSendImageParts() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(EMPTY_TAGS));
        service.generate(input("标题简介"));
        var request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        var user = request.getValue().getMessages().get(1);
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
        var request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        var part = request.getValue().getMessages().get(1).getParts().get(0);
        assertEquals("image_url", part.getType());
        assertEquals("http://local/image.jpg", part.getImageUrl().getUrl());
    }

    @Test void nextCallUsesPublishedTaggingTemplate() throws Exception {
        String defaults = new org.springframework.core.io.ClassPathResource("prompts/model-prompts.yaml")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(EMPTY_TAGS));
        service.generate(input("标题"));
        prompts.replace(defaults.replace("你是内容平台的自动打标器", "新版打标器"));
        service.generate(input("标题"));
        var requests = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(2)).chat(requests.capture());
        assertTrue(requests.getAllValues().get(0).getMessages().get(0).getContent().startsWith("你是内容平台的自动打标器"));
        assertTrue(requests.getAllValues().get(1).getMessages().get(0).getContent().startsWith("新版打标器"));
        assertTrue(requests.getValue().getMessages().get(0).getContent().contains("TECH(科技)"));
    }

    @Test
    void enabledVisionFailureCannotBecomeEmptySuccess() {
        properties.setVisionEnabled(true);
        when(llm.chat(any())).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, () -> service.generate(input("")));
        verify(llm, times(1)).chat(any());
    }
}
