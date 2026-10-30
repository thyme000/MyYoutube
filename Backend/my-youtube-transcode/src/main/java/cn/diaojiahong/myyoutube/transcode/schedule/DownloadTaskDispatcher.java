package cn.diaojiahong.myyoutube.transcode.schedule;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import cn.diaojiahong.myyoutube.transcode.mapper.TranscodeTaskMapper;
import cn.diaojiahong.myyoutube.transcode.mq.message.TranscodeTaskMessage;
import cn.diaojiahong.myyoutube.transcode.service.TranscodeTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadTaskDispatcher {
    private final TranscodeTaskMapper rows;
    private final TranscodeTaskService tasks;
    private final RocketMQTemplate mq;

    public void dispatch(Long id) {
        if (reserve(id)) send(id);
    }

    private boolean reserve(Long id) {
        return rows.reserveDispatch(id) > 0;
    }

    private void send(Long id) {
        try {
            mq.syncSend(MqConstants.TOPIC_DYNAMIC_TRANSCODE, new TranscodeTaskMessage(id));
        } catch (Exception e) {
            log.warn("下载任务投递失败，等待补偿 taskId={}", id, e);
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void recover() {
        for (TranscodeTask row : rows.due()) {
            try {
                // 忙碌任务也推迟到下一轮，避免同一哈希占满分页而饿死后续任务。
                if (!reserve(row.getId())) continue;
                RLock lock = tasks.artifactLock(row.getTaskKey());
                if (!lock.tryLock()) continue;
                try {
                    send(row.getId());
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("下载任务补偿失败 taskId={}", row.getId(), e);
            }
        }
    }
}
