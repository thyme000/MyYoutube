package cn.diaojiahong.myyoutube.common.counter;

import java.util.List;

/** 管理端按计数类型覆盖选中的 id。权威值由持有关系表的服务重算，不接收调用方传入的数字。 */
public record CounterRepairRequest(String type, List<Long> ids) {
}
