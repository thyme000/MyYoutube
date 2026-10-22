package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.search.hot.HotSearchHistoryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/search/hot")
@RequiredArgsConstructor
public class HotSearchHistoryController {
    private final HotSearchHistoryQueryService service;

    @GetMapping("/history")
    public Result<List<String>> hot(@RequestParam("date") String date) {
        return Result.success(service.hot(date));
    }
}
