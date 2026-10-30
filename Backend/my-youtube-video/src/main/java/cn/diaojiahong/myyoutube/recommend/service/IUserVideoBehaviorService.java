package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.dto.BehaviorDTO;
import cn.diaojiahong.myyoutube.recommend.domain.po.UserVideoBehavior;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 用户-视频行为流水表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface IUserVideoBehaviorService extends IService<UserVideoBehavior> {

    void report(BehaviorDTO behaviorDTO);
    }
