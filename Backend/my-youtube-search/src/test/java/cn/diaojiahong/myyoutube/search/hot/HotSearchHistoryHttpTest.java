package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.config.JacksonConfig;
import cn.diaojiahong.myyoutube.common.handler.GlobalExceptionHandler;
import cn.diaojiahong.myyoutube.search.controller.*;
import cn.diaojiahong.myyoutube.search.api.HotSearchHistoryPort.*;
import cn.diaojiahong.myyoutube.search.mapper.HotSearchHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HotSearchHistoryHttpTest {
    final HotSearchHistoryMapper mapper = mock(HotSearchHistoryMapper.class);
    final LocalDate day = LocalDate.of(2026, 10, 20);
    final MockMvc mvc;

    HotSearchHistoryHttpTest() {
        var properties = new HotSearchProperties();
        var service = new HotSearchHistoryQueryService(mapper, new HotSearchFilter(properties), properties,
                Clock.fixed(Instant.parse("2026-10-20T16:30:00Z"), ZoneOffset.UTC));
        var builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().jackson2ObjectMapperBuilderCustomizer().customize(builder);
        mvc = MockMvcBuilders.standaloneSetup(new HotSearchHistoryController(service), new InternalHotSearchHistoryController(service))
                .setControllerAdvice(new HotSearchHistoryExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(builder.build())).build();
    }

    @Test void publicHistoryReturnsOnlyNamesAndInternalDetailsKeepLongIdsAsStrings() throws Exception {
        when(mapper.groups(day, null, 3, 200)).thenReturn(List.of(new Group("g", "主题", "v1", 3, 3, 3, 1, null)));
        mvc.perform(get("/search/hot/history").param("date", day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("主题"));
        long userId = 2_000_000_000_000_000_007L;
        when(mapper.keyword(day, "g", 1)).thenReturn("  C++ 入门  ");
        when(mapper.users(day, "g", "  C++ 入门  ", null, 21)).thenReturn(List.of(new User(userId, 2, 100, 200)));
        mvc.perform(get("/internal/hot-search/history/users").param("date", day.toString()).param("groupId", "g").param("keywordId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].userId").value(Long.toString(userId)))
                .andExpect(jsonPath("$.items[0].searchCount").value("2"));
    }

    @Test void dateAndBindingErrorsAre400EvenWithTheGlobalCatchAllAdvice() throws Exception {
        mvc.perform(get("/search/hot/history").param("date", "2026-10-21")).andExpect(status().isBadRequest());
        mvc.perform(get("/search/hot/history").param("date", "2026-02-30")).andExpect(status().isBadRequest());
        mvc.perform(get("/search/hot/history")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/hot-search/history/groups").param("date", day.toString()).param("size", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/hot-search/history/events").param("date", day.toString()).param("groupId", "g").param("keywordId", "x")).andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test void databaseFailureIs503InsteadOfAnEmptyLeaderboard() throws Exception {
        when(mapper.groups(day, null, 3, 200)).thenThrow(new DataAccessResourceFailureException("offline"));
        mvc.perform(get("/search/hot/history").param("date", day.toString()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
    }
}
