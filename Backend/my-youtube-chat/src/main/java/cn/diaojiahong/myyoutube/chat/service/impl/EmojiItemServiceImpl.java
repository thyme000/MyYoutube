package cn.diaojiahong.myyoutube.chat.service.impl;

import cn.diaojiahong.myyoutube.chat.mongo.po.EmojiItem;
import cn.diaojiahong.myyoutube.chat.mapper.EmojiItemMapper;
import cn.diaojiahong.myyoutube.chat.service.IEmojiItemService;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-12
 */
@Service
public class EmojiItemServiceImpl extends ServiceImpl<EmojiItemMapper, EmojiItem> implements IEmojiItemService {

    @Override
    public List<EmojiItem> getEmojiByCategory(String categoryCode) {
        List<EmojiItem> list = lambdaQuery().eq(EmojiItem::getCategoryCode, categoryCode).list();
        if (list.isEmpty()) {
            throw new BizException(BizException.MESSAGE_ERROR, "分类不存在");
        }
        return list;
    }
}
