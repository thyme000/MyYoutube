package cn.diaojiahong.myyoutube.common.prompt;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.Executor;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "my-youtube.prompts.nacos", name = "enabled", havingValue = "true")
public class NacosPromptSource {
    private static final String PREFIX = "my-youtube.prompts.nacos.";
    private final PromptTemplates templates;
    private final Environment environment;
    private ConfigService client;
    private String dataId;
    private String group;

    private final Listener listener = new Listener() {
        @Override
        public Executor getExecutor() {
            return null;
        }

        @Override
        public void receiveConfigInfo(String content) {
            reload(content);
        }
    };

    public NacosPromptSource(PromptTemplates templates, Environment environment) {
        this.templates = templates;
        this.environment = environment;
    }

    @PostConstruct
    public synchronized void initialize() throws Exception {
        var connection = new Properties();
        connection.setProperty("serverAddr", environment.getRequiredProperty(PREFIX + "server-addr"));
        for (String key : new String[]{"namespace", "username", "password"}) {
            connection.setProperty(key, environment.getProperty(PREFIX + key, ""));
        }

        dataId = environment.getProperty(PREFIX + "data-id", "model-prompts.yaml");
        group = environment.getProperty(PREFIX + "group", "PROMPTS_GROUP");
        client = NacosFactory.createConfigService(connection);
        try {
            templates.replace(client.getConfigAndSignListener(dataId, group, 5000, listener));
            log.info(
                    "Model prompts loaded dataId={} group={} version={}",
                    dataId,
                    group,
                    templates.snapshot().version());
        } catch (Exception e) {
            try {
                close();
            } catch (Exception closeError) {
                e.addSuppressed(closeError);
            }
            // 配置中心启用后必须有有效配置，不能悄悄退回打包默认值。
            throw new IllegalStateException("无法加载 Nacos 模型提示词: " + dataId, e);
        }
    }

    private synchronized void reload(String content) {
        try {
            templates.replace(content);
            log.info(
                    "Model prompts updated dataId={} group={} version={}",
                    dataId,
                    group,
                    templates.snapshot().version());
        } catch (RuntimeException e) {
            log.warn("Model prompts update rejected dataId={} group={} retainedVersion={} reason={}",
                    dataId, group, templates.snapshot().version(),
                    e instanceof IllegalArgumentException ? e.getMessage() : "YAML syntax or structure invalid");
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
