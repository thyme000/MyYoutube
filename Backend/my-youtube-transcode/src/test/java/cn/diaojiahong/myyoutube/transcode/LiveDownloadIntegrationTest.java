package cn.diaojiahong.myyoutube.transcode;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.transcode.api.dto.*;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.schedule.DownloadTaskDispatcher;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="content.integration",matches="true")
class LiveDownloadIntegrationTest {
    @TempDir Path work;
    IsolatedDownloadSupport env;
    RocketMQTemplate mq;
    final List<Process> workers = new ArrayList<>();
    final String run = "content_it_"+UUID.randomUUID().toString().replace("-","");

    @BeforeEach void setup() throws Exception {
        env = new IsolatedDownloadSupport(); env.initialize();
        var producer = new DefaultMQProducer(run); producer.setNamesrvAddr(IsolatedDownloadSupport.NAMESERVER);
        mq = new RocketMQTemplate(); mq.setProducer(producer);
        mq.setMessageConverter(new RocketMQMessageConverter().getMessageConverter()); mq.afterPropertiesSet();
        mq.syncSend(MqConstants.TOPIC_DYNAMIC_TRANSCODE,new TranscodeTaskMessage(null));
    }
    @AfterEach void cleanup() throws Exception {
        for(var worker:workers) { worker.destroyForcibly(); worker.waitFor(5,TimeUnit.SECONDS); }
        if(mq!=null) mq.destroy(); if(env!=null) env.close();
    }
    TranscodeSubmitDTO params() throws Exception {
        var image = new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);
        var file = work.resolve("image.png"); ImageIO.write(image,"png",file.toFile());
        String imageKey = "input/"+run+".png",audioKey="input/"+run+".wav";
        env.storage.uploadWithKey(Files.readAllBytes(file),imageKey);
        var audio = work.resolve("music.wav");
        var process = new ProcessBuilder("ffmpeg","-v","error","-y","-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","1",audio.toString())
                .redirectError(work.resolve("audio.log").toFile()).start();
        assertTrue(process.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
        env.storage.uploadWithKey(Files.readAllBytes(audio),audioKey);
        var dto = new TranscodeSubmitDTO(); dto.setUserId(9L); dto.setContentId(10L);
        dto.setCanvasW(160); dto.setCanvasH(90); dto.setTransition("fade"); dto.setFadeMs(500);
        dto.setAudioObjectKey(audioKey); dto.setAudioDurationMs(1000L); dto.setTotalDurationMs(5500L);
        var items = new ArrayList<TranscodeItemDTO>();
        for(long start:List.of(0L,2500L)) {
            var item = new TranscodeItemDTO(); item.setImageObjectKey(imageKey); item.setStartMs(start); item.setDurationMs(3000L);
            items.add(item);
        }
        dto.setItems(items); return dto;
    }
    Process worker(String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Dcontent.integration=true","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),IsolatedDownloadWorker.class.getName()));
        command.addAll(List.of(args));
        Path log = Path.of("/private/tmp/"+run+"-worker-"+workers.size()+".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        workers.add(process); return process;
    }
    void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(),"Condition timed out; worker logs: /private/tmp/"+run+"-worker-*.log");
    }
    void consumer(String suffix) throws Exception {
        String group=run+suffix; worker("consumer",group);
        await(() -> env.redis.getBucket(group).isExists(),30);
    }
    void due() { env.jdbc.update("UPDATE transcode_task SET next_dispatch_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)"); }
    int executions() { return env.jdbc.queryForObject("SELECT COUNT(*) FROM export_execution",Integer.class); }
    void completed(TranscodeTask row) throws Exception {
        await(() -> "DONE".equals(env.tasks.getById(row.getId()).getStatus()),45);
        var result = env.tasks.getById(row.getId()); assertTrue(env.storage.objectExists(result.getResultObjectKey()));
        var output = work.resolve("result.mp4"); Files.write(output,env.storage.readObject(result.getResultObjectKey()));
        var probe = new ProcessBuilder("ffprobe","-v","error","-show_entries","format=duration","-of","default=nw=1:nk=1",output.toString()).start();
        String duration = new String(probe.getInputStream().readAllBytes()).trim(); assertEquals(0,probe.waitFor());
        assertEquals(5.5,Double.parseDouble(duration),.08);
    }
    @Test void duplicateMqAcrossTwoJvmsComposesOnceAndExpiresMissingCache() throws Exception {
        var dto=params(); var requests=new ArrayList<TranscodeTask>();
        for(long id=1;id<=8;id++) { dto.setUserId(id); requests.add(env.tasks.submit(dto)); }
        consumer("a"); consumer("b");
        for(int i=0;i<16;i++) mq.syncSend(MqConstants.TOPIC_DYNAMIC_TRANSCODE,new TranscodeTaskMessage(requests.get(i%8).getId()));
        completed(requests.get(0));
        assertEquals(8,env.jdbc.queryForObject("SELECT COUNT(*) FROM transcode_task WHERE status='DONE'",Integer.class));
        assertEquals(1,executions());
        dto.setUserId(99L); var cached=env.tasks.submit(dto); assertEquals("DONE",cached.getStatus()); assertEquals(99L,cached.getUserId());
        env.storage.deleteObjectQuietly(cached.getResultObjectKey());
        var fresh=env.tasks.submit(dto); assertEquals("PENDING",fresh.getStatus());
        assertEquals(9,env.jdbc.queryForObject("SELECT COUNT(*) FROM transcode_task WHERE status='EXPIRED'",Integer.class));
        mq.syncSend(MqConstants.TOPIC_DYNAMIC_TRANSCODE,new TranscodeTaskMessage(fresh.getId()));
        completed(fresh); assertEquals(2,executions());
    }
    @Test void unannouncedPendingTaskIsRecoveredThroughRealMq() throws Exception {
        var row=env.tasks.submit(params()); consumer("recover");
        new DownloadTaskDispatcher(env.rows,env.tasks,mq).recover();
        completed(row); assertEquals(1,executions());
    }
    @Test void killedWorkerReleasesWatchdogLockAndProcessingTaskRecovers() throws Exception {
        var row=env.tasks.submit(params()); String marker=run+"crashed";
        var crashed=worker("crash",row.getId().toString(),marker);
        await(() -> env.redis.getBucket(marker).isExists(),30);
        assertEquals("PROCESSING",env.tasks.getById(row.getId()).getStatus());
        Thread.sleep(4500);
        assertTrue(env.tasks.artifactLock(row.getTaskKey()).isLocked(),"Watchdog must renew beyond its initial lease");
        var dispatcher=new DownloadTaskDispatcher(env.rows,env.tasks,mq); dispatcher.recover();
        assertEquals(0,executions());
        crashed.destroyForcibly(); assertTrue(crashed.waitFor(5,TimeUnit.SECONDS));
        await(() -> !env.tasks.artifactLock(row.getTaskKey()).isLocked(),10);
        consumer("replacement"); due(); dispatcher.recover();
        completed(row); assertEquals(1,executions());
    }
}
