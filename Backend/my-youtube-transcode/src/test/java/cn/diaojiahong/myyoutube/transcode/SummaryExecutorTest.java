package cn.diaojiahong.myyoutube.transcode;
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
    final SummaryTaskExecutor executor=new SummaryTaskExecutor(artifacts,new PipelineProperties(),llm,guard);
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
    @Test void metadataOnlyMakesOneCallAndDropsUnsupportedChapterTimes() {
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"元数据摘要\",\"chapters\":[{\"startMs\":0,\"title\":\"猜测\"}]}"));
        executor.execute(input("元数据标题")); verify(llm,times(1)).chat(any());
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("元数据摘要",List.of()));
    }
    @Test void nativeSummaryKeepsOriginalAudioEvidenceWithoutTurningItsTimeIntoImageSeekPositions() {
        var message = input("标题");
        var media = new DynamicMedia("vertical",1080,1920,"cut",0,List.of(new DynamicMedia.Image("frame",0,3000,0.5,0.5)),
                new DynamicMedia.Audio(20L,"music","配乐",120000),3000);
        message.setInput(new Input(100L,1L,9L,"标题","","","frame",new ContentMedia("dynamic",null,media),0));
        when(artifacts.read("asr",ContentArtifacts.Transcript.class)).thenReturn(new ContentArtifacts.Transcript(List.of(new ContentArtifacts.Segment(110000,120000,"音频末尾证据"))));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"summary\":\"配乐与图片摘要\",\"chapters\":[{\"startMs\":110000,\"title\":\"配乐末尾\"}]}"));
        executor.execute(message);
        verify(artifacts).write(100L,"SUMMARY",1,new ContentArtifacts.Summary("配乐与图片摘要",List.of()));
        var request=org.mockito.ArgumentCaptor.forClass(LlmPort.ChatRequest.class); verify(llm).chat(request.capture());
        assertTrue(request.getValue().getMessages().get(1).getContent().contains("音频末尾证据"));
    }
    @Test void modelFailureIsNotRetriedInsideExecutor() {
        when(llm.chat(any())).thenThrow(new IllegalStateException("down"));
        assertThrows(IllegalStateException.class,()->executor.execute(input("标题")));
        verify(llm,times(1)).chat(any());
    }
    @Test void missingArtifactCannotMasqueradeAsEmpty() {
        var request=input(""); when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,()->executor.execute(request)); verifyNoInteractions(llm);
    }
}
