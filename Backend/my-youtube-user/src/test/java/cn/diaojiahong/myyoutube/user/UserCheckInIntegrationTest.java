package cn.diaojiahong.myyoutube.user;

import cn.diaojiahong.myyoutube.common.filter.UserIdHeaderFilter;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.cache.UserCheckInBitmapCache;
import cn.diaojiahong.myyoutube.user.controller.UserCheckInController;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInHeatmapVO;
import cn.diaojiahong.myyoutube.user.domain.vo.CheckInStatusVO;
import cn.diaojiahong.myyoutube.user.mapper.UserCheckInMapper;
import cn.diaojiahong.myyoutube.user.service.UserCheckInService;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    private UserCheckInService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:check-in-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        String schema = Files.readString(Path.of("../../resources/schema.sql"));
        int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `user_check_in`");
        jdbc.execute(schema.substring(start, schema.indexOf(';', start) + 1));

        var factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(UserCheckInMapper.class);
        UserCheckInMapper mapper = new SqlSessionTemplate(factory).getMapper(UserCheckInMapper.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        service = new UserCheckInService(mapper, new UserCheckInBitmapCache(redis));

        var filter = new UserIdHeaderFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "internalToken", INTERNAL_TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new UserCheckInController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(filter)
                .build();
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
    void checkInEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post("/user/check-ins")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/check-ins/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/user/check-ins/heatmap").param("year", "2024")).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_check_in", Integer.class));
    }
}
