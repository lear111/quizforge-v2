package cloud.treenewbee.quizforge.content.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** JSON data only: binary64 numbers, UTF-16 key ordering and well-formed JSON.stringify strings. */
final class EcmaJson {
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxNumberLength(8 * 1024 * 1024).maxStringLength(8 * 1024 * 1024)
            .maxNameLength(8 * 1024 * 1024).maxNestingDepth(Integer.MAX_VALUE).build()).build();

    private EcmaJson() {}

    static Object parse(byte[] bytes) {
        try (JsonParser parser = JSON.createParser(utf8(bytes))) {
            Deque<Container> stack = new ArrayDeque<>();
            Object result = null;
            boolean seenRoot = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.PROPERTY_NAME) { stack.peek().key = parser.currentName(); continue; }
                if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) { stack.pop(); continue; }
                Object value = switch (token) {
                    case START_OBJECT -> new LinkedHashMap<String, Object>();
                    case START_ARRAY -> new ArrayList<>();
                    case VALUE_STRING -> parser.getString();
                    case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> parser.getDoubleValue();
                    case VALUE_TRUE -> true;
                    case VALUE_FALSE -> false;
                    case VALUE_NULL -> null;
                    default -> throw new IllegalArgumentException("Invalid JSON token");
                };
                if (stack.isEmpty()) {
                    if (seenRoot) throw new IllegalArgumentException("Trailing JSON input");
                    seenRoot = true; result = value;
                } else stack.peek().add(value);
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) stack.push(new Container(value));
            }
            if (!seenRoot || !stack.isEmpty()) throw new IllegalArgumentException("Incomplete JSON");
            return result;
        } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid extension JSON", error); }
    }

    private static final class Container {
        private final Object value;
        private String key;
        private Container(Object value) { this.value = value; }
        @SuppressWarnings("unchecked")
        private void add(Object child) {
            if (value instanceof Map<?, ?>) ((Map<String, Object>) value).put(key, child);
            else ((List<Object>) value).add(child);
        }
    }

    static String stringify(Object value, boolean canonical) {
        StringBuilder out = new StringBuilder();
        Deque<Part> pending = new ArrayDeque<>();
        pending.push(new Value(value));
        while (!pending.isEmpty()) {
            Part part = pending.pop();
            if (part instanceof Literal literal) out.append(literal.text());
            else if (part instanceof Quoted quoted) quote(quoted.text(), out);
            else write(((Value) part).value(), canonical, out, pending);
        }
        return out.toString();
    }

    private interface Part {}
    private record Literal(String text) implements Part {}
    private record Quoted(String text) implements Part {}
    private record Value(Object value) implements Part {}

    private static void write(Object value, boolean canonical, StringBuilder out, Deque<Part> pending) {
        if (value == null) out.append("null");
        else if (value instanceof String string) quote(string, out);
        else if (value instanceof Boolean bool) out.append(bool);
        else if (value instanceof Number number) out.append(number(number.doubleValue()));
        else if (value instanceof List<?> list) {
            out.append('[');
            pending.push(new Literal("]"));
            for (int index = list.size() - 1; index >= 0; index--) {
                pending.push(new Value(list.get(index)));
                if (index > 0) pending.push(new Literal(","));
            }
        } else if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) keys.add((String) key);
            // JSON.stringify enumerates integer-index properties first, even after Object.keys().sort().
            keys.sort((left, right) -> {
                long a = arrayIndex(left), b = arrayIndex(right);
                if (a >= 0 || b >= 0) return a < 0 ? 1 : b < 0 ? -1 : Long.compare(a, b);
                return canonical ? left.compareTo(right) : 0;
            });
            out.append('{');
            pending.push(new Literal("}"));
            for (int index = keys.size() - 1; index >= 0; index--) {
                String key = keys.get(index);
                pending.push(new Value(map.get(key))); pending.push(new Literal(":")); pending.push(new Quoted(key));
                if (index > 0) pending.push(new Literal(","));
            }
        } else throw new IllegalArgumentException("Not JSON data");
    }

    private static long arrayIndex(String key) {
        if (key.isEmpty() || key.length() > 10 || key.charAt(0) == '0' && key.length() != 1) return -1;
        long number = 0;
        for (int index = 0; index < key.length(); index++) {
            char digit = key.charAt(index);
            if (digit < '0' || digit > '9') return -1;
            number = number * 10 + digit - '0';
        }
        return number < 4294967295L ? number : -1;
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || Character.isSurrogate(c)
                            && !(Character.isHighSurrogate(c) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1)))
                            && !(Character.isLowSurrogate(c) && index > 0 && Character.isHighSurrogate(value.charAt(index - 1)))) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) out.append("0123456789abcdef".charAt(c >> shift & 15));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }

    // ECMA-262 Number::toString: shortest round-tripping decimal, closest to the exact binary64
    // value, with an even last digit on ties. Search adjacent decimal grid points as rounding
    // intervals at binary powers can be asymmetric. There is always a solution by 17 digits.
    static String number(double value) {
        if (!Double.isFinite(value)) return "null";
        if (value == 0) return "0";
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal exact = new BigDecimal(magnitude);
        int exponent = exact.precision() - exact.scale() - 1;
        BigDecimal shortest = null;
        for (int precision = 1; precision <= 17 && shortest == null; precision++) {
            BigDecimal rounded = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            BigDecimal unit = BigDecimal.ONE.scaleByPowerOfTen(exponent - precision + 1);
            BigDecimal distance = null;
            for (int offset = -1; offset <= 1; offset++) {
                BigDecimal candidate = rounded.add(unit.multiply(BigDecimal.valueOf(offset))).stripTrailingZeros();
                if (candidate.signum() <= 0 || candidate.precision() > precision
                        || Double.doubleToRawLongBits(Double.parseDouble(candidate.toString())) != Double.doubleToRawLongBits(magnitude)) continue;
                BigDecimal delta = candidate.subtract(exact).abs();
                if (distance == null || delta.compareTo(distance) < 0
                        || delta.compareTo(distance) == 0 && !candidate.unscaledValue().testBit(0)) {
                    shortest = candidate; distance = delta;
                }
            }
        }
        if (shortest == null) throw new IllegalStateException("No binary64 decimal representation");
        String digits = shortest.unscaledValue().toString();
        int n = digits.length() - shortest.scale(), k = digits.length();
        String text;
        if (k <= n && n <= 21) text = digits + "0".repeat(n - k);
        else if (n > 0 && n <= 21) text = digits.substring(0, n) + "." + digits.substring(n);
        else if (n > -6 && n <= 0) text = "0." + "0".repeat(-n) + digits;
        else text = digits.charAt(0) + (k == 1 ? "" : "." + digits.substring(1)) + "e" + (n > 0 ? "+" : "") + (n - 1);
        return negative ? "-" + text : text;
    }

    // Buffer.toString('utf8') follows maximal valid prefixes, including malformed surrogate bytes.
    private static String utf8(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < bytes.length;) {
            int first = bytes[index] & 255;
            if (first < 128) { result.append((char) first); index++; continue; }
            int length = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
            if (length == 0) { result.append('\ufffd'); index++; continue; }
            int code = first & (0x7f >> length), used = 1;
            while (used < length && index + used < bytes.length) {
                int next = bytes[index + used] & 255;
                if (next < 0x80 || next > 0xbf || used == 1 && (first == 0xe0 && next < 0xa0 || first == 0xed && next > 0x9f
                        || first == 0xf0 && next < 0x90 || first == 0xf4 && next > 0x8f)) break;
                code = code << 6 | next & 63; used++;
            }
            if (used == length) result.appendCodePoint(code); else result.append('\ufffd');
            index += used;
        }
        return result.toString();
    }
}
