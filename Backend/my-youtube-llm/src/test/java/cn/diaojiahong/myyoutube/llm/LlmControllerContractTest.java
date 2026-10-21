package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.controller.InternalLlmController;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LlmControllerContractTest {
    @Test void embeddingMetadataIsReadOnlyAndDoesNotCallModels() throws Exception {
        var service = mock(cn.diaojiahong.myyoutube.llm.embedding.QueryEmbeddingService.class);
        when(service.info()).thenReturn(new cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort.Info("current", 4096));
        var controller = new cn.diaojiahong.myyoutube.llm.controller.QueryEmbeddingController(service,
                new LlmRequestMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        MockMvcBuilders.standaloneSetup(controller).build().perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/llm/embedding-info"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("current"))
                .andExpect(jsonPath("$.dims").value(4096));
        verify(service).info(); verifyNoMoreInteractions(service);
    }

    @Test void jsonContractCarriesSchemaBudgetFinishReasonAndUsage() throws Exception {
        var client = mock(LlmClient.class);
        when(client.chatDetailed(anyList(),isNull(),eq(0.1),eq(1500),anyMap()))
                .thenReturn(new LlmPort.ChatResponse("{}","model","stop",new LlmPort.Usage(10,2,12)));
        var controller = new InternalLlmController(new LlmProperties(),client,mock(EmbeddingOrchestrator.class),
                new LlmRequestMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/internal/llm/chat")
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"messages":[{"role":"user","content":"资料"}],"temperature":0.1,"maxTokens":1500,
                 "responseFormat":{"type":"json_schema","json_schema":{"name":"summary"}}}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.totalTokens").value(12));
        verify(client).chatDetailed(anyList(),isNull(),eq(0.1),eq(1500),argThat(f -> "json_schema".equals(f.get("type"))));
    }
}
