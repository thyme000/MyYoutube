package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.video.config.ContentPipelineProperties;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.video.service.*;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentReviewState;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.mapper.*;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewPolicyTest {
    @Test void yamlDefaultsDisableVisionAndAllowIndependentReviewAndBatchOverrides() throws Exception {
        var environment = new org.springframework.mock.env.MockEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("video", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("common", new org.springframework.core.io.ClassPathResource("application-common.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        var binder = org.springframework.boot.context.properties.bind.Binder.get(environment);
        var defaults = binder.bind("my-youtube.content-pipeline", ContentPipelineProperties.class).get();
        assertFalse(defaults.isReviewVisionEnabled());
        assertEquals(4, new FrameProcessingSettings(environment).snapshot().reviewBatchSize());
        assertFalse(binder.bind("my-youtube.autotag", cn.diaojiahong.myyoutube.recommend.auto.TaggingProperties.class).get().isVisionEnabled());
        environment.setProperty("CONTENT_AUTO_REVIEW", "false");
        environment.setProperty("CONTENT_REVIEW_VISION_ENABLED", "true");
        environment.setProperty("CONTENT_REVIEW_FRAME_BATCH_SIZE", "3");
        environment.setProperty("TAG_VISION_ENABLED", "true");
        var overridden = binder.bind("my-youtube.content-pipeline", ContentPipelineProperties.class).get();
        assertFalse(overridden.isAutoReview());
        assertTrue(overridden.isReviewVisionEnabled());
        assertEquals(3, new FrameProcessingSettings(environment).snapshot().reviewBatchSize());
        assertTrue(binder.bind("my-youtube.autotag", cn.diaojiahong.myyoutube.recommend.auto.TaggingProperties.class).get().isVisionEnabled());
    }

    @Test void frameBatchSizeMustBeWithinAvailableFrames() {
        for (int size : new int[]{0, -1, 9}) {
            assertThrows(IllegalArgumentException.class, () -> new FrameProcessingSettings.Snapshot(5, 10, 8, 2, size, 80));
            assertThrows(IllegalArgumentException.class, () -> new PipelineContracts.ReviewSpec(
                    true, "test", "v1", "rules", 0.2, 0.9, 12000, true, size));
        }

        for (int size : new int[]{1, 4, 8}) {
            assertDoesNotThrow(() -> new FrameProcessingSettings.Snapshot(5, 10, 8, 2, size, 80));
        }
    }

    @Test void passRequiresBothScoresAndExplicitPass() {
        var policy = new ContentPipelineProperties();
        assertTrue(ReviewVerdict.parse("{\"decision\":\"PASS\",\"riskScore\":0.2,\"confidence\":0.9,\"reason\":\"安全\",\"evidence\":[]}", "test").passes(policy.getMaxRisk(), policy.getMinConfidence()));
        for (String json : List.of(
                "{\"decision\":\"PASS\",\"riskScore\":0.21,\"confidence\":0.99,\"reason\":\"风险\",\"evidence\":[]}",
                "{\"decision\":\"PASS\",\"riskScore\":0.1,\"confidence\":0.89,\"reason\":\"存疑\",\"evidence\":[]}",
                "{\"decision\":\"UNCERTAIN\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"存疑\",\"evidence\":[]}"))
            assertFalse(ReviewVerdict.parse(json, "test").passes(policy.getMaxRisk(), policy.getMinConfidence()));
    }
    @Test void malformedScoreDoesNotPass() {
        assertThrows(RuntimeException.class, () -> ReviewVerdict.parse("{\"decision\":\"PASS\",\"riskScore\":\"NaN\",\"confidence\":1,\"reason\":\"安全\",\"evidence\":[]}", "test"));
        assertThrows(RuntimeException.class, () -> ReviewVerdict.parse("{\"decision\":\"PASS\"}", "test"));
    }
    @Test void evidenceBatchesCoverEveryCharacterWithoutBreakingUnicode() {
        String text = "标题简介" + "字幕😀".repeat(10000) + "末尾证据";
        var chunks = EvidenceText.chunks(text, 1024);
        assertEquals(text, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 1024 && !Character.isHighSurrogate(c.charAt(c.length() - 1))));
    }
    @Test void lateMachineResultCannotTouchManualState() {
        var videos = mock(VideoMapper.class); var rows = mock(ContentUnderstandingMapper.class);
        var records = mock(ContentReviewRecordMapper.class); var events = mock(ContentPublicationEventMapper.class);
        when(rows.selectOne(any())).thenReturn(new ContentUnderstanding().setContentId(1L).setState(ContentReviewState.MANUAL_PENDING).setReviewAttempt(2));
        var service = new ReviewDecisionService(rows, records, events, mock(IContentService.class), new ContentPipelineProperties(), mock(PublicationEffectsService.class), new PromptTemplates(), mock(cn.diaojiahong.myyoutube.video.service.ContentObservation.class), new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        assertFalse(service.machine(1L, 1, ReviewVerdict.parse("{\"decision\":\"PASS\",\"riskScore\":0,\"confidence\":1,\"reason\":\"安全\",\"evidence\":[]}", "test")));
        verifyNoInteractions(videos, records, events);
    }
}
