package cn.diaojiahong.myyoutube.recommend.auto;

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
    private final TaggingService service = new TaggingService(llm, mock(TagPoolHolder.class), guard, properties);

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

    @Test
    void enabledVisionFailureCannotBecomeEmptySuccess() {
        properties.setVisionEnabled(true);
        when(llm.chat(any())).thenThrow(new IllegalStateException("model unavailable"));
        assertThrows(IllegalStateException.class, () -> service.generate(input("")));
        verify(llm, times(1)).chat(any());
    }
}
