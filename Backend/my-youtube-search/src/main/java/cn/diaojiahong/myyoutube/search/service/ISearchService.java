package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.TagSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.ContentSearchCursorQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchUserVO;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchContentVO;

import java.util.List;

public interface ISearchService {
    CursorPage<SearchContentVO, String> searchContent(ContentSearchCursorQuery query);

    PageResult<SearchUserVO> searchUser(SearchUserPageQuery pageQuery);

    List<String> prompt(String keyword);

    CursorPage<SearchContentVO, String> searchByTag(TagSearchCursorQuery pageQuery);

    List<String> hot();
}
