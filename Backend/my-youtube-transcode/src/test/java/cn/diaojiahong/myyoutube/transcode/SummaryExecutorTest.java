package cn.diaojiahong.myyoutube.transcode;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.task.SummaryTaskExecutor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SummaryExecutorTest {
    final ContentArtifactStore artifacts=mock(ContentArtifactStore.class);
    final LlmPort llm=mock(LlmPort.class);
    final ModelCallGuard guard=new ModelCallGuard(new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),new MockEnvironment());
    final PromptTemplates prompts = new PromptTemplates();
    final SummaryTaskExecutor executor=new SummaryTaskExecutor(artifacts,new PipelineProperties(),llm,guard,prompts);
    MediaTaskMessage input(String title) {
        when(artifacts.read("asr",ContentArtifacts.Transcript.class)).thenReturn(new ContentArtifacts.Transcript(List.of()));
        when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(List.of(new ContentArtifacts.Frame(0,20,"frame",""))));
        when(artifacts.write(eq(100L),eq("SUMMARY"),eq(1),any())).thenReturn("summary");
        return MediaTaskMessage.builder().pipelineId(100L).attempt(1).input(new Input(100L, 1L, 9L, title, "", "", null, new cn.diaojiahong.myyoutube.common.media.ContentMedia("video", new cn.diaojiahong.myyoutube.common.media.VideoMedia("key", 1000), null), 0))
                .dependencies(Map.of("ASR","{\"segmentsKey\":\"asr\"}","OCR","{\"artifactKey\":\"ocr\",\"durationMs\":1000}")).build();
    }
    @Test void allEmptySkipsModelAndPreservesLegalEmptySummary() {
        executor.execute(input("")); verifyNoInteractions(llm);
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("",List.of()));
    }
    @Test void metadataOnlyMakesOneCallWithoutChapterTimes() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"元数据摘要\",\"chapters\":[]}","test","stop",null));
        executor.execute(input("元数据标题")); verify(llm,times(1)).chat(any());
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("元数据摘要",List.of()));
    }
    @Test void uninformativeEvidenceAllowsModelToReturnEmptySummaryAndSchemaAllowsIt() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"\",\"chapters\":[]}","test","stop",null));
        executor.execute(input("美图"));
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("",List.of()));
        var request = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm).chat(request.capture());
        var jsonSchema = (Map<?, ?>) request.getValue().getResponseFormat().get("json_schema");
        var schema = (Map<?, ?>) jsonSchema.get("schema");
        var fields = (Map<?, ?>) schema.get("properties");
        assertEquals(0, ((Map<?, ?>) fields.get("summary")).get("minLength"));
    }
    @Test void emptySummaryCannotCarryChaptersAndWhitespaceOrNullIsNotEmptySummary() {
        var msg = input("美图");
        when(artifacts.read("asr",ContentArtifacts.Transcript.class)).thenReturn(
                new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(0,1000,"零散词句"))));
        for (String json : List.of("{\"summary\":\"\",\"chapters\":[{\"startMs\":0,\"title\":\"章节\"}]}",
                "{\"summary\":\"  \",\"chapters\":[]}", "{\"summary\":null,\"chapters\":[]}")) {
            when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(json,"test","stop",null));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(msg));
        }
    }
    @Test void emptyChunksAreSkippedDuringMergeAndAllEmptyChunksNeedNoMergeCall() {
        var msg = input("美图");
        var properties = new PipelineProperties(); properties.setChunkCharacters(1024);
        when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(
                List.of(new ContentArtifacts.Frame(0,0,"frame","碎片".repeat(1700)))));
        var empty = new LlmPort.ChatResponse("{\"summary\":\"\",\"chapters\":[]}","test","stop",null);
        var summary = new LlmPort.ChatResponse("{\"summary\":\"有效内容\",\"chapters\":[]}","test","stop",null);
        var chunked = new SummaryTaskExecutor(artifacts,properties,llm,guard,prompts);
        when(llm.chat(any())).thenReturn(empty);
        chunked.execute(msg);
        verify(llm,times(4)).chat(any());
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("",List.of()));

        clearInvocations(llm,artifacts);
        when(llm.chat(any())).thenReturn(empty,summary,empty,empty);
        chunked.execute(msg);
        verify(llm,times(4)).chat(any());
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("有效内容",List.of()));
    }
    @Test void mergeMayReturnEmptySummaryAndMustDiscardEarlierChapters() {
        var msg = input("标题");
        var properties = new PipelineProperties(); properties.setChunkCharacters(1024);
        when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(
                List.of(new ContentArtifacts.Frame(0,0,"frame","证据".repeat(1700)))));
        var chunk = new LlmPort.ChatResponse("{\"summary\":\"分段内容\",\"chapters\":[{\"startMs\":0,\"title\":\"章节\"}]}","test","stop",null);
        var empty = new LlmPort.ChatResponse("{\"summary\":\"\",\"chapters\":[]}","test","stop",null);
        when(llm.chat(any())).thenReturn(chunk,chunk,chunk,chunk,empty);
        new SummaryTaskExecutor(artifacts,properties,llm,guard,prompts).execute(msg);
        verify(llm,times(5)).chat(any());
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("",List.of()));
    }
    @Test void nativeSummaryKeepsOriginalAudioEvidenceWithoutTurningItsTimeIntoImageSeekPositions() {
        var message = input("标题");
        var media = new DynamicMedia("vertical",1080,1920,"cut",0,List.of(new DynamicMedia.Image("frame",0,3000,0.5,0.5)),
                new DynamicMedia.Audio(20L,"music","配乐",120000),3000);
        message.setInput(new Input(100L,1L,9L,"标题","","","frame",new ContentMedia("dynamic",null,media),0));
        when(artifacts.read("asr",ContentArtifacts.Transcript.class)).thenReturn(new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(110000,120000,"音频末尾证据"))));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"配乐与图片摘要\",\"chapters\":[]}","test","stop",null));
        executor.execute(message);
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("配乐与图片摘要",List.of()));
        var request=org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class); verify(llm).chat(request.capture());
        assertTrue(request.getValue().getMessages().get(1).getContent().contains("音频末尾证据"));
        assertEquals(1500, request.getValue().getMaxTokens());
        assertEquals("json_schema", request.getValue().getResponseFormat().get("type"));
        assertTrue(request.getValue().getMessages().get(0).getContent().contains("必须是空数组"));
    }
    @Test void modelFailureIsNotRetriedInsideExecutor() {
        when(llm.chat(any())).thenThrow(new IllegalStateException("down"));
        assertThrows(IllegalStateException.class,()->executor.execute(input("标题")));
        verify(llm,times(1)).chat(any());
    }
    @Test void truncationFailsEvenWithParseableJsonWithoutExecutorRetry() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"摘要\",\"chapters\":[]}", "test", "length", null));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(input("标题")));
        verify(llm, times(1)).chat(any());
    }
    @Test void forbiddenChapterAndWrongJsonTypesFail() {
        var msg = input("标题");
        for (String json : List.of("{\"summary\":1,\"chapters\":[]}",
                "{\"summary\":\"摘要\",\"chapters\":[{\"startMs\":0,\"title\":\"猜测\"}]}",
                "{\"summary\":\"摘要\",\"chapters\":[],\"extra\":true}",
                "{\"summary\":\"摘要\",\"chapters\":[]")) {
            when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse(json,"test","stop",null));
            assertThrows(RuntimeException.class, () -> executor.execute(msg));
        }
    }
    @Test void mergePacksMoreThanTwoSummariesAndKeepsAllEvidence() {
        var msg = input("标题");
        var properties = new PipelineProperties(); properties.setChunkCharacters(1024);
        var text = "证据".repeat(1700);
        when(artifacts.read("ocr", ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(List.of(new ContentArtifacts.Frame(0,0,"frame",text))));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"简短摘要\",\"chapters\":[]}","test","stop",null));
        new SummaryTaskExecutor(artifacts,properties,llm,guard,new PromptTemplates()).execute(msg);
        var requests = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(5)).chat(requests.capture());
        var values = requests.getAllValues();
        assertTrue(String.join("", values.subList(0,4).stream().map(r -> r.getMessages().get(1).getContent()).toList()).contains(text));
        assertEquals("简短摘要\n简短摘要\n简短摘要\n简短摘要", values.get(4).getMessages().get(1).getContent());
        assertTrue(values.get(4).getMessages().get(0).getContent().contains("必须是空数组"));
    }
    @Test void hotUpdateAppliesToNextTaskButNotRemainingChunksInCurrentTask() throws Exception {
        String defaults = new org.springframework.core.io.ClassPathResource("prompts/model-prompts.yaml")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var msg = input("标题");
        when(artifacts.read("ocr", ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(
                List.of(new ContentArtifacts.Frame(0, 0, "frame", "证据".repeat(7000)))));
        when(llm.chat(any())).thenAnswer(call -> {
            prompts.replace(defaults.replace("你是内容归纳器", "新版归纳器"));
            return new LlmPort.ChatResponse("{\"summary\":\"摘要\",\"chapters\":[]}", "test", "stop", null);
        });
        executor.execute(msg);
        var requests = org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class);
        verify(llm, times(3)).chat(requests.capture());
        assertTrue(requests.getAllValues().stream().allMatch(r -> r.getMessages().get(0).getContent().startsWith("你是内容归纳器")));
        clearInvocations(llm);
        executor.execute(input("下一任务"));
        verify(llm).chat(requests.capture());
        assertTrue(requests.getValue().getMessages().get(0).getContent().startsWith("新版归纳器"));
    }
    @Test void missingArtifactCannotMasqueradeAsEmpty() {
        var request=input(""); when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->executor.execute(request)); verifyNoInteractions(llm);
    }
}
