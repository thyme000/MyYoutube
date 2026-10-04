package cn.diaojiahong.myyoutube.chat.service;

import cn.diaojiahong.myyoutube.chat.mongo.po.EmojiItem;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author thyme
 * @since 2026-04-12
 */
public interface IEmojiItemService extends IService<EmojiItem> {

    List<EmojiItem> getEmojiByCategory(String categoryCode);

}
