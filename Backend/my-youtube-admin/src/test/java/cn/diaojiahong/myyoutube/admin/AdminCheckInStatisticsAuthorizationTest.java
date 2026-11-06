package cn.diaojiahong.myyoutube.admin;

import cn.diaojiahong.myyoutube.admin.controller.AdminCheckInStatisticsController;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort;
import cn.diaojiahong.myyoutube.user.api.UserCheckInStatisticsPort.Day;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCheckInStatisticsAuthorizationTest {
    private final UserCheckInStatisticsPort statistics = mock(UserCheckInStatisticsPort.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AdminCheckInStatisticsController(statistics, AuthorizationFixture.authorization()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    @Test
    void rejectsOrdinaryUsersBeforeCallingStatistics() throws Exception {
        UserContext.setUser("2");
        mvc.perform(get("/admin/check-ins/daily").param("from", "2024-02-01").param("to", "2024-02-02"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/check-ins/monthly").param("year", "2024").param("month", "2"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/check-ins/yearly").param("year", "2024"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(statistics);
    }

    @Test
    void administratorsCanQueryAnIsoDateRange() throws Exception {
        UserContext.setUser("1");
        LocalDate from = LocalDate.of(2024, 2, 1);
        LocalDate to = from.plusDays(1);
        when(statistics.daily(from, to)).thenReturn(List.of(new Day(from, 2), new Day(to, 0)));

        mvc.perform(get("/admin/check-ins/daily").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].statDate").exists())
                .andExpect(jsonPath("$.data[0].signedUsers").value(2))
                .andExpect(jsonPath("$.data[1].signedUsers").value(0));
    }
}
