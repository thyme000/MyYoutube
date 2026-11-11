package cn.diaojiahong.myyoutube.common.counter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条计数和权威值对不上的记录。diff = stored - truth，正数表示计数列偏高。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterDrift {

    private long id;
    private long stored;
    private long truth;
    private long diff;
}
