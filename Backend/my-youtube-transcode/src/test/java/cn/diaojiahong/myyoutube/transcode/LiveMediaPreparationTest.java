package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.media.*;
import cn.diaojiahong.myyoutube.common.media.PreparationContracts.*;
import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.common.utils.*;
import cn.diaojiahong.myyoutube.transcode.api.OcrPort;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import cn.diaojiahong.myyoutube.transcode.mapper.*;
import cn.diaojiahong.myyoutube.transcode.preparation.*;
import cn.diaojiahong.myyoutube.transcode.service.MediaTaskService;
import cn.diaojiahong.myyoutube.transcode.task.PrepareTaskExecutor;
import cn.diaojiahong.myyoutube.video.api.MediaDraftPort;
import java.time.LocalDateTime;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="preparation.live", matches="true")
@SpringJUnitConfig(MediaTaskIntegrationTest.Config.class)
class LiveMediaPreparationTest {
    @Autowired DataSource dataSource;
    @Autowired MediaPreparationMapper preparations;
    @Autowired PreparationItemMapper items;
    @Autowired MediaTaskMapper taskRows;
    @Autowired MediaTaskService tasks;
    @Autowired TransactionTemplate transaction;
    @TempDir Path work;
    S3Client s3;
    S3Presigner signer;
    PreparationStorage storage;
    PreparationRegistry registry;
    PrepareTaskExecutor executor;
    S3StorageUtil urls;
    String bucket;

