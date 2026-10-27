package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchUserPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoByTagPageQuery;
import cn.diaojiahong.myyoutube.search.domain.dto.SearchVideoPageQuery;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchUserVO;
import cn.diaojiahong.myyoutube.search.domain.vo.SearchVideoVO;

import java.util.List;

public interface ISearchService {
    PageResult<SearchVideoVO> searchVideo(SearchVideoPageQuery query);

    PageResult<SearchUserVO> searchUser(SearchUserPageQuery pageQuery);

    List<String> prompt(String keyword);

    CursorPage<SearchVideoVO, String> searchByTag(SearchVideoByTagPageQuery pageQuery);

    List<String> hot();
}
