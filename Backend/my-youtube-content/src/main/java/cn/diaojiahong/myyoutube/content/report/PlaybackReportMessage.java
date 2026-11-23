package cn.diaojiahong.myyoutube.content.report;

import java.util.List;

/**
 * 一次播放上报的消息正文。会话和接收时间在投递前已经盖章，消费时原样落库。
 */
public record PlaybackReportMessage(Long userId, List<PlaybackReportDTO> reports) {
    public static final String TOPIC = "playback_report_topic";
}