    @BeforeEach
    void setup() throws Exception {
        IsolatedDownloadSupport.database();
        var jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("media_preparation_item", "media_preparation", "media_task", "media_pipeline"))
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        new ResourceDatabasePopulator(new ClassPathResource("pipeline-schema.sql")).execute(dataSource);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                "content-it",
                "content-it-only"));
        URI endpoint = URI.create("http://127.0.0.1:19000");
        s3 = S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
        signer = S3Presigner.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(credentials)
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        bucket = "preparation-it-" + UUID.randomUUID();
        s3.createBucket(b -> b.bucket(bucket));
        var config = new S3Properties();
        config.setBucketName(bucket);
        config.setEndpoint(endpoint.toString());
        storage = new PreparationStorage(s3, config);
        var properties = new MediaPreparationProperties();
        registry = new PreparationRegistry(
                preparations,
                items,
                taskRows,
                tasks,
                transaction,
                properties,
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings());
        var writer = new PreparedImageWriter(properties);
        urls = new S3StorageUtil(config, s3, signer);
        executor = new PrepareTaskExecutor(
                registry,
                storage,
                writer,
                new PreparedVideoReader(properties, writer, new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings()),
                properties,
                urls,
                new cn.diaojiahong.myyoutube.common.media.FrameProcessingSettings(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @AfterEach
    void close() {
        try {
            if (s3 != null && bucket != null) {
                ListObjectVersionsResponse versions = s3.listObjectVersions(b -> b.bucket(bucket));
                for (ObjectVersion version : versions.versions()) s3.deleteObject(b -> b.bucket(bucket).key(version.key()).versionId(version.versionId()));
                for (DeleteMarkerEntry marker : versions.deleteMarkers()) s3.deleteObject(b -> b.bucket(bucket).key(marker.key()).versionId(marker.versionId()));
                s3.deleteBucket(b -> b.bucket(bucket));
            }
        } finally {
            if (signer != null) signer.close();
            if (s3 != null) s3.close();
        }
    }

    void put(String key, byte[] bytes) {
        s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }

    Path image() throws Exception {
        var image = new BufferedImage(2000, 1400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 2000, 1400);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 120));
        g.drawString("ALPHA 123", 100, 300);
        g.dispose();
        Path file = work.resolve("image.png");
        ImageIO.write(image, "png", file.toFile());
        image.flush();
        return file;
    }

    Manifest prepare(Request request) throws Exception {
        registry.start(request);
        MediaTask row = tasks.listByDraft(request.preparationId()).get(0);
        MediaTaskMessage task = tasks.claim(MediaTaskMessage.builder().taskId(row.getId()).phase("DRAFT").attempt(row.getAttempt()).build());
        String json = executor.execute(task);
        tasks.completeAndAdvance(task, json, null);
        PreparationContracts.Manifest manifest = registry.view(request.preparationId()).manifest();
        assertNotNull(PreparedMedia.freeze(request, manifest));
        return manifest;
    }

    @Test
    void conditionalCopiesRejectSourceChangesAndDestinationOverwrite() {
        put("upload/source", new byte[]{1, 2, 3});
        PreparationStorage.Source source = storage.inspect("upload/source");
        String frozen = "media-prepared/9/101/original";
        storage.freeze(source, frozen);
        assertArrayEquals(new byte[]{1, 2, 3}, s3.getObjectAsBytes(b -> b.bucket(bucket).key(frozen)).asByteArray());
        assertThrows(S3Exception.class, () -> storage.freeze(source, frozen));
        put("upload/source", new byte[]{4, 5, 6});
        assertThrows(S3Exception.class, () -> storage.freeze(source, "media-prepared/9/101/changed"));
        assertNull(storage.inspectIfPresent("media-prepared/9/101/changed"));
    }

    @Test
    void versionedDeletionRemovesTheActualVersionAndIsIdempotent() {
        s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        String key = "media-prepared/9/101/versioned";
        put(key, new byte[]{1, 2, 3});
        assertNotNull(storage.inspect(key).versionId());
        storage.delete(key);
        storage.delete(key);
        assertTrue(s3.listObjectVersions(b -> b.bucket(bucket)).versions().isEmpty());
        assertTrue(s3.listObjectVersions(b -> b.bucket(bucket)).deleteMarkers().isEmpty());
    }

    @Test
    void realImagePreparationAndVersionReusePreserveFrozenBytesAndNewTiming() throws Exception {
        put("upload/image", Files.readAllBytes(image()));
        var original = new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                List.of(new DynamicMedia.Image("upload/image", 0, 3000, .5, .5)), null, 3000);
        PreparationContracts.Manifest first = prepare(new Request(
                101L,
                1L,
                0,
                9L,
                new ContentMedia("dynamic", null, original),
                "upload/image"));
        var changed = new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                List.of(new DynamicMedia.Image("upload/image", 0, 5000, .2, .8)), null, 5000);
        PreparationContracts.Manifest second = prepare(new Request(
                102L,
                1L,
                1,
                9L,
                new ContentMedia("dynamic", null, changed),
                "upload/image"));
        assertEquals(first.items().get(0).objectKey(), second.items().get(0).objectKey());
        assertEquals(first.items().get(1).objectKey(), second.items().get(1).objectKey());
        assertEquals(5000, second.durationMs());
        assertEquals(3, s3.listObjectsV2(b -> b.bucket(bucket)).keyCount());
        BufferedImage output = ImageIO.read(s3.getObject(b -> b.bucket(bucket).key(first.items().get(1).objectKey())));
        assertTrue(output.getWidth() <= 1600 && (long) output.getWidth() * output.getHeight() <= 1600000);
        output.flush();
        Path bounded = work.resolve("bounded");
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.download(storage.inspect("upload/image"), bounded, 1));
        assertFalse(Files.exists(bounded));
    }

    @Test
    void realVideoPreparesDurationBasedSharedFramesWithoutReencoding() throws Exception {
        Path video = work.resolve("sample.mp4");
        Path log = work.resolve("ffmpeg.log");
        Process process = new ProcessBuilder(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=s=320x240:r=10",
                "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        byte[] bytes = Files.readAllBytes(video);
        put("upload/video", bytes);
        PreparationContracts.Manifest manifest = prepare(new Request(
                101L,
                1L,
                0,
                9L,
                new ContentMedia("video", new VideoMedia("upload/video", 0), null),
                null));
        assertEquals(2, manifest.items().size());
        assertFalse(manifest.hasAudio());
        assertArrayEquals(
                bytes,
                s3.getObjectAsBytes(b -> b.bucket(bucket).key(manifest.items().get(0).objectKey())).asByteArray());
        assertEquals(1, manifest.videoFrameCount());
        assertEquals(1, manifest.items().stream().filter(i -> "FRAME".equals(i.role())).count());
        for (PreparationContracts.Item item : manifest.items()) assertNotNull(storage.inspect(item.objectKey()));
    }

    @Test
    void sharedObjectsAreDeletedOnlyAfterTheLastRealReferenceIsReleased() throws Exception {
        put("upload/image", Files.readAllBytes(image()));
        var media = new ContentMedia("dynamic", null, new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                List.of(new DynamicMedia.Image("upload/image", 0, 3000, .5, .5)), null, 3000));
        PreparationContracts.Manifest first = prepare(new Request(101L, 1L, 0, 9L, media, "upload/image"));
        prepare(new Request(102L, 1L, 1, 9L, media, "upload/image"));
        MediaDraftPort video = org.mockito.Mockito.mock(cn.diaojiahong.myyoutube.video.api.MediaDraftPort.class);
        org.mockito.Mockito.when(video.preparationReference(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            cn.diaojiahong.myyoutube.video.api.MediaDraftPort.PreparationReference ref = call.getArgument(0);
            return ref.preparationId().equals(102L) ? "RETAINED" : "RELEASED";
        });
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        SqlSessionFactory session = factory.getObject();
        session.getConfiguration().addMapper(PipelineMapper.class);
        PipelineMapper pipelines = new org.mybatis.spring.SqlSessionTemplate(session).getMapper(PipelineMapper.class);
        var cleaner = new PreparationReclaimer(preparations, items, taskRows, pipelines,
                new PreparationLifecycle(preparations),
                video,
                storage,
                transaction,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        cleaner.reclaim();
        var jdbc = new JdbcTemplate(dataSource);
        LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("UPDATE media_preparation SET reclaim_after=?,retired_at=?", now.minusSeconds(1), now.minusDays(2));
        cleaner.reclaim();
        for (PreparationContracts.Item item : first.items()) assertNotNull(storage.inspect(item.objectKey()));
        org.mockito.Mockito.doReturn("RELEASED").when(video).preparationReference(org.mockito.ArgumentMatchers.any());
        jdbc.update("UPDATE media_preparation SET reclaim_after=?", now.minusSeconds(1));
        cleaner.reclaim();
        jdbc.update("UPDATE media_preparation SET reclaim_after=?,retired_at=?", now.minusSeconds(1), now.minusDays(2));
        cleaner.reclaim();
        for (PreparationContracts.Item item : first.items()) assertNull(storage.inspectIfPresent(item.objectKey()));
        assertNotNull(storage.inspect("upload/image"));
    }

    @Test
    @EnabledIfSystemProperty(named="content.models", matches="true")
    void realOcrPreservesKnownNormalSizeTextAfterFourKPreparation() throws Exception {
        var image = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 3840, 2160);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.PLAIN, 64));
        g.drawString("ALPHA 123", 160, 300);
        g.drawString("BETA 456", 160, 500);
        g.dispose();
        Path file = work.resolve("four-k.png");
        ImageIO.write(image, "png", file.toFile());
        image.flush();
        put("upload/four-k", Files.readAllBytes(file));
        var media = new ContentMedia("dynamic", null, new DynamicMedia("horizontal", 1920, 1080, "cut", 0,
                List.of(new DynamicMedia.Image("upload/four-k", 0, 3000, .5, .5)), null, 3000));
        PreparationContracts.Manifest manifest = prepare(new Request(101L, 1L, 0, 9L, media, "upload/four-k"));
        RestClient client = org.springframework.web.client.RestClient.builder()
                .baseUrl("http://127.0.0.1:18088")
                .build();
        for (String key : List.of("upload/four-k", manifest.items().get(1).objectKey())) {
            OcrPort.Response response = client.post().uri("/ocr/batch")
                    .body(new cn.diaojiahong.myyoutube.transcode.api.OcrPort.Request(List.of(urls.signGetUrl(
                            key,
                            600))))
                    .retrieve().body(cn.diaojiahong.myyoutube.transcode.api.OcrPort.Response.class);
            assertNotNull(response);
            assertEquals(1, response.results().size());
            String text = response.results().get(0).text().replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
            assertTrue(text.contains("ALPHA123") && text.contains("BETA456"));
        }
    }

}
