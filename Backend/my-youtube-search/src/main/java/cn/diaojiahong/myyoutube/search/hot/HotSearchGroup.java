package cn.diaojiahong.myyoutube.search.hot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record HotSearchGroup(String id, String name, List<Float> vector, String version) {
    public HotSearchGroup {
        // 保留非法值，由 valid() 逐组淘汰坏缓存。
        if (vector != null) vector = Collections.unmodifiableList(new ArrayList<>(vector));
    }

    public boolean valid(String expectedVersion, int dims) {
        return id != null && name != null && expectedVersion != null && expectedVersion.equals(version)
                && validVector(vector, dims);
    }

    public static boolean validVector(List<Float> vector, int dims) {
        if (vector == null || dims <= 0 || vector.size() != dims) return false;
        double norm = 0;
        for (Float value : vector) {
            if (value == null || !Float.isFinite(value)) return false;
            norm += (double) value * value;
        }
        return norm > 0;
    }

    public double similarity(List<Float> other) {
        if (vector == null || other == null || vector.size() != other.size()) return Double.NaN;
        double dot = 0, left = 0, right = 0;
        for (int i = 0; i < vector.size(); i++) {
            Float leftValue = vector.get(i), rightValue = other.get(i);
            if (leftValue == null || rightValue == null || !Float.isFinite(leftValue) || !Float.isFinite(rightValue)) {
                return Double.NaN;
            }
            double a = leftValue, b = rightValue;
            dot += a * b; left += a * a; right += b * b;
        }
        double denominator = Math.sqrt(left * right);
        return denominator > 0 ? dot / denominator : Double.NaN;
    }
}
