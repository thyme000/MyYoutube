package cn.diaojiahong.myyoutube.chat.controller;

import cn.diaojiahong.myyoutube.chat.mongo.po.EmojiItem;
import cn.diaojiahong.myyoutube.chat.service.IEmojiItemService;
import cn.diaojiahong.myyoutube.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author thyme
 * @since 2026-04-12
 */
@RestController
@RequestMapping("/emoji")
@RequiredArgsConstructor
@Tag(name = "聊天模块")
public class EmojiItemController {

    private final IEmojiItemService emojiItemService;

    /**
     * 分类获取表情包
     * @return
     */
    @Operation(summary = "分类获取表情包")
    @GetMapping("/category")
    public Result<List<EmojiItem>> getCategory(@RequestParam String categoryCode) {
        return Result.success(emojiItemService.getEmojiByCategory(categoryCode));
    }
}
