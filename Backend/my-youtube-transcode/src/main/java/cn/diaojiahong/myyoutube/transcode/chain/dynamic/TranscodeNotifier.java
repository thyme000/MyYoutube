package cn.diaojiahong.myyoutube.transcode.chain.dynamic;

import cn.diaojiahong.myyoutube.common.push.PushFanoutPublisher;
import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 转码终态 SSE 通知: 成功(CompleteStep)与失败(监听器兜底)两条路共用。
 */
@Component
@RequiredArgsConstructor
public class TranscodeNotifier {

    private final PushFanoutPublisher pushFanoutPublisher;

    /** 同 task_key 的所有在途行一起落终态, 逐用户发 SSE(载荷带 taskIds 供前端对号) */
    public void notifyAffected(List<TranscodeTask> affected, String signal) {
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> taskIds = new LinkedHashSet<>();
        for (TranscodeTask t : affected) {
            userIds.add(t.getUserId());
            taskIds.add(t.getId());
        }
        for (Long userId : userIds) {
            pushFanoutPublisher.pushSseSignal(userId, signal, Map.of("taskIds", taskIds));
        }
    }
}
