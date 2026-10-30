package cn.diaojiahong.myyoutube.video;

import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.media.ContentArtifactStore;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.video.api.AudioAdminPort;
import cn.diaojiahong.myyoutube.video.controller.InternalAdminAudioController;
import cn.diaojiahong.myyoutube.video.domain.enums.AudioReviewDecision;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.mapper.AudioReviewRecordMapper;
import cn.diaojiahong.myyoutube.video.service.AudioReviewService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioReviewDecisionContractTest {
    private final AudioReviewService reviews = mock(AudioReviewService.class);

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalAdminAudioController(
                    mock(AudioMapper.class), mock(AudioReviewRecordMapper.class), reviews,
                    mock(S3StorageUtil.class), mock(ContentArtifactStore.class), mock(UserPort.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @ParameterizedTest @EnumSource(AudioReviewDecision.class)
    void existingStringCodesReachTheServiceAsAnEnum(AudioReviewDecision decision) throws Exception {
        when(reviews.decide(9L, decision, "人工审核", 1L)).thenReturn(true);
        mvc.perform(post("/internal/admin/audios/9/decision").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(new AudioAdminPort.Decision(decision.name(), "人工审核", 1L))))
                .andExpect(status().isOk()).andExpect(content().string("true"));
        verify(reviews).decide(9L, decision, "人工审核", 1L);
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", "pass", "APPROVED", "UNCERTAIN", " PASS "})
    void invalidCodesAreRejectedBeforeEnteringTheService(String decision) throws Exception {
        mvc.perform(post("/internal/admin/audios/9/decision").contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(new AudioAdminPort.Decision(decision, "人工审核", 1L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(reviews);
    }
}
