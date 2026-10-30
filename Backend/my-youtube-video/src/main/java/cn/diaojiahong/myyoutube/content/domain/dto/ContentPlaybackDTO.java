package cn.diaojiahong.myyoutube.content.domain.dto;

public record ContentPlaybackDTO(String sessionId, Long sessionStartedAt, Long sequence, Long watchedMs,
                                 Long videoPositionMs, Integer imageIndex, Long imageElapsedMs, Long audioPositionMs) {
}
