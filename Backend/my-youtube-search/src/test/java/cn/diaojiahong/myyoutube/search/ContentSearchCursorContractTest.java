package cn.diaojiahong.myyoutube.search;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.search.controller.SearchController;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchVideoVO;
import cn.diaojiahong.myyoutube.search.service.ISearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContentSearchCursorContractTest {
    @Test
    void endpointBindsCursorAndReturnsItemsWithoutPageTotals() throws Exception {
        ISearchService service = mock(ISearchService.class);
        SearchVideoVO row = new SearchVideoVO();
        row.setId("9007199254740993");
        when(service.searchVideo(any())).thenReturn(new CursorPage<>(List.of(row), "next"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SearchController(service)).build();

        mvc.perform(get("/search/video").param("keyword", "camera").param("cursor", "previous")
                        .param("pageSize", "20").param("sortByLatest", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.nextCursor").value("next"))
                .andExpect(jsonPath("$.data.total").doesNotExist()).andExpect(jsonPath("$.data.pages").doesNotExist());

        ArgumentCaptor<ContentSearchCursorQuery> query = ArgumentCaptor.forClass(ContentSearchCursorQuery.class);
        verify(service).searchVideo(query.capture());
        assertEquals("previous", query.getValue().getCursor());
        assertEquals(20, query.getValue().getPageSize());
        assertTrue(query.getValue().isSortByLatest());
    }
}
