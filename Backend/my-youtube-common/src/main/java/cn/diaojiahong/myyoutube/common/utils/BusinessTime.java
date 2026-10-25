package cn.diaojiahong.myyoutube.common.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 业务表中的无时区时间使用上海时区；UTC 管线表不使用此转换。 */
public final class BusinessTime {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private BusinessTime() {}
    public static Instant toInstant(LocalDateTime time) { return time.atZone(ZONE).toInstant(); }
    public static LocalDateTime fromInstant(Instant time) { return LocalDateTime.ofInstant(time, ZONE); }
    public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
}
