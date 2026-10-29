package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackageJsonTest {
    @Test
    @SuppressWarnings("unchecked")
    void retainsOpaqueJsonValuesAndFreezesNestedObjectsAndLists() {
        Map<String, Object> root = (Map<String, Object>) PackageJson.read(bytes("{\"n\":9007199254740993,\"d\":1.234567890123456789,\"items\":[null,{\"中文\":\"😀\"}]}"));
        assertThat(root.get("n")).isEqualTo(new BigInteger("9007199254740993"));
        assertThat(root.get("d")).isEqualTo(new BigDecimal("1.234567890123456789"));
        List<Object> items = (List<Object>) root.get("items");
        assertThat(items.get(0)).isNull();
        assertThatThrownBy(() -> root.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> items.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<String, Object>) items.get(1)).put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMalformedUtf8AndInvalidOrTrailingJson() {
        assertThatThrownBy(() -> PackageJson.read(new byte[] {'"', (byte) 255, '"'})).isInstanceOf(IllegalArgumentException.class);
        for (String value : new String[] {"", "{", "{} {}", "[1,]", "NaN", "01"}) {
            assertThatThrownBy(() -> PackageJson.read(bytes(value))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void retainsLegacyJsonDepthDuplicateAndNumberLimits() {
        assertThat(PackageJson.read(bytes("[".repeat(64) + "0" + "]".repeat(64)))).isInstanceOf(List.class);
        for (String input : List.of("[".repeat(65) + "0" + "]".repeat(65), "{\"x\":1,\"x\":2}", "1e400", "1".repeat(101))) {
            assertThatThrownBy(() -> PackageJson.read(bytes(input))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void legacyStringAndNumberLengthBoundariesAreExact() {
        assertThat(PackageJson.read(bytes("\"" + "x".repeat(2_000_000) + "\""))).isInstanceOf(String.class);
        assertThatThrownBy(() -> PackageJson.read(bytes("\"" + "x".repeat(2_000_001) + "\""))).isInstanceOf(RuntimeException.class);
        assertThat(PackageJson.read(bytes("1".repeat(100)))).isInstanceOf(BigInteger.class);
        assertThat(PackageJson.read(bytes("-" + "1".repeat(99)))).isInstanceOf(BigInteger.class);
        for (String number : List.of("-" + "1".repeat(100), "1." + "1".repeat(99), "1e+" + "0".repeat(98))) {
            assertThatThrownBy(() -> PackageJson.read(bytes(number))).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void legacyUtf8BomNegativeZeroAndFiniteExtremeExponentsRemainAccepted() {
        assertThat(PackageJson.read(bytes("\ufeff{}"))).isEqualTo(Map.of());
        assertThatThrownBy(() -> PackageJson.read(bytes("\ufeff\ufeff{}"))).isInstanceOf(RuntimeException.class);
        assertThat(Double.doubleToRawLongBits(((Number) PackageJson.read(bytes("-0.0"))).doubleValue())).isEqualTo(Long.MIN_VALUE);
        for (String number : List.of("0e2147483648", "1e-2147483649")) {
            assertThat(((Number) PackageJson.read(bytes(number))).doubleValue()).isZero();
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
