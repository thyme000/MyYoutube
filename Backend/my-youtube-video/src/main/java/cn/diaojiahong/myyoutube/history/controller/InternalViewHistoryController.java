package cn.diaojiahong.myyoutube.history.controller;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.history.domain.po.UserViewHistory;
import cn.diaojiahong.myyoutube.history.mapper.UserViewHistoryMapper;
import cn.diaojiahong.myyoutube.video.api.ViewHistoryPort;
import cn.diaojiahong.myyoutube.video.api.ViewHistoryPort.Candidates;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/view-history")
@RequiredArgsConstructor
public class InternalViewHistoryController {

    private final UserViewHistoryMapper historyMapper;

    @PostMapping("/viewed-ids")
    public List<Long> viewedIds(@RequestBody Candidates query) {
        List<Long> contentIds = query.contentIds();
        if (query.userId() == null || query.userId() <= 0 || contentIds == null
                || contentIds.size() > ViewHistoryPort.MAX_CANDIDATES) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "观看历史候选查询无效");
        }
        if (contentIds.isEmpty()) {
            return List.of();
        }

        return historyMapper.selectList(new LambdaQueryWrapper<UserViewHistory>()
                        .select(UserViewHistory::getContentId)
                        .eq(UserViewHistory::getUserId, query.userId())
                        .in(UserViewHistory::getContentId, contentIds))
                .stream()
                .map(UserViewHistory::getContentId)
                .toList();
    }
}
