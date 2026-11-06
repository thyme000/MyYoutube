package cn.diaojiahong.myyoutube.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public record CheckInHeatmapVO(int year, List<LocalDate> checkedInDates,
                               @Schema(description = "所选自然年的签到天数") int daysInYear) {
}
