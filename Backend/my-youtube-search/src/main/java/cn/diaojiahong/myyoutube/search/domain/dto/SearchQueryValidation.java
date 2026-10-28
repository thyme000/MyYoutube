package cn.diaojiahong.myyoutube.search.domain.dto;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;

public final class SearchQueryValidation {
    public static final int MAX_KEYWORD_LENGTH = 100;

    private SearchQueryValidation() {}

    public static void validateKeyword(String keyword) {
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "搜索词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
    }
}
