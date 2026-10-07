package cn.diaojiahong.myyoutube.transcode.task;

import cn.diaojiahong.myyoutube.common.mq.message.MediaTaskMessage;
import cn.diaojiahong.myyoutube.transcode.domain.enums.MediaTaskType;

/**
 * 任务执行器 SPI: 一种任务类型一个实现, Worker 按 taskType 路由
 */
public interface MediaTaskExecutor {

    MediaTaskType type();

    /** 执行任务, 返回 resultJson; 失败抛异常由 Worker 统一回报 */
    String execute(MediaTaskMessage msg) throws Exception;
}
