package cn.diaojiahong.myyoutube.user.api.dto;
public record ContentCountEvent(String eventId, Long userId, int delta) {}
