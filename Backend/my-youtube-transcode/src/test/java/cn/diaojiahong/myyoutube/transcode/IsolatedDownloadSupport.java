package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.chain.*;
import cn.diaojiahong.myyoutube.common.properties.S3Properties;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.transcode.chain.dynamic.*;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import cn.diaojiahong.myyoutube.transcode.mq.listener.TranscodeTaskListener;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.net.URI;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.mock;

final class IsolatedDownloadSupport implements AutoCloseable {
    final JdbcTemplate jdbc;
    final TranscodeTaskMapper rows;
    final RedissonClient redis;
    final S3Client s3;
    final S3Presigner signer;
    final S3StorageUtil storage;
    final ObjectMapper json = new ObjectMapper();
    final TranscodeTaskService tasks;
    static final String BUCKET = "content-integration";
    static final String NAMESERVER = "127.0.0.1:19876";

    IsolatedDownloadSupport() throws Exception {
        if (!Boolean.getBoolean("content.integration")) throw new IllegalStateException("Isolated integration opt-in required");
        var ds = database(); jdbc = new JdbcTemplate(ds);
        var factoryBean = new MybatisSqlSessionFactoryBean(); factoryBean.setDataSource(ds);
        var factory = factoryBean.getObject(); factory.getConfiguration().addMapper(TranscodeTaskMapper.class);
        rows = new SqlSessionTemplate(factory).getMapper(TranscodeTaskMapper.class);
        var config = new Config(); config.setNettyThreads(2); config.setThreads(2); config.setLockWatchdogTimeout(3000);
        config.useSingleServer().setAddress("redis://127.0.0.1:16379").setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        redis = Redisson.create(config);
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("content-it", "content-it-only"));
        var endpoint = URI.create("http://127.0.0.1:19000");
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).forcePathStyle(true).build();
        signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials)
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        var properties = new S3Properties(); properties.setEndpoint(endpoint.toString()); properties.setBucketName(BUCKET);
        storage = new S3StorageUtil(properties,s3,signer);
        tasks = new TranscodeTaskService(rows,json,redis,storage);
    }
    static DriverManagerDataSource database() {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/content_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", "root", "content-it-only");
        if (new JdbcTemplate(ds).queryForObject("SELECT COUNT(*) FROM isolated_test_environment WHERE id=1",Integer.class) != 1)
            throw new IllegalStateException("Missing isolated Docker environment marker");
        return ds;
    }
    void initialize() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("resources/schema.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Final schema not found");
        String schema = Files.readString(root.resolve("resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `transcode_task`");
        jdbc.execute(schema.substring(start, schema.indexOf(';',start)));
        jdbc.execute("DELETE FROM transcode_task");
        jdbc.execute("CREATE TABLE IF NOT EXISTS export_execution(task_key VARCHAR(64),worker VARCHAR(128))");
        jdbc.execute("DELETE FROM export_execution");
        if (s3.listBuckets().buckets().stream().noneMatch(b -> BUCKET.equals(b.name()))) s3.createBucket(b -> b.bucket(BUCKET));
    }
    TranscodeTaskListener listener(String worker) {
        var notifier = new TranscodeNotifier(mock(PushFanoutPublisher.class));
        var count = new TranscodeStep(25) {
            protected void doApply(TranscodeContext ctx) throws Exception {
                jdbc.update("INSERT INTO export_execution VALUES(?,?)",ctx.getTask().getTaskKey(),worker);
                Thread.sleep(500);
            }
        };
        var chains = new SyncChainStepRegistry(List.of(new PrepareStep(json),new DownloadMaterialStep(storage),count,
                new ComposeStep(),new UploadResultStep(storage),new CompleteStep(tasks,notifier)));
        return new TranscodeTaskListener(tasks,notifier,chains);
    }
    public void close() { redis.shutdown(); signer.close(); s3.close(); }
}
