package cn.diaojiahong.myyoutube.common.page;

import java.util.List;

public record CursorPage<T, C>(List<T> items, C nextCursor) {
}
