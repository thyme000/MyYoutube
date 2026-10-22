package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.idempotent.IdempotentGuard;
import cn.diaojiahong.myyoutube.common.prompt.PromptTemplates;
import cn.diaojiahong.myyoutube.config.RecallConfig;
import cn.diaojiahong.myyoutube.llm.api.LlmPort;
import cn.diaojiahong.myyoutube.llm.api.QueryEmbeddingPort;
import cn.diaojiahong.myyoutube.search.vector.VectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotSearchWiringTest {
    @Configuration(proxyBeanMethods = false)
    @Import({HotSearchConfiguration.class, HotSearchService.class, HotSearchDailyArchive.class, HotSearchProperties.class, HotSearchStore.class,
            HotSearchModel.class, HotSearchFilter.class, RecallConfig.class, VectorProperties.class, PromptTemplates.class})
    static class Config {
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean LlmPort llm() { return mock(LlmPort.class); }
        @Bean QueryEmbeddingPort queries() { return mock(QueryEmbeddingPort.class); }
        @Bean IdempotentGuard guard() { return mock(IdempotentGuard.class); }
        @Bean HotSearchHistoryStore history() { return mock(HotSearchHistoryStore.class); }
    }

    @Test void bindsConfigurationAndChoosesItsOwnExecutorWithoutAffectingRecallPools() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withPropertyValues("my-youtube.search.hot.enabled=false", "my-youtube.search.hot.similarity-threshold=0.83",
                        "my-youtube.search.hot.partition-count=7",
                        "my-youtube.search.hot.excluded-location=classpath:hot-search/missing.txt")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(.83, context.getBean(HotSearchProperties.class).getSimilarityThreshold());
                    assertEquals(7, context.getBean(HotSearchProperties.class).getPartitionCount());
                    assertEquals("classpath:hot-search/missing.txt", context.getBean(HotSearchProperties.class).getExcludedLocation());
                    assertEquals("asdfghjkl", context.getBean(HotSearchFilter.class).eligible("asdfghjkl"));
                    assertTrue(context.getBean(HotSearchService.class).hot().isEmpty());
                    assertNotNull(context.getBean(HotSearchDailyArchive.class));
                    verifyNoInteractions(context.getBean(LlmPort.class), context.getBean(QueryEmbeddingPort.class));
                });
    }

    @Test void rejectsInvalidPartitionCounts() {
        var properties = new HotSearchProperties();
        assertEquals(16, properties.getPartitionCount());
        for (int count : new int[]{0, -1, properties.getPendingLimit() + 1}) {
            properties.setPartitionCount(count);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
        properties.setPartitionCount(3);
        assertDoesNotThrow(properties::validate);
    }
}
