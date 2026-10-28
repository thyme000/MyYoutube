package cn.diaojiahong.myyoutube.search.recall;

import cn.diaojiahong.myyoutube.common.concurrent.AsyncTimeoutProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class RecallRefillPropertiesTest {
    @Test void applicationConfigBindsBothRecallWaitsWithTwoSecondDefaults() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("search", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);

        AsyncTimeoutProperties properties = Binder.get(environment)
                .bind("my-youtube.async-timeouts", Bindable.of(AsyncTimeoutProperties.class)).get();
        properties.validate();
        assertEquals(2000, properties.getResources().get("keyword-recall").getTimeoutMillis());
        assertEquals(2000, properties.getResources().get("vector-recall").getTimeoutMillis());
    }

    @Test void bindsFixedDependencyTimeoutsAndRefillLimits() {
        MockEnvironment env = new MockEnvironment().withProperty("my-youtube.search.recall.es-timeout-millis", "1500")
                .withProperty("my-youtube.search.recall.embedding-timeout-millis", "8000")
                .withProperty("my-youtube.search.recall.refill-max-rounds", "3")
                .withProperty("my-youtube.search.recall.refill-max-candidates", "200")
                .withProperty("my-youtube.search.recall.refill-batch-size", "20");
        RecallProperties properties = Binder.get(env).bind("my-youtube.search.recall", Bindable.of(RecallProperties.class)).get();
        properties.validate();
        assertEquals(1500, properties.getEsTimeoutMillis());
        assertEquals(8000, properties.getEmbeddingTimeoutMillis());
        assertEquals(3, properties.getRefillMaxRounds());
        assertEquals(200, properties.getRefillMaxCandidates());
    }

    @Test void rejectsInvalidLimitsAndAllowsDisablingRefill() {
        List<Consumer<RecallProperties>> invalid = List.of(p -> p.setRefillMaxRounds(6), p -> p.setRefillMaxRounds(-1),
                p -> p.setRefillMaxCandidates(0), p -> p.setRefillMaxCandidates(1001),
                p -> p.setRefillBatchSize(0), p -> p.setRefillBatchSize(501),
                p -> p.setEsTimeoutMillis(0), p -> p.setEmbeddingTimeoutMillis(0),
                p -> p.setFirstPageSize(0), p -> p.setFirstPageSize(101));
        for (Consumer<RecallProperties> change : invalid) {
            RecallProperties properties = new RecallProperties(); change.accept(properties);
            assertThrows(IllegalStateException.class, properties::validate);
        }
        RecallProperties disabled = new RecallProperties(); disabled.setRefillMaxRounds(0);
        assertDoesNotThrow(disabled::validate);
    }

    @Test void bindsAndValidatesTheAspectPolicy() {
        MockEnvironment env = new MockEnvironment().withProperty("my-youtube.async-timeouts.resources.vector-recall.executor", "queryVectorExecutor")
                .withProperty("my-youtube.async-timeouts.resources.vector-recall.timeout-millis", "500");
        AsyncTimeoutProperties properties = Binder.get(env).bind("my-youtube.async-timeouts", Bindable.of(AsyncTimeoutProperties.class)).get();
        properties.validate();
        assertEquals(500, properties.getResources().get("vector-recall").getTimeoutMillis());
        properties.getResources().get("vector-recall").setTimeoutMillis(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
