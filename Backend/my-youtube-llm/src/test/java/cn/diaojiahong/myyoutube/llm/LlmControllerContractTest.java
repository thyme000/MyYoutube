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
    @Test void jsonContractCarriesSchemaBudgetFinishReasonAndUsage() throws Exception {
        var client = mock(LlmClient.class);
        when(client.chatDetailed(anyList(),isNull(),eq(0.1),eq(1500),anyMap()))
                .thenReturn(new LlmPort.ChatResponse("{}","model","stop",new LlmPort.Usage(10,2,12)));
        var controller = new InternalLlmController(new LlmProperties(),client,mock(EmbeddingOrchestrator.class));
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
