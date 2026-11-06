package cn.diaojiahong.myyoutube.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record CheckInStatusVO(LocalDate date, boolean checkedIn,
                              @Schema(description = "本自然年的签到天数") int daysInYear) {
}
