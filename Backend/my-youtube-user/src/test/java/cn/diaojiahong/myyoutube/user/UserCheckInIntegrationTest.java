package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.filter.UserIdHeaderFilter;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.schedule.ScheduleRoundLock;
import cn.diaojiahong.myyoutube.common.security.InternalApiCredentials;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Month;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Monthly;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Totals;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Yearly;
import cn.diaojiahong.myyoutube.user.cache.UserCheckInBitmapCache;
import cn.diaojiahong.myyoutube.user.controller.InternalUserCheckInStatisticsController;
import cn.diaojiahong.myyoutube.user.controller.UserCheckInController;
import cn.diaojiahong.myyoutube.user.domain.po.UserCheckInMonthSnapshot;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInHeatmapVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInStatusVO;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMonthSnapshotMapper;
import cn.diaojiahong.myyoutube.user.schedule.UserCheckInStatisticsJob;
import cn.diaojiahong.myyoutube.user.service.UserCheckInService;
import cn.diaojiahong.myyoutube.user.service.UserCheckInStatisticsService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserCheckInIntegrationTest {
    private static final String INTERNAL_TOKEN = "check-in-test-token";
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private UserCheckInMapper checkIns;
    private UserCheckInMonthSnapshotMapper snapshots;
    private UserCheckInService service;
    private UserCheckInStatisticsService statistics;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:check-in-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        jdbc.execute(tableDdl(schema, "user_check_in"));
        jdbc.execute(tableDdl(schema, "user_check_in_month_snapshot"));

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setMapperLocations(new ClassPathResource("mapper/UserCheckInMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserCheckInMonthSnapshotMapper.class);
        var session = new SqlSessionTemplate(factory);
        checkIns = session.getMapper(UserCheckInMapper.class);
        snapshots = session.getMapper(UserCheckInMonthSnapshotMapper.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        service = new UserCheckInService(checkIns, new UserCheckInBitmapCache(redis));
        statistics = new UserCheckInStatisticsService(checkIns, snapshots);

        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new UserCheckInController(service),
                        new InternalUserCheckInStatisticsController(statistics, new InternalApiCredentials(INTERNAL_TOKEN)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private static String tableDdl(String schema, String table) {
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `%s`".formatted(table));
        assertTrue(start >= 0, "缺少建表定义: " + table);
        int end = schema.indexOf(';', start);
        assertTrue(end > start, "建表定义缺少结束分号: " + table);
        return schema.substring(start, end);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void checkInSurvivesRedisFailureAndStatusIgnoresStaleBitmap() throws Exception {
        LocalDateTime first = LocalDateTime.of(2024, 2, 29, 9, 0);
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(first);
            mvc.perform(post("/user/check-ins")
                            .param("userId", "2").param("date", "2024-02-28")
                            .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.date").exists())
                    .andExpect(jsonPath("$.data.checkedInAt").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.data.checkedIn").doesNotHaveJsonPath());

            time.when(BusinessTime::now).thenReturn(first.plusHours(1));
            mvc.perform(post("/user/check-ins")
                            .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(40901))
                    .andExpect(jsonPath("$.message").value("今天已签到"));

            doReturn(new byte[46]).when(redis).execute(any(RedisCallback.class));
            assertEquals(0, service.heatmap(1L, 2024).daysInYear());
            mvc.perform(get("/user/check-ins/status")
                            .header(UserIdHeaderFilter.USER_ID_HEADER, "1")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checkedIn").value(true))
                    .andExpect(jsonPath("$.data.daysInYear").value(1))
                    .andExpect(jsonPath("$.data.checkedInAt").doesNotHaveJsonPath());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_check_in", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT user_id FROM user_check_in", Long.class));
        assertEquals(first.toLocalDate(),
                jdbc.queryForObject("SELECT check_in_date FROM user_check_in", LocalDate.class));
    }

    @Test
    void statusCountsTheCurrentYearAndHeatmapCountsTheRequestedYear() {
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2024, 12, 31, 23, 59));
            assertEquals(new CheckInStatusVO(LocalDate.of(2024, 12, 31), false, 0), service.status(1L));
            service.checkIn(1L);
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 1, 0, 1));
            service.checkIn(1L);
            service.checkIn(2L);
            assertEquals(new CheckInStatusVO(LocalDate.of(2025, 1, 1), true, 1),
                    service.status(1L));

            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 2, 8, 0));
            assertEquals(new CheckInStatusVO(LocalDate.of(2025, 1, 2), false, 1),
                    service.status(1L));
            assertEquals(new CheckInHeatmapVO(2024, List.of(LocalDate.of(2024, 12, 31)), 1),
                    service.heatmap(1L, 2024));
            assertEquals(new CheckInHeatmapVO(2025, List.of(LocalDate.of(2025, 1, 1)), 1),
                    service.heatmap(1L, 2025));
        }
    }

    @Test
    void statisticsDeduplicateAcrossPeriodsAndIncludeZeroDaysAndMonths() {
        jdbc.update("""
                INSERT INTO user_check_in (user_id, check_in_date, checked_in_at) VALUES
                (1, '2024-02-28', '2024-02-28 09:00:00'),
                (1, '2024-02-29', '2024-02-29 09:00:00'),
                (2, '2024-02-29', '2024-02-29 09:00:00'),
                (2, '2024-03-01', '2024-03-01 09:00:00'),
                (3, '2024-03-01', '2024-03-01 09:00:00')
                """);
        LocalDate today = LocalDate.of(2024, 3, 1);
        LocalDate leapDay = today.minusDays(1);
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(today.atTime(12, 0));
            assertEquals(List.of(new Day(today.minusDays(3), 0), new Day(today.minusDays(2), 1),
                    new Day(leapDay, 2), new Day(today, 2)), statistics.daily(today.minusDays(3), today));

            Monthly february = statistics.monthly(2024, 2);
            assertEquals(new Totals(2, 3), february.totals());
            assertEquals(29, february.daily().size());
            assertEquals(new Day(leapDay, 2), february.peakDay());
            assertEquals(3.0 / 29, february.averageDailyUsers(), 0.000001);
            assertEquals(List.of(new Day(today, 2)), statistics.monthly(2024, 3).daily());

            Yearly year = statistics.yearly(2024);
            assertEquals(today, year.throughDate());
            assertEquals(new Totals(3, 5), year.totals());
            assertEquals(List.of(new Month(1, 0, 0), new Month(2, 2, 3), new Month(3, 2, 2)), year.monthly());
        }
    }

    @Test
    void jobArchivesOnlyThePreviousMonthEvenWhenOlderMonthsAreMissing() {
        jdbc.update("""
                INSERT INTO user_check_in (user_id, check_in_date, checked_in_at) VALUES
                (1, '2024-09-30', '2024-09-30 09:00:00'),
                (1, '2024-12-31', '2024-12-31 09:00:00'),
                (2, '2024-12-31', '2024-12-31 09:00:00'),
                (3, '2025-01-01', '2025-01-01 09:00:00')
                """);
        UserCheckInStatisticsJob job = jobWithLockAcquired(statistics);

        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2024, 11, 1, 12, 15));
            job.archivePreviousMonth();
            job.archivePreviousMonth();
            assertEquals(1L, snapshots.selectCount(null));
            assertNull(snapshots.selectById(LocalDate.of(2024, 9, 1)));
            assertEquals(new Totals(1, 1), statistics.monthly(2024, 9).totals());
            UserCheckInMonthSnapshot october = snapshots.selectById(LocalDate.of(2024, 10, 1));
            assertEquals(0, october.getSignedUsers());
            assertNull(october.getYearSignedUsers());
            assertNull(october.getYearCheckIns());

            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 15));
            job.archivePreviousMonth();
            assertEquals(2L, snapshots.selectCount(null));
            assertNull(snapshots.selectById(LocalDate.of(2024, 11, 1)));
            assertNull(snapshots.selectById(LocalDate.of(2025, 1, 1)));
            UserCheckInMonthSnapshot yearEnd = snapshots.selectById(LocalDate.of(2024, 12, 1));
            assertEquals(2L, yearEnd.getYearSignedUsers());
            assertEquals(3L, yearEnd.getYearCheckIns());
            assertEquals(new Totals(2, 3), statistics.yearly(2024).totals());
        }
    }

    @Test
    void jobAcceptsTheSnapshotWhenAnotherWriterInsertsThePreviousMonthFirst() {
        jdbc.update("""
                INSERT INTO user_check_in (user_id, check_in_date, checked_in_at) VALUES
                (1, '2024-11-30', '2024-11-30 09:00:00'),
                (2, '2024-12-01', '2024-12-01 09:00:00')
                """);
        UserCheckInMonthSnapshotMapper competingMapper = mock(
                UserCheckInMonthSnapshotMapper.class, delegatesTo(snapshots));
        doAnswer(call -> {
            UserCheckInMonthSnapshot snapshot = call.getArgument(0);
            snapshots.insert(snapshot);
            return snapshots.insert(snapshot);
        }).when(competingMapper).insert(any(UserCheckInMonthSnapshot.class));
        var competingStatistics = new UserCheckInStatisticsService(checkIns, competingMapper);

        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 15));
            jobWithLockAcquired(competingStatistics).archivePreviousMonth();
            assertEquals(1L, snapshots.selectCount(null));
            assertEquals(new Totals(1, 1), statistics.monthly(2024, 12).totals());
        }
    }

    @Test
    void archiveRejectsTheCurrentMonthBeforeWritingASnapshot() {
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 15));
            assertThrows(IllegalStateException.class, () -> statistics.archiveMonth(YearMonth.of(2025, 1)));
            assertEquals(0L, snapshots.selectCount(null));
        }
    }

    private UserCheckInStatisticsJob jobWithLockAcquired(UserCheckInStatisticsService archiveService) {
        ScheduleRoundLock lock = mock(ScheduleRoundLock.class);
        doAnswer(call -> {
            Runnable round = call.getArgument(1);
            round.run();
            return null;
        }).when(lock).run(anyString(), any(Runnable.class));
        return new UserCheckInStatisticsJob(lock, archiveService);
    }

    @Test
    void statisticsCombineArchivedDaysWithLiveGapsAndUseClosedYearTotals() {
        jdbc.update("""
                INSERT INTO user_check_in (user_id, check_in_date, checked_in_at) VALUES
                (1, '2024-11-30', '2024-11-30 09:00:00'),
                (1, '2024-12-01', '2024-12-01 09:00:00'),
                (2, '2024-12-01', '2024-12-01 09:00:00'),
                (2, '2024-12-31', '2024-12-31 09:00:00'),
                (3, '2025-01-02', '2025-01-02 09:00:00')
                """);
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2025, 1, 2, 12, 0));
            statistics.archiveMonth(YearMonth.of(2024, 12));
            // 留下未归档月份的明细，确认已归档的日、月、年结果从快照读取。
            jdbc.update("DELETE FROM user_check_in WHERE check_in_date BETWEEN '2024-12-01' AND '2024-12-31'");

            List<Day> days = statistics.daily(LocalDate.of(2024, 11, 30), LocalDate.of(2025, 1, 2));
            assertEquals(34, days.size());
            assertEquals(new Day(LocalDate.of(2024, 11, 30), 1), days.get(0));
            assertEquals(new Day(LocalDate.of(2024, 12, 1), 2), days.get(1));
            assertEquals(new Day(LocalDate.of(2024, 12, 31), 1), days.get(31));
            assertEquals(new Day(LocalDate.of(2025, 1, 1), 0), days.get(32));
            assertEquals(new Day(LocalDate.of(2025, 1, 2), 1), days.get(33));

            Monthly december = statistics.monthly(2024, 12);
            assertEquals(new Totals(2, 3), december.totals());
            assertEquals(new Day(LocalDate.of(2024, 12, 1), 2), december.peakDay());
            Yearly year = statistics.yearly(2024);
            assertEquals(new Totals(2, 4), year.totals());
            assertEquals(new Month(11, 1, 1), year.monthly().get(10));
            assertEquals(new Month(12, 2, 3), year.monthly().get(11));
            assertEquals(new Totals(1, 1), statistics.yearly(2025).totals());
        }
    }

    @Test
    void allStatisticsRoutesRequireInternalCredentials() throws Exception {
        String path = "/internal/user/check-ins/daily";
        mvc.perform(get(path).param("from", "2024-01-01").param("to", "2024-01-01"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/user/check-ins/monthly").param("year", "2024").param("month", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/user/check-ins/yearly").param("year", "2024"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("from", "2024-01-01").param("to", "2024-01-01")
                        .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].statDate").exists())
                .andExpect(jsonPath("$[0].signedUsers").value(0));
    }

    @Test
    void statisticsReturnSpecificErrorsForInvalidPeriods() throws Exception {
        String path = "/internal/user/check-ins/daily";
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(LocalDateTime.of(2026, 11, 6, 9, 0));
            mvc.perform(get(path).param("from", "2026-11-06").param("to", "2026-11-05")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("统计开始日期不能晚于结束日期"));
            mvc.perform(get(path).param("from", "2026-11-06").param("to", "2026-11-07")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("统计结束日期不能晚于今天"));
            mvc.perform(get("/internal/user/check-ins/monthly").param("year", "2026").param("month", "12")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("统计月份不能晚于当前月份"));
            mvc.perform(get("/internal/user/check-ins/yearly").param("year", "2027")
                            .header(UserIdHeaderFilter.INTERNAL_TOKEN_HEADER, INTERNAL_TOKEN))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("统计年份须在 1000 至 2026 之间"));
        }
    }

    @Test
    void dailyRangeCountsBothEndpointsAcrossYearsAndRejectsDay367() {
        jdbc.update("""
                INSERT INTO user_check_in (user_id, check_in_date, checked_in_at) VALUES
                (1, '2023-12-31', '2023-12-31 09:00:00'),
                (1, '2024-01-01', '2024-01-01 09:00:00'),
                (2, '2024-12-30', '2024-12-30 09:00:00')
                """);
        LocalDate from = LocalDate.of(2023, 12, 31);
        LocalDate to = from.plusDays(365);
        try (MockedStatic<BusinessTime> time = mockStatic(BusinessTime.class)) {
            time.when(BusinessTime::now).thenReturn(to.plusDays(1).atTime(12, 0));
            List<Day> days = statistics.daily(from, to);
            assertEquals(366, days.size());
            assertEquals(new Day(from, 1), days.get(0));
            assertEquals(new Day(from.plusDays(1), 1), days.get(1));
            assertEquals(new Day(to, 1), days.get(365));

            BizException error = assertThrows(BizException.class, () -> statistics.daily(from, to.plusDays(1)));
            assertEquals("统计日期范围最多 366 天", error.getMessage());
        }
    }

    @Test
    void checkInEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post("/user/check-ins")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/check-ins/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/check-ins/heatmap").param("year", "2024")).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_check_in", Integer.class));
    }
}
