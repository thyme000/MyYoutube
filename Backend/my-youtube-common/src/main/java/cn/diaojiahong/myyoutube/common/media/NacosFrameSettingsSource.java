package cn.diaojiahong.myyoutube.common.media;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Properties;
import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "my-youtube.frame-processing.nacos", name = "enabled", havingValue = "true")
public class NacosFrameSettingsSource {
    private static final String PREFIX = "my-youtube.frame-processing.nacos.";
    private final FrameProcessingSettings settings;
    private final Environment environment;
    private ConfigService client;
    private String dataId;
    private String group;
    private final Listener listener = new Listener() {
        @Override public Executor getExecutor() { return null; }
        @Override public void receiveConfigInfo(String content) { reload(content); }
    };

    @PostConstruct
    public synchronized void initialize() throws Exception {
        var connection = new Properties();
        connection.setProperty("serverAddr", environment.getRequiredProperty(PREFIX + "server-addr"));
        for (String key : new String[]{"namespace", "username", "password"})
            connection.setProperty(key, environment.getProperty(PREFIX + key, ""));
        dataId = environment.getProperty(PREFIX + "data-id", "frame-processing.yaml");
        group = environment.getProperty(PREFIX + "group", "MEDIA_GROUP");
        client = NacosFactory.createConfigService(connection);
        try {
            settings.replace(client.getConfigAndSignListener(dataId, group, 5000, listener));
            log.info("Frame settings loaded dataId={} group={} settings={}", dataId, group, settings.snapshot());
        } catch (Exception e) {
            try { close(); } catch (Exception closeError) { e.addSuppressed(closeError); }
            throw new IllegalStateException("无法加载 Nacos 帧处理配置: " + dataId, e);
        }
    }

    private synchronized void reload(String content) {
        try {
            settings.replace(content);
            log.info("Frame settings updated dataId={} group={} settings={}", dataId, group, settings.snapshot());
        } catch (RuntimeException e) {
            log.warn("Frame settings update rejected dataId={} group={} retained={}", dataId, group, settings.snapshot());
        }
    }

    @PreDestroy
    public void close() throws Exception {
        if (client != null) {
            client.removeListener(dataId, group, listener);
            client.shutDown();
            client = null;
        }
    }
}
