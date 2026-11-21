package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import cn.diaojiahong.myyoutube.recommend.mapper.UserTagPreferenceMapper;
import cn.diaojiahong.myyoutube.recommend.service.IUserTagPreferenceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserTagPreferenceServiceImpl extends ServiceImpl<UserTagPreferenceMapper, UserTagPreference>
        implements IUserTagPreferenceService {

    @Override
    public void addBehaviorScore(Long userId, Long contentId, EventEnum event) {
        baseMapper.addBehaviorScore(userId, contentId, event.getScore());
    }

    @Override
    public void addTagScore(Long userId, List<Long> tagIds, int delta) {
        if (!tagIds.isEmpty()) {
            baseMapper.addTagScore(userId, tagIds, delta);
        }
    }
}
