package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.config.MybatisPlusConfig;
import cn.diaojiahong.myyoutube.search.mapper.HotSearchHistoryMapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "hot.mysql.url", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/hot_search_test.*")
@Timeout(20)
class HotSearchHistoryMysqlTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final long NINE = OffsetDateTime.parse("2026-10-20T09:10:00+08:00").toInstant().toEpochMilli();
    private String url;
    private JdbcTemplate jdbc;
    private HotSearchHistoryStore store;

    @BeforeEach void setup() throws Exception {
        url = System.getProperty("hot.mysql.url");
        assertTrue(URI.create(url.substring(5)).getPort() > 10000, "仅使用隔离测试 MySQL");
        var dataSource = new DriverManagerDataSource(url, "root", "");
        String schema = Files.readString(Path.of(System.getProperty("basedir", "."), "../../resources/schema.sql"));
        String ddl = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS hot_search_event"));
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(
                new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))), dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM hot_search_daily_group");
        jdbc.update("DELETE FROM hot_search_event");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new ClassPathResource("mapper/HotSearchHistoryMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(HotSearchHistoryMapper.class);
        store = new HotSearchHistoryStore(mapper, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void duplicateEventCannotChangeItsOriginalDateUserTextOrGroup(CapturedOutput output) {
        store.append(List.of(event("same", DAY, 1, NINE, "  C++  ", "group-a")));
        store.append(List.of(event("same", DAY.plusDays(1), 9, NINE + 1000, "C#", "group-b")));
        var row = jdbc.queryForMap("SELECT * FROM hot_search_event WHERE event_id = 'same'");
        assertEquals(1L, count("hot_search_event"));
        assertEquals(DAY, row.get("stat_date") instanceof java.sql.Date date ? date.toLocalDate() : row.get("stat_date"));
        assertEquals(1L, ((Number) row.get("user_id")).longValue());
        assertEquals(NINE, ((Number) row.get("happened_at")).longValue());
        assertEquals("  C++  ", row.get("raw_keyword"));
        assertEquals("group-a", row.get("group_id"));
        assertTrue(output.getAll().contains("Hot search history append completed batchSize=1 elapsedMs="));
        assertFalse(output.getAll().contains("  C++  "));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void repeatedSearchesRemainSeparateButHourlyContributionsAreDeduplicated(CapturedOutput output) {
        store.append(List.of(event("a", DAY, 1, NINE, "Java", "a"),
                event("b", DAY, 1, NINE + 300_000, "Java 教程", "a"),
                event("c", DAY, 1, NINE + 3_600_000, "Java", "a"),
                event("d", DAY, 2, NINE, "Java", "a")));
        store.rebuildDay(DAY);
        var row = jdbc.queryForMap("SELECT * FROM hot_search_daily_group");
        assertEquals(4L, ((Number) row.get("search_count")).longValue());
        assertEquals(2L, ((Number) row.get("user_count")).longValue());
        assertEquals(3L, ((Number) row.get("score")).longValue());
        assertTrue(output.getAll().contains("Hot search history append completed batchSize=4 elapsedMs="));
        assertTrue(output.getAll().contains("Hot search history rebuild started date=2026-10-20"));
        assertTrue(output.getAll().contains("Hot search history rebuild completed date=2026-10-20 groups=1 elapsedMs="));
    }

    @Test void allGroupsAreRankedWithStableTiesWithoutTopSevenOrUserThresholdFiltering() {
        store.append(IntStream.range(0, 9).mapToObj(i -> event("event-" + i, DAY, 1, NINE, "词" + i, "group-" + i)).toList());
        store.rebuildDay(DAY);
        assertEquals(9L, count("hot_search_daily_group"));
        assertEquals(IntStream.range(0, 9).mapToObj(i -> "group-" + i).toList(),
                jdbc.queryForList("SELECT group_id FROM hot_search_daily_group ORDER BY rank_no", String.class));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L),
                jdbc.queryForList("SELECT rank_no FROM hot_search_daily_group ORDER BY rank_no", Long.class));
    }

    @Test void rebuildReplacesAbsoluteCountsRemovesStaleGroupsAndLeavesOtherDaysUntouched() {
        store.append(List.of(event("a", DAY, 1, NINE, "Java", "a"),
                event("b", DAY, 2, NINE, "Java", "a"),
                event("c", DAY.plusDays(1), 3, NINE, "Java", "a")));
        store.rebuildDay(DAY);
        store.rebuildDay(DAY.plusDays(1));
        jdbc.update("DELETE FROM hot_search_event WHERE event_id = 'b'");
        store.rebuildDay(DAY);
        store.rebuildDay(DAY);
        assertEquals(1L, jdbc.queryForObject("SELECT search_count FROM hot_search_daily_group WHERE stat_date = ?", Long.class, DAY));
        assertEquals(1L, jdbc.queryForObject("SELECT search_count FROM hot_search_daily_group WHERE stat_date = ?", Long.class, DAY.plusDays(1)));
        jdbc.update("DELETE FROM hot_search_event WHERE stat_date = ?", DAY);
        store.rebuildDay(DAY);
        assertEquals(List.of(DAY.plusDays(1).toString()), jdbc.queryForList(
                "SELECT CAST(stat_date AS CHAR) FROM hot_search_daily_group", String.class));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedBatchDoesNotPersistPartialRows(CapturedOutput output) {
        assertThrows(DataAccessException.class, () -> store.append(List.of(
                event("good", DAY, 1, NINE, "Java", "a"), event("bad", DAY, 2, NINE, "private-search-6789", null))));
        assertEquals(0L, count("hot_search_event"));
        assertTrue(output.getAll().contains("Hot search history append failed batchSize=2 elapsedMs="));
        assertTrue(output.getAll().contains("errorType="));
        assertFalse(output.getAll().contains("Hot search history append completed"));
        assertFalse(output.getAll().contains("private-search-6789"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedRebuildRestoresThePreviousSummary(CapturedOutput output) {
        store.append(List.of(event("a", DAY, 1, NINE, "Java", "a")));
        store.rebuildDay(DAY);
        store.append(List.of(event("b", DAY, 2, NINE, "Java", "a")));
        jdbc.execute("CREATE TRIGGER hot_search_test_fail BEFORE INSERT ON hot_search_daily_group "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test rebuild failure'");
        try {
            assertThrows(DataAccessException.class, () -> store.rebuildDay(DAY));
            assertEquals(1L, jdbc.queryForObject("SELECT search_count FROM hot_search_daily_group", Long.class));
            String failedAttempt = output.getAll().substring(output.getAll().lastIndexOf("Hot search history rebuild started"));
            assertTrue(failedAttempt.contains("Hot search history rebuild failed date=2026-10-20 elapsedMs="));
            assertTrue(failedAttempt.contains("errorType="));
            assertFalse(failedAttempt.contains("Hot search history rebuild completed"));
        } finally {
            jdbc.execute("DROP TRIGGER hot_search_test_fail");
        }
    }

    @Test void rawKeywordGroupingPreservesCasePunctuationWhitespaceAndUnicode() {
        store.append(List.of(event("a", DAY, 1, NINE, "Java", "a"),
                event("b", DAY, 2, NINE, "java", "a"),
                event("c", DAY, 3, NINE, "Java ", "a"),
                event("d", DAY, 4, NINE, "C++", "a"),
                event("e", DAY, 5, NINE, "C#", "a"),
                event("f", DAY, 6, NINE, "猫🐈", "a")));
        assertEquals(6L, jdbc.queryForObject("SELECT COUNT(DISTINCT raw_keyword) FROM hot_search_event", Long.class));
        assertEquals("猫🐈", jdbc.queryForObject("SELECT raw_keyword FROM hot_search_event WHERE event_id = 'f'", String.class));
    }

    @Test void originalHourIsPreservedWhenArchiveDateIsCarriedToTheNextDay() {
        store.append(List.of(event("carried", DAY.plusDays(1), 1, NINE, "Java", "a")));
        assertEquals(Math.floorDiv(NINE, 3_600_000L), jdbc.queryForObject("SELECT event_hour FROM hot_search_event", Long.class));
        store.rebuildDay(DAY);
        assertEquals(0L, count("hot_search_daily_group"));
        store.rebuildDay(DAY.plusDays(1));
        assertEquals(1L, count("hot_search_daily_group"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({HotSearchHistoryStore.class, MybatisPlusConfig.class})
    static class Config {}

    @Test void applicationConfigurationCreatesMapperStorageUsingTheSearchDatabaseName() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
                        TransactionAutoConfiguration.class, MybatisPlusAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=root", "spring.datasource.password=")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("search_db", context.getEnvironment().getProperty("my-youtube.db-name"));
                    assertEquals(3, context.getBean(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize());
                    assertNotNull(context.getBean(HotSearchHistoryMapper.class));
                    context.getBean(HotSearchHistoryStore.class).append(List.of(event("wired", DAY, 1, NINE, "Java", "a")));
                    assertEquals(1L, count("hot_search_event"));
                });
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private HotSearchHistoryStore.GroupedEvent event(String id, LocalDate date, long user, long time, String raw, String group) {
        return new HotSearchHistoryStore.GroupedEvent(id, date, time, user, raw, raw.strip(), group, "Java 入门", "v1");
    }
}
