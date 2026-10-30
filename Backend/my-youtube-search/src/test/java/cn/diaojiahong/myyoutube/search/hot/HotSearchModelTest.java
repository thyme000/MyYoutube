package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HotSearchModelTest {
    final LlmPort llm = mock(LlmPort.class);

    final PromptTemplates prompts = new PromptTemplates();

    final HotSearchModel model = new HotSearchModel(
            llm,
            mock(QueryEmbeddingPort.class),
            new HotSearchProperties(),
            prompts);

    final QueryEmbeddingPort.Info info = new QueryEmbeddingPort.Info("v1", 2);

    @Test
    void acceptsPartialVectorsInResponseIndexOrder() {
        var response = new LlmPort.EmbeddingsResponse(List.of(List.of(1f, 0f), List.of(0f, 1f)), 2, "v1");
        response.setItems(List.of(
                new LlmPort.EmbeddingItem(1, null, "unavailable"),
                new LlmPort.EmbeddingItem(0, List.of(1f, 0f), null)));
        when(llm.embeddings(any(), any())).thenReturn(response);
        assertEquals(java.util.Map.of("a", List.of(1f, 0f)), model.vectors(List.of("a", "b"), info));
    }

    @Test
    void rejectsIncompatibleVersionsDimensionsAndMissingResponseStructures() {
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(List.of(1f, 0f)),
                2,
                "v2"));
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> model.vectors(List.of("a"), info));
        assertTrue(mismatch.getMessage().contains("expectedVersion=v1"));
        assertTrue(mismatch.getMessage().contains("actualVersion=v2"));
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(List.of(List.of(1f)), 1, "v1"));
        assertThrows(IllegalStateException.class, () -> model.vectors(List.of("a"), info));
        var response = new LlmPort.EmbeddingsResponse();
        response.setVersion("v1");
        response.setDims(2);
        when(llm.embeddings(any(), any())).thenReturn(response);
        assertThrows(IllegalStateException.class, () -> model.vectors(List.of("a"), info));
        when(llm.embeddings(any(), any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> model.vectors(List.of("a"), info));
    }

    @Test
    void discardsEveryResultForADuplicateIndexRegardlessOfOrderOrValidity() {
        var first = new LlmPort.EmbeddingItem(0, List.of(1f, 0f), null);
        var second = new LlmPort.EmbeddingItem(0, List.of(0f, 1f), null);
        var failed = new LlmPort.EmbeddingItem(0, null, "failed");
        var valid = new LlmPort.EmbeddingItem(2, List.of(1f, 0f), null);
        for (List<LlmPort.EmbeddingItem> items : List.of(List.of(first, second, valid), List.of(first, failed, valid),
                List.of(failed, first, valid), List.of(first, second, first, valid))) {
            var response = new LlmPort.EmbeddingsResponse(items, 2, "v1", null);
            when(llm.embeddings(any(), any())).thenReturn(response);
            assertEquals(Map.of("c", List.of(1f, 0f)), model.vectors(List.of("a", "b", "c"), info));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void keepsUniqueValidItemsAndLogsOnlySummaryContext(CapturedOutput output) {
        List<LlmPort.EmbeddingItem> items = Arrays.asList(null, new LlmPort.EmbeddingItem(-1, List.of(1f, 0f), null),
                new LlmPort.EmbeddingItem(9, List.of(1f, 0f), null),
                new LlmPort.EmbeddingItem(1, null, "private error text"),
                new LlmPort.EmbeddingItem(2, List.of(Float.NaN, 0f), null),
                new LlmPort.EmbeddingItem(3, List.of(1f), null),
                new LlmPort.EmbeddingItem(4, List.of(1f, 0f), null));
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(items, 2, "v1", "unavailable"));
        Map<String,List<Float>> result = model.vectors(List.of("private query", "b", "c", "d", "e"), info);
        assertEquals(Map.of("e", List.of(1f, 0f)), result);
        assertThrows(UnsupportedOperationException.class, () -> result.get("e").set(0, 0f));
        assertTrue(output.getOut().contains("batchSize=5 responseItems=7 accepted=1 invalidItems=6"));
        assertTrue(output.getOut().contains("version=v1 dims=2 unavailable=true"));
        assertFalse(output.getOut().contains("private query"));
        assertFalse(output.getOut().contains("private error text"));
    }

    @Test
    void handlesMissingItemsAndEmptyBatchesWithoutInventingMappings() {
        assertTrue(model.vectors(List.of(), info).isEmpty());
        verifyNoInteractions(llm);
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(new LlmPort.EmbeddingItem(1, List.of(0f, 1f), null)), 2, "v1", null));
        assertEquals(Map.of("b", List.of(0f, 1f)), model.vectors(List.of("a", "b"), info));
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(List.<List<Float>>of(), 2, "v1"));
        assertTrue(model.vectors(List.of("a"), info).isEmpty());
    }

    @Test
    void doesNotAcceptZeroOrNonfiniteVectors() {
        when(llm.embeddings(any(), any())).thenReturn(new LlmPort.EmbeddingsResponse(
                List.of(List.of(0f, 0f)),
                2,
                "v1"));
        assertTrue(model.vectors(List.of("a"), info).isEmpty());
        assertFalse(HotSearchGroup.validVector(List.of(Float.NaN, 1f), 2));
        assertFalse(HotSearchGroup.validVector(List.of(Float.POSITIVE_INFINITY, 1f), 2));
    }

    @Test
    void groupsAndNamesAllInputsInOneCallUsingTheirOriginalIndices() {
        var response = new LlmPort.ChatResponse("{\"groups\":[{\"name\":\"番茄炒蛋教程\",\"members\":[2,0]},{\"name\":\"猫粮选购\",\"members\":[1]}]}");
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);

        List<HotSearchModel.Grouping> groups = model.group(List.of("番茄炒蛋怎么做", "猫粮怎么选", "西红柿炒鸡蛋教程"));

        assertEquals(List.of(new HotSearchModel.Grouping("番茄炒蛋教程", List.of("西红柿炒鸡蛋教程", "番茄炒蛋怎么做")),
                new HotSearchModel.Grouping("猫粮选购", List.of("猫粮怎么选"))), groups);
        assertThrows(UnsupportedOperationException.class, () -> groups.clear());
        assertThrows(UnsupportedOperationException.class, () -> groups.get(0).keywords().clear());
        ArgumentCaptor<LlmPort.ChatRequest> requests = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(requests.capture(), any());
        JsonObject input = com.google.gson.JsonParser.parseString(requests.getValue().getMessages().get(1).getContent())
                .getAsJsonObject();
        assertEquals(3, input.getAsJsonArray("queries").size());
        assertEquals(2, input.getAsJsonArray("queries").get(2).getAsJsonObject().get("index").getAsInt());
        assertEquals("西红柿炒鸡蛋教程", input.getAsJsonArray("queries").get(2).getAsJsonObject().get("query").getAsString());
        assertTrue(requests.getValue().getMaxTokens() >= 2048);
        verify(llm, never()).embeddings(any(), any());
    }

    @Test
    void translatedChineseNamesFallBackToAChineseMember() {
        var response = new LlmPort.ChatResponse("{\"groups\":[{\"name\":\"tomato scrambled eggs recipe\",\"members\":[0,1]}]}");
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);
        assertEquals("番茄炒蛋怎么做", model.group(List.of("tomato eggs", "番茄炒蛋怎么做")).get(0).name());
        response.setContent("{\"groups\":[{\"name\":\".,.,\",\"members\":[0]}]}");
        assertEquals(".,.,", model.group(List.of("番茄炒蛋怎么做")).get(0).name());
    }

    @Test
    void duplicateIndicesKeepTheirFirstValidAssignmentAndEmptyGroupsAreSkipped() {
        var response = new LlmPort.ChatResponse("""
                {"groups":[{"name":"first","members":[0,0]},
                           {"name":"second","members":[0,1]},
                           {"name":"duplicate","members":[1]}]}
                """);
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);

        assertEquals(List.of(new HotSearchModel.Grouping("first", List.of("a")),
                new HotSearchModel.Grouping("second", List.of("b"))), model.group(List.of("a", "b")));
        verify(llm).chat(any(), any());
    }

    @Test
    void malformedGroupsAreSkippedWithoutReservingAnyOfTheirIndices() {
        var response = new LlmPort.ChatResponse();
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);
        for (String malformed : List.of(
                "{\"name\":\"bad\",\"members\":[0,2]}",
                "{\"name\":\"bad\",\"members\":[0,-1]}",
                "{\"name\":\"bad\",\"members\":[0,0.5]}",
                "{\"name\":\"bad\",\"members\":[0,4294967297]}",
                "{\"name\":\"bad\",\"members\":[0,\"1\"]}",
                "{\"name\":\"bad\",\"members\":[0,null]}",
                "{\"name\":\"bad\",\"members\":[]}",
                "{\"name\":123,\"members\":[0,1]}",
                "{\"name\":null,\"members\":[0,1]}",
                "{\"name\":\"bad\",\"members\":[0,1],\"extra\":1}",
                "{\"name\":\"bad\",\"members\":null}",
                "{\"name\":\"bad\"}", "null", "[]", "123")) {
            response.setContent("{\"groups\":[" + malformed + ",{\"name\":\"valid\",\"members\":[0,1]}]}");
            assertEquals(List.of(new HotSearchModel.Grouping("valid", List.of("a", "b"))),
                    model.group(List.of("a", "b")), malformed);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void partialResultsLogDiscardedGroupsDuplicatesAndUnassignedCountsWithoutRawContent(CapturedOutput output) {
        var response = new LlmPort.ChatResponse("""
                {"groups":[{"name":"private generated name","members":[0,"bad"]},
                           {"name":"valid","members":[0,0]},null]}
                """);
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);

        assertEquals(List.of(new HotSearchModel.Grouping("valid", List.of("private query"))),
                model.group(List.of("private query", "unassigned query")));

        assertTrue(output.getOut().contains("Hot search group discarded groupIndex=0 batchSize=2"));
        assertTrue(output.getOut().contains("Hot search group discarded groupIndex=2 batchSize=2"));
        assertTrue(output.getOut().contains("acceptedGroups=1 invalidGroups=2 duplicateMembers=1 unassignedMembers=1"));
        assertTrue(output.getOut().contains("promptVersion=" + prompts.snapshot().version()));
        assertFalse(output.getOut().contains("private query"));
        assertFalse(output.getOut().contains("private generated name"));
    }

    @Test
    void missingAssignmentsAndEntirelyInvalidGroupsReturnAvailableResults() {
        var response = new LlmPort.ChatResponse("{\"groups\":[{\"name\":\"valid\",\"members\":[0]}]}");
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);
        assertEquals(List.of(new HotSearchModel.Grouping("valid", List.of("a"))), model.group(List.of("a", "b")));
        response.setContent("{\"groups\":[null,{\"name\":123,\"members\":[0]}]}");
        assertTrue(model.group(List.of("a", "b")).isEmpty());
        response.setContent("{\"groups\":[]}");
        assertTrue(model.group(List.of("a", "b")).isEmpty());
    }

    @Test
    void invalidOuterResponseStillFailsTheCall() {
        var response = new LlmPort.ChatResponse();
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenReturn(response);
        for (String content : List.of("{\"groups\":[],\"extra\":1}", "{\"groups\":null}",
                "{\"groups\":{}}", "{\"name\":\"a\"}", "not json", "null", "[]")) {
            response.setContent(content);
            assertThrows(IllegalStateException.class, () -> model.group(List.of("a", "b")), content);
        }
    }

    @Test
    void requiresNormalCompletionAndSkipsEmptyInput() {
        assertTrue(model.group(List.of()).isEmpty());
        verifyNoInteractions(llm);
        var response = new LlmPort.ChatResponse("{\"groups\":[{\"name\":\"手机夜景拍摄\",\"members\":[0]}]}");
        response.setFinishReason("length");
        when(llm.chat(any(), any())).thenReturn(response);
        assertThrows(IllegalStateException.class, () -> model.group(List.of("晚上怎么拍照")));
        response.setFinishReason("stop");
        assertEquals("手机夜景拍摄", model.group(List.of("晚上怎么拍照")).get(0).name());
        when(llm.chat(any(), any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> model.group(List.of("a")));
    }

    @Test
    void readsExternalPromptSnapshotAndUsesUpdatedTextOnTheNextCall() throws Exception {
        String yaml = new ClassPathResource("prompts/model-prompts.yaml").getContentAsString(StandardCharsets.UTF_8);
        String originalPrompt = prompts.snapshot().render("hot-search-group-system");
        var response = new LlmPort.ChatResponse("{\"groups\":[{\"name\":\"手机夜景\",\"members\":[0]}]}");
        response.setFinishReason("stop");
        when(llm.chat(any(), any())).thenAnswer(call -> {
            prompts.replace(yaml.replace("将本批搜索词按语义分组", "请将本批搜索词按搜索意图分组"));
            return response;
        });
        assertEquals("手机夜景", model.group(List.of("手机夜景")).get(0).name());
        assertEquals("手机夜景", model.group(List.of("手机夜景")).get(0).name());
        ArgumentCaptor<LlmPort.ChatRequest> requests = ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(2)).chat(requests.capture(), any());
        assertEquals(originalPrompt, requests.getAllValues().get(0).getMessages().get(0).getContent());
        assertTrue(requests.getAllValues().get(1).getMessages().get(0).getContent().startsWith("请将本批搜索词按搜索意图分组"));
        assertSame(requests.getAllValues().get(0).getResponseFormat(), requests.getValue().getResponseFormat());
        assertThrows(
                UnsupportedOperationException.class,
                () -> requests.getValue().getResponseFormat().put("type", "text"));
    }

    @Test
    void groupingFailureIdentifiesTheExecutedPromptSnapshot() throws Exception {
        String before = prompts.snapshot().version();
        String yaml = new ClassPathResource("prompts/model-prompts.yaml").getContentAsString(StandardCharsets.UTF_8);
        var failure = new IllegalStateException("unavailable");
        when(llm.chat(any(), any())).thenAnswer(call -> {
            prompts.replace(yaml.replace("将本批搜索词按语义分组", "请将本批搜索词按搜索意图分组"));
            throw failure;
        });
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> model.group(List.of("查询")));
        assertTrue(error.getMessage().contains(before));
        assertNotEquals(before, prompts.snapshot().version());
        assertSame(failure, error.getCause());
        verify(llm).chat(any(), any());
    }
}
