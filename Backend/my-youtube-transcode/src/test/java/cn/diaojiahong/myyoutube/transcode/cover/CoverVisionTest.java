package cn.diaojiahong.myyoutube.transcode.cover;

import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.ffmpeg.FFmpegUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoverVisionTest {
    private final LlmPort llm = mock(LlmPort.class);
    private final CoverProperties properties = new CoverProperties();
    private final ModelCallGuard guard = new ModelCallGuard(new StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new MockEnvironment());
    private final CoverVisionSelector selector = new CoverVisionSelector(llm, guard, properties);
    @TempDir Path directory;

    private FFmpegUtil.FrameScore frame(Path path, double score) {
        return new FFmpegUtil.FrameScore(path, 1, 1, 1, 1, 100, 20, score);
    }

    @Test
    void yamlDefaultsDisableCoverVisionAndAllowExplicitEnable() throws Exception {
        var environment = new MockEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("transcode", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
        assertFalse(binder.bind("my-youtube.cover", CoverProperties.class).get().isVisionEnabled());
        environment.setProperty("COVER_VISION_ENABLED", "true");
        assertTrue(binder.bind("my-youtube.cover", CoverProperties.class).get().isVisionEnabled());
    }

    @Test
    void disabledVisionUsesHighestQualityWithoutReadingImagesOrCallingModel() {
        var first = frame(directory.resolve("missing-first.jpg"), 0.2);
        var best = frame(directory.resolve("missing-best.jpg"), 0.9);
        var generator = new VideoCoverGenerator(mock(S3StorageUtil.class), selector);
        assertSame(best, ReflectionTestUtils.invokeMethod(generator, "selectBest",
                List.of(first, best), new FFmpegUtil.Options()));
        verifyNoInteractions(llm);
    }

    @Test
    void enabledVisionSendsImagesAndUsesModelSelection() throws Exception {
        properties.setVisionEnabled(true);
        properties.setVisionModel("vision-test");
        Path image = Files.write(directory.resolve("frame.jpg"), new byte[]{1, 2, 3});
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"index\":1}"));
        assertEquals(0, selector.pickBest(List.of(frame(image, 0.5))));
        var request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        assertEquals("vision-test", request.getValue().getModel());
        assertTrue(request.getValue().getMessages().get(0).getParts().get(1)
                .getImageUrl().getUrl().startsWith("data:image/jpeg;base64,"));
    }

    @Test
    void enabledVisionFailureStillFailsTaskForSchedulerRetry() throws Exception {
        properties.setVisionEnabled(true);
        Path image = Files.write(directory.resolve("frame.jpg"), new byte[]{1});
        when(llm.chat(any())).thenThrow(new IllegalStateException("model unavailable"));
        var generator = new VideoCoverGenerator(mock(S3StorageUtil.class), selector);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(generator,
                "selectBest", List.of(frame(image, 0.5)), new FFmpegUtil.Options()));
        verify(llm, times(1)).chat(any());
    }
}
