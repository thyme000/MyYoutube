package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.enums.EventEnum;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserTagPreference;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IUserTagPreferenceService extends IService<UserTagPreference> {

    void addBehaviorScore(Long userId, Long contentId, EventEnum event);

    void addTagScore(Long userId, List<Long> tagIds, int delta);
}
