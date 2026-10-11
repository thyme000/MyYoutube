package cn.diaojiahong.myyoutube.llm;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.resilience.ModelCallGuard;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.controller.InternalLlmController;
import cn.diaojiahong.myyoutube.llm.embedding.EmbeddingOrchestrator;
import cn.diaojiahong.myyoutube.recommend.auto.*;
import cn.diaojiahong.myyoutube.recommend.tag.TagPoolHolder;
import cn.diaojiahong.myyoutube.transcode.config.PipelineProperties;
import cn.diaojiahong.myyoutube.transcode.task.*;
import cn.diaojiahong.myyoutube.video.api.ContentPipelinePort;
import cn.diaojiahong.myyoutube.video.service.ReviewRules;
import feign.Feign;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.cloud.openfeign.support.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "content.llm", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveContentModelTest {
    @Configuration
    @EnableWebMvc
    static class Config {
        @Bean LlmProperties properties() {
            var p = new LlmProperties();
            p.setCloud(false);
            p.setBaseUrl("http://127.0.0.1:11434/v1");
            p.setModel("qwen3.5:9b");
            p.setTimeoutSeconds(120);
            p.getEmbedding().setBaseUrl(p.getBaseUrl());
            p.getEmbedding().setModel("qwen3-embedding:8b");
            return p;
        }
        @Bean LlmClient client(LlmProperties p) { return new LlmClient(p); }
        @Bean InternalLlmController controller(LlmProperties p, LlmClient client) {
            return new InternalLlmController(p, client, mock(EmbeddingOrchestrator.class));
        }
    }

    WebServer server;
    AnnotationConfigWebApplicationContext context;
    LlmPort llm;
    final ModelCallGuard guard = new ModelCallGuard(new StaticListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), new MockEnvironment());

    @BeforeAll void start() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.register(Config.class);
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        server = factory.getWebServer(sc -> {
            context.setServletContext(sc);
            var servlet = sc.addServlet("dispatcher", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
        });
        server.start();
        var converters = new HttpMessageConverters();
        llm = Feign.builder().retryer(feign.Retryer.NEVER_RETRY).contract(new SpringMvcContract())
                .encoder(new SpringEncoder(() -> converters)).decoder(new SpringDecoder(() -> converters))
                .options(new feign.Request.Options(10, java.util.concurrent.TimeUnit.SECONDS,
                        130, java.util.concurrent.TimeUnit.SECONDS, true))
                .target(LlmPort.class, "http://127.0.0.1:" + server.getPort() + "/internal/llm");
    }

    @AfterAll void stop() {
        if (server != null) server.stop();
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "dynamic"})
    void summaryTagAndTextReviewUseRealModelOverFeign(String type) {
        var values = new HashMap<String, Object>();
        var store = memoryArtifacts(values);
        values.put("asr", new ContentArtifacts.Transcript(List.of(
                new ContentArtifacts.Segment(0, 1000, "本期介绍太阳系，地球围绕太阳公转。"))));
        values.put("ocr", new ContentArtifacts.Ocr(List.of(new ContentArtifacts.Frame(0, 0, "image", "太阳系科普"))));
        var input = new PipelineContracts.Input(800L, 801L, 9L, "太阳系科普", "介绍地球与太阳的关系", "", "image", media(type), 0);
        var message = MediaTaskMessage.builder().pipelineId(800L).attempt(1).input(input)
                .dependencies(Map.of("ASR", "{\"segmentsKey\":\"asr\"}",
                        "OCR", "{\"artifactKey\":\"ocr\",\"durationMs\":5000}")).build();
        var summaryPointer = StructuredJson.object(new SummaryTaskExecutor(store, new PipelineProperties(), llm, guard).execute(message));
        String key = summaryPointer.get("artifactKey").getAsString();
        var summary = (ContentArtifacts.Summary) values.get(key);
        assertFalse(summary.summary().isBlank());
        if (type.equals("dynamic")) assertTrue(summary.chapters().isEmpty());

        var pool = mock(TagPoolHolder.class);
        when(pool.grouped()).thenReturn(Map.of());
        String tags = new TaggingService(llm, pool, guard, new TaggingProperties())
                .generate(TaggingInput.ofText(input.title() + "\n" + summary.summary()));
        TagResultMapper.validate(tags);
        assertEquals("KNOWLEDGE", StructuredJson.object(tags).get("category").getAsString());

        var pipeline = mock(ContentPipelinePort.class);
        when(pipeline.reviewSpec(800L, 1)).thenReturn(policy(false));
        message.setDependencies(Map.of("ASR", "{\"segmentsKey\":\"asr\"}",
                "OCR", "{\"artifactKey\":\"ocr\"}", "SUMMARY", "{\"artifactKey\":\"" + key + "\"}"));
        var storage = mock(S3StorageUtil.class);
        var verdict = ReviewVerdict.parse(new ReviewTaskExecutor(pipeline, store, storage, llm, guard).execute(message), "qwen3.5:9b");
        assertEquals("PASS", verdict.decision());
        assertTrue(verdict.passes(0.2, 0.9));
        assertTrue(verdict.evidence().get(1).getAsJsonObject().get("skipped").getAsBoolean());
        verifyNoInteractions(storage);
    }

    @Test void explicitVisionReviewUsesEveryNativeImage() throws Exception {
        var values = new HashMap<String, Object>();
        values.put("asr", new ContentArtifacts.Transcript(List.of()));
        values.put("ocr", new ContentArtifacts.Ocr(List.of(new ContentArtifacts.Frame(0, 0, "image", "ALPHA"))));
        values.put("summary", new ContentArtifacts.Summary("一张展示英文字母的教学图片", List.of()));
        var pipeline = mock(ContentPipelinePort.class);
        when(pipeline.reviewSpec(800L, 1)).thenReturn(policy(true));
        var storage = mock(S3StorageUtil.class);
        when(storage.signGetUrl("image", 600L)).thenReturn(image());
        var input = new PipelineContracts.Input(800L, 801L, 9L, "字母教学", "学习英文字母", "", "image", media("dynamic"), 0);
        var message = MediaTaskMessage.builder().pipelineId(800L).attempt(1).input(input)
                .dependencies(Map.of("ASR", "{\"segmentsKey\":\"asr\"}", "OCR", "{\"artifactKey\":\"ocr\"}",
                        "SUMMARY", "{\"artifactKey\":\"summary\"}")).build();
        var verdict = ReviewVerdict.parse(new ReviewTaskExecutor(pipeline, memoryArtifacts(values), storage, llm, guard).execute(message), "qwen3.5:9b");
        assertEquals(2, verdict.evidence().size());
        assertEquals("FRAMES", verdict.evidence().get(1).getAsJsonObject().get("source").getAsString());
        assertEquals("PASS", verdict.decision());
        verify(storage).signGetUrl("image", 600L);
    }

    private PipelineContracts.ReviewSpec policy(boolean vision) {
        return new PipelineContracts.ReviewSpec(true, "qwen3.5:9b", "v1", ReviewRules.TEXT, 0.2, 0.9, 12000, vision, 4);
    }

    private ContentMedia media(String type) {
        return type.equals("video") ? new ContentMedia(type, new VideoMedia("video", 5000), null)
                : new ContentMedia(type, null, new DynamicMedia("vertical", 640, 960, "cut", 0,
                List.of(new DynamicMedia.Image("image", 0, 3000, 0.5, 0.5)), null, 3000));
    }

    private ContentArtifactStore memoryArtifacts(Map<String, Object> values) {
        var store = mock(ContentArtifactStore.class);
        when(store.read(anyString(), any())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(store.write(anyLong(), anyString(), anyInt(), any())).thenAnswer(call -> {
            String key = UUID.randomUUID().toString(); values.put(key, call.getArgument(3)); return key;
        });
        return store;
    }

    private String image() throws Exception {
        var image = new BufferedImage(480, 240, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, 480, 240);
            g.setColor(Color.BLACK); g.setFont(new Font("SansSerif", Font.BOLD, 70)); g.drawString("ALPHA", 70, 145);
        } finally { g.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
