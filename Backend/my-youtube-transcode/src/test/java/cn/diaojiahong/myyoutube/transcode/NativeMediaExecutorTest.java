package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PipelineContracts.Input;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.transcode.api.*;
import cn.diaojiahong.myyoutube.transcode.cover.VideoCoverGenerator;
import cn.diaojiahong.myyoutube.transcode.task.*;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeMediaExecutorTest {
    final S3StorageUtil storage = mock(S3StorageUtil.class);
    final ContentArtifactStore artifacts = mock(ContentArtifactStore.class);
    final ModelCallGuard guard = new ModelCallGuard(new StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new MockEnvironment());

    MediaTaskMessage message(int count, DynamicMedia.Audio audio) {
        var images = IntStream.range(0,count).mapToObj(i -> new DynamicMedia.Image("image-"+i, i*3000L,3000,0.5,0.5)).toList();
        var media = new DynamicMedia("vertical",1080,1920,"cut",0,images,audio,count*3000L);
        return MediaTaskMessage.builder().pipelineId(100L).userId(9L).attempt(1)
                .input(new Input(100L,1L,9L,"标题","简介","","image-1",new ContentMedia("dynamic",null,media),1)).build();
    }
    @Test void selectedCoverDoesNotExtractVideoFramesOrChangeImageOrder() {
        var generator = mock(VideoCoverGenerator.class);
        var result = StructuredJson.object(new CoverTaskExecutor(generator).execute(message(3,null)));
        assertEquals("image-1", result.get("coverObjectKey").getAsString()); verifyNoInteractions(generator);
    }
    @Test void noMusicWritesExplicitEmptyAsrArtifactsWithoutCallingSpeechService() {
        var asr = mock(AsrPort.class);
        when(artifacts.write(eq(100L),eq("ASR"),eq(1),any())).thenReturn("empty-transcript");
        var result = StructuredJson.object(new AsrTaskExecutor(asr,guard,storage,artifacts).execute(message(3,null)));
        assertTrue(result.get("noAudio").getAsBoolean()); assertTrue(result.get("speechEmpty").getAsBoolean());
        assertEquals(0,result.get("segmentCount").getAsInt()); assertEquals(0,result.get("durationMs").getAsLong());
        verify(storage).uploadWithKey(eq("WEBVTT\n\n".getBytes(StandardCharsets.UTF_8)),eq(result.get("vttKey").getAsString()));
        verify(artifacts).write(100L,"ASR",1,new ContentArtifacts.Transcript(List.of())); verifyNoInteractions(asr);
    }
    @Test void longOriginalMusicIsRecognizedOnceWithoutImageDurationTruncationOrLooping() {
        var asr = mock(AsrPort.class);
        when(storage.signGetUrl("music",3600L)).thenReturn("http://local/music");
        when(asr.transcribe(any())).thenReturn(new AsrPort.Response(120000,1,false));
        var result = StructuredJson.object(new AsrTaskExecutor(asr,guard,storage,artifacts).execute(
                message(3,new DynamicMedia.Audio(20L,"music","配乐",120000))));
        var request=ArgumentCaptor.forClass(AsrPort.Request.class); verify(asr,times(1)).transcribe(request.capture());
        assertEquals("http://local/music",request.getValue().sourceUrl()); assertEquals(120000,result.get("durationMs").getAsLong());
        verifyNoInteractions(artifacts);
    }
    @Test void ocrUsesEveryOriginalImageInOrderAndPreservesEmptyText() throws Exception {
        var ocr=mock(OcrPort.class); var message=message(18,null);
        when(storage.signGetUrl(anyString(),eq(600L))).thenAnswer(call -> "http://local/"+call.getArgument(0));
        when(ocr.recognize(any())).thenAnswer(call -> new OcrPort.Response(((OcrPort.Request)call.getArgument(0))
                .urls().stream().map(url -> new OcrPort.Item(url,"",null)).toList()));
        when(artifacts.write(eq(100L),eq("OCR"),eq(1),any())).thenReturn("ocr");
        var result=StructuredJson.object(new OcrTaskExecutor(ocr,guard,storage,artifacts).execute(message));
        var written=ArgumentCaptor.forClass(Object.class); verify(artifacts).write(eq(100L),eq("OCR"),eq(1),written.capture());
        var frames=((ContentArtifacts.Ocr)written.getValue()).frames();
        assertEquals(18,frames.size()); assertEquals(54000,result.get("durationMs").getAsLong());
        for(int i=0;i<18;i++) {
            assertEquals(i,frames.get(i).imageIndex()); assertEquals(i*3000L,frames.get(i).timeMs());
            assertEquals("image-"+i,frames.get(i).objectKey()); assertEquals("",frames.get(i).text());
        }
    }
    @Test void missingFailedOrReorderedOcrImagesAreFailures() {
        var ocr=mock(OcrPort.class); var executor=new OcrTaskExecutor(ocr,guard,storage,artifacts);
        when(storage.signGetUrl(anyString(),eq(600L))).thenAnswer(call -> "http://local/"+call.getArgument(0));
        for(var response : List.of(
                new OcrPort.Response(List.of(new OcrPort.Item("http://local/image-0","",null))),
                new OcrPort.Response(List.of(new OcrPort.Item("http://local/image-0",null,"failed"),new OcrPort.Item("http://local/image-1","",null))),
                new OcrPort.Response(List.of(new OcrPort.Item("http://local/image-1","",null),new OcrPort.Item("http://local/image-0","",null))))) {
            when(ocr.recognize(any())).thenReturn(response);
            assertThrows(IllegalStateException.class, () -> executor.execute(message(2,null)));
        }
        verifyNoInteractions(artifacts);
    }
    @Test void visionReviewCoversAllEighteenImagesIncludingPartialBatch() {
        var port=mock(ContentPipelinePort.class); var llm=mock(LlmPort.class); var message=message(18,null);
        when(port.reviewSpec(100L,1)).thenReturn(new PipelineContracts.ReviewSpec(true,"test","v1","rules",0.2,0.9,12000,true,4));
        when(artifacts.read("asr",ContentArtifacts.Transcript.class)).thenReturn(new ContentArtifacts.Transcript(List.of()));
        when(artifacts.read("summary",ContentArtifacts.Summary.class)).thenReturn(new ContentArtifacts.Summary("摘要",List.of()));
        var frames=message.getInput().media().dynamic().items();
        when(artifacts.read("ocr",ContentArtifacts.Ocr.class)).thenReturn(new ContentArtifacts.Ocr(IntStream.range(0,18)
                .mapToObj(i -> new ContentArtifacts.Frame(i,frames.get(i).startMs(),frames.get(i).imageObjectKey(),"")).toList()));
        when(storage.signGetUrl(anyString(),eq(600L))).thenAnswer(call -> "http://local/"+call.getArgument(0));
        when(llm.chat(any())).thenReturn(new LlmPort.ChatResponse("{\"decision\":\"PASS\",\"riskScore\":0.1,\"confidence\":0.99,\"reason\":\"安全\",\"evidence\":[]}","test"));
        message.setDependencies(Map.of("ASR","{\"segmentsKey\":\"asr\"}","OCR","{\"artifactKey\":\"ocr\"}","SUMMARY","{\"artifactKey\":\"summary\"}"));
        var verdict=ReviewVerdict.parse(new ReviewTaskExecutor(port,artifacts,storage,llm,guard).execute(message),"test");
        assertTrue(verdict.passes(0.2,0.9));
        var requests=ArgumentCaptor.forClass(LlmPort.ChatRequest.class);verify(llm,times(6)).chat(requests.capture());
        var urls=requests.getAllValues().stream().flatMap(req -> req.getMessages().get(1).getParts().stream())
                .filter(p -> "image_url".equals(p.getType())).map(p -> p.getImageUrl().getUrl()).toList();
        assertEquals(IntStream.range(0,18).mapToObj(i -> "http://local/image-"+i).toList(),urls);
    }
}
