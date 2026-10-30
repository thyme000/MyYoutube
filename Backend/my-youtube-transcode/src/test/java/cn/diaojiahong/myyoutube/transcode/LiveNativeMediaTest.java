package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.transcode.api.*;
import cn.diaojiahong.myyoutube.transcode.task.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="content.models",matches="true")
class LiveNativeMediaTest {
    @TempDir Path work;
    IsolatedDownloadSupport env;
    ContentArtifactStore artifacts;

    final String sample=System.getProperty("content.asr.sample","");

    final ModelCallGuard guard=new ModelCallGuard(
            new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
            new MockEnvironment());

    final RestClient asr=RestClient.create("http://127.0.0.1:18089"),ocr=RestClient.create("http://127.0.0.1:18088");

    final AsrPort speech=request -> asr.post().uri("/asr").body(request).retrieve().body(AsrPort.Response.class);

    final OcrPort images=request -> ocr.post().uri("/ocr/batch").body(request).retrieve().body(OcrPort.Response.class);
    List<DynamicMedia.Image> items;

    @BeforeEach
    void setup() throws Exception {
        assertTrue(
                Files.isRegularFile(Path.of(sample)),
                "Set content.asr.sample to a local speech WAV of at least ten seconds");
        env=new IsolatedDownloadSupport();
        env.initialize();
        artifacts=new ContentArtifactStore(env.storage);
        assertTrue(asr.get()
                .uri("/health")
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class)
                .get("ready")
                .asBoolean());
        assertTrue(ocr.get()
                .uri("/health")
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class)
                .get("ready")
                .asBoolean());
        var keys=new java.util.ArrayList<String>();
        for(String text:List.of("ALPHA 123","BETA 456")) {
            var image=new BufferedImage(512,128,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0,0,512,128);
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial",Font.BOLD,48));
            g.drawString(text,25,80);
            g.dispose();
            Path file=work.resolve(text+".png");
            ImageIO.write(image,"png",file.toFile());
            keys.add(env.storage.uploadForObjectKey(Files.readAllBytes(file),"text.png",9L,"picture"));
        }
        items=List.of(
                new DynamicMedia.Image(keys.get(0),0,3000,.5,.5),
                new DynamicMedia.Image(keys.get(1),2200,4200,.5,.5));
    }

    @AfterEach
    void close() {
        if(env!=null) env.close();
    }

    MediaTaskMessage dynamic(DynamicMedia.Audio audio) {
        var media=new ContentMedia("dynamic",null,new DynamicMedia("horizontal",1920,1080,"fade",800,items,audio,6400));
        return MediaTaskMessage.builder().pipelineId(777L).userId(9L).attempt(1)
                .input(new PipelineContracts.Input(
                        777L,
                        77L,
                        9L,
                        "测试图集",
                        "完整原始配乐",
                        "",
                        items.get(1).imageObjectKey(),
                        media,
                        System.currentTimeMillis()))
                .build();
    }

    void run(String... args) throws Exception {
        Path log=work.resolve("ffmpeg.log");
        Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS));
            assertEquals(0,process.exitValue(),Files.readString(log));
        } finally {
            if(process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void originalSoundtrackAndEveryOrderedImageProduceRealArtifacts() throws Exception {
        Path audio=work.resolve("speech.wav");
        run("ffmpeg","-v","error","-y","-i",sample,"-t","10",audio.toString());
        String key=env.storage.uploadForObjectKey(Files.readAllBytes(audio),"speech.wav",9L,"audio");
        MediaTaskMessage msg=dynamic(new DynamicMedia.Audio(20L,key,"原始配乐",10000));
        JsonObject asrResult=StructuredJson.object(new AsrTaskExecutor(speech,guard,env.storage,artifacts).execute(msg));
        assertEquals(10000,asrResult.get("durationMs").getAsLong());
        ContentArtifacts.Transcript transcript=artifacts.read(
                asrResult.get("segmentsKey").getAsString(),
                ContentArtifacts.Transcript.class);
        assertFalse(transcript.segments().isEmpty());
        assertTrue(new String(env.storage.readObject(asrResult.get("vttKey").getAsString())).startsWith("WEBVTT"));
        JsonObject result=StructuredJson.object(new OcrTaskExecutor(images,guard,env.storage,artifacts,new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings()).execute(msg));
        List<ContentArtifacts.Frame> frames=artifacts.read(
                result.get("artifactKey").getAsString(),
                ContentArtifacts.Ocr.class)
                .frames();
        assertEquals(2,frames.size());
        assertTrue(frames.get(0).text().contains("ALPHA"));
        assertTrue(frames.get(1).text().contains("BETA"));
        for(int i=0;i<2;i++) {
            assertEquals(i,frames.get(i).imageIndex());
            assertEquals(items.get(i).startMs(),frames.get(i).timeMs());
            assertEquals(items.get(i).imageObjectKey(),frames.get(i).objectKey());
        }
        assertEquals(6400,result.get("durationMs").getAsLong());
    }

    @Test
    void noMusicCreatesEmptyAsrButMissingImageFailsWholeOcr() {
        MediaTaskMessage msg=dynamic(null);
        JsonObject result=StructuredJson.object(new AsrTaskExecutor(speech,guard,env.storage,artifacts).execute(msg));
        assertTrue(result.get("noAudio").getAsBoolean());
        assertTrue(artifacts.read(result.get("segmentsKey").getAsString(),ContentArtifacts.Transcript.class)
                .segments()
                .isEmpty());
        env.storage.deleteObjectQuietly(items.get(1).imageObjectKey());
        assertThrows(
                IllegalStateException.class,
                () -> new OcrTaskExecutor(
                        images,
                        guard,
                        env.storage,
                        artifacts,
                        new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings())
                        .execute(msg));
    }

    @Test
    void videoKeepsRealSpeechAndEightFrameOcrPath() throws Exception {
        Path audio=work.resolve("audio.wav");
        Path video=work.resolve("video.mp4");
        run("ffmpeg","-v","error","-y","-i",sample,"-t","10",audio.toString());
        run("ffmpeg","-v","error","-y","-loop","1","-i",work.resolve("ALPHA 123.png").toString(),"-i",audio.toString(),
                "-t","10","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac",video.toString());
        String key=env.storage.uploadForObjectKey(Files.readAllBytes(video),"video.mp4",9L,"video");
        MediaTaskMessage msg=MediaTaskMessage.builder().pipelineId(778L).userId(9L).attempt(1).objectKey(key)
                .input(new PipelineContracts.Input(778L,78L,9L,"视频回归","","",null,
                        new ContentMedia("video",new VideoMedia(key,10000),null),System.currentTimeMillis())).build();
        JsonObject transcript=StructuredJson.object(new AsrTaskExecutor(speech,guard,env.storage,artifacts).execute(msg));
        assertFalse(artifacts.read(transcript.get("segmentsKey").getAsString(),ContentArtifacts.Transcript.class)
                .segments()
                .isEmpty());
        JsonObject result=StructuredJson.object(new OcrTaskExecutor(images,guard,env.storage,artifacts,new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings()).execute(msg));
        List<ContentArtifacts.Frame> frames=artifacts.read(
                result.get("artifactKey").getAsString(),
                ContentArtifacts.Ocr.class)
                .frames();
        assertEquals(8,frames.size());
        assertTrue(frames.stream().allMatch(frame -> frame.text().contains("ALPHA")));
        for(int i=1;i<8;i++) assertTrue(frames.get(i).timeMs()>frames.get(i-1).timeMs());
    }
}
