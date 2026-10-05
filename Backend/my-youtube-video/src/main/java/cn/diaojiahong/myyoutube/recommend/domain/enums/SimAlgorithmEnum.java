package cn.diaojiahong.myyoutube.recommend.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 物品相似度算法: 同一对内容各自算一份, 在线按配置切换
 */
@Getter
public enum SimAlgorithmEnum {

    /**
     * 经典 ItemCF: 共现加权和 / sqrt(热度乘积)
     */
    ITEMCF("itemcf"),

    /**
     * Swing: 共现贡献按用户对交集大小平滑, 抗活跃用户噪声
     */
    SWING("swing");

    @EnumValue
    private final String code;

    SimAlgorithmEnum(String code) {
        this.code = code;
    }

    public static SimAlgorithmEnum fromCode(String code) {
        for (SimAlgorithmEnum a : values()) {
            if (a.getCode().equalsIgnoreCase(code)) {
                return a;
            }
        }
        return null;
    }
}
