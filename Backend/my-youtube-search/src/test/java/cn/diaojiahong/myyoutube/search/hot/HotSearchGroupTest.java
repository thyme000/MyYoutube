package cn.diaojiahong.myyoutube.search.hot;

import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HotSearchGroupTest {
    @Test void snapshotsTheInputAndExposesAnUnmodifiableVector() {
        var vector = new ArrayList<>(List.of(1f, 0f));
        var group = group(vector);
        vector.set(0, 0f);
        vector.clear();
        assertEquals(List.of(1f, 0f), group.vector());
        assertThrows(UnsupportedOperationException.class, () -> group.vector().set(0, 0f));
        assertEquals(1, group.similarity(List.of(1f, 0f)), 1e-12);
    }

    @Test void preservesTheCacheJsonContractAndImmutabilityAfterDeserialization() {
        var original = group(List.of(1f, 0f));
        var restored = JsonUtil.fromJson(JsonUtil.toJson(original), HotSearchGroup.class);
        assertEquals(original, restored);
        assertTrue(restored.valid("v1", 2));
        assertThrows(UnsupportedOperationException.class, () -> restored.vector().add(2f));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[0,0]", "[1,null]"})
    void malformedCachedVectorsRemainReadableAndCanBeRejectedIndividually(String vectorJson) {
        String json = "{\"id\":\"1\",\"name\":\"组名\",\"vector\":" + vectorJson + ",\"version\":\"v1\"}";
        var restored = assertDoesNotThrow(() -> JsonUtil.fromJson(json, HotSearchGroup.class));
        assertFalse(restored.valid("v1", 2));
        assertTrue(Double.isNaN(restored.similarity(List.of(1f, 0f))));
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void rejectsInvalidVectorsOnEitherSideWithoutThrowing(List<Float> invalid) {
        var valid = group(List.of(1f, 0f));
        assertTrue(Double.isNaN(valid.similarity(invalid)));
        assertTrue(Double.isNaN(group(invalid).similarity(valid.vector())));
        assertFalse(HotSearchGroup.validVector(invalid, 2));
    }

    static Stream<Arguments> invalidVectors() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(List.of()),
                Arguments.of(List.of(1f)),
                Arguments.of(List.of(0f, 0f)),
                Arguments.of(Arrays.asList(1f, null)),
                Arguments.of(List.of(Float.NaN, 1f)),
                Arguments.of(List.of(Float.POSITIVE_INFINITY, 1f)),
                Arguments.of(List.of(Float.NEGATIVE_INFINITY, 1f)));
    }

    @Test void preservesValidCosinesIncludingNegativeOne() {
        var group = group(List.of(1f, 0f));
        assertEquals(1, group.similarity(List.of(1f, 0f)), 1e-12);
        assertEquals(-1, group.similarity(List.of(-1f, 0f)), 1e-12);
        assertEquals(0, group.similarity(List.of(0f, 1f)), 1e-12);
        assertEquals(.6, group.similarity(List.of(3f, 4f)), 1e-12);
    }

    @ParameterizedTest
    @ValueSource(floats = {1e-9f, Float.MIN_VALUE, Float.MAX_VALUE})
    void acceptsNonzeroFiniteVectorsRegardlessOfScale(float value) {
        var group = group(List.of(value, value));
        assertTrue(group.valid("v1", 2));
        assertEquals(1, group.similarity(List.of(value, value)), 1e-12);
        assertEquals(-1, group.similarity(List.of(-value, -value)), 1e-12);
    }

    @Test void emptyVectorsDoNotProduceAValidSimilarity() {
        assertTrue(Double.isNaN(group(List.of()).similarity(List.of())));
    }

    private static HotSearchGroup group(List<Float> vector) {
        return new HotSearchGroup("1", "组名", vector, "v1");
    }
}
