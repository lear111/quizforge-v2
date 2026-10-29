package cloud.treenewbee.quizforge.content.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** Exact JSON numbers and deeply immutable values; extension data remains opaque to the host. */
final class PackageJson {
    private static final JsonFactory JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
            .maxStringLength(2_000_000).maxNameLength(2_000_000).maxNumberLength(100)
            .maxNestingDepth(64).build()).build();

    private PackageJson() {}

    static Object read(byte[] bytes) {
        try {
            String input = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            // TextDecoder('utf-8', {fatal:true}) in the legacy host consumes one initial BOM.
            if (input.startsWith("\ufeff")) input = input.substring(1);
            return parse(input);
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("JSON 不是有效的 UTF-8", error); }
    }

    private static Object parse(String input) {
        try (JsonParser parser = JSON.createParser(input)) {
            Deque<Frame> frames = new ArrayDeque<>();
            Object root = null;
            boolean complete = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (complete) throw new IllegalArgumentException("JSON 末尾存在多余内容");
                if (token == JsonToken.PROPERTY_NAME) {
                    Frame frame = frames.peek();
                    frame.key = parser.currentName();
                    if (frame.object.containsKey(frame.key)) throw new IllegalArgumentException("JSON 包含重复字段");
                    continue;
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    frames.push(new Frame(token == JsonToken.START_OBJECT)); continue;
                }
                Object value = switch (token) {
                    case END_OBJECT, END_ARRAY -> frames.pop().freeze();
                    case VALUE_STRING -> parser.getString();
                    case VALUE_NUMBER_INT -> number(parser, true);
                    case VALUE_NUMBER_FLOAT -> number(parser, false);
                    case VALUE_TRUE -> true;
                    case VALUE_FALSE -> false;
                    case VALUE_NULL -> null;
                    default -> throw new IllegalArgumentException("无效的 JSON");
                };
                if (value instanceof Number number && !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("JSON 数值超出范围");
                if (frames.isEmpty()) { root = value; complete = true; }
                else frames.peek().add(value);
            }
            if (!complete) throw new IllegalArgumentException("JSON 不完整");
            return root;
        }
    }

    private static Number number(JsonParser parser, boolean integer) {
        String literal = parser.getString();
        if (literal.length() > 100) throw new IllegalArgumentException("JSON 数字字面量超过 100 字符");
        if (integer) return new java.math.BigInteger(literal);
        double approximate = Double.parseDouble(literal);
        if (!Double.isFinite(approximate)) throw new IllegalArgumentException("JSON 数值超出范围");
        try {
            java.math.BigDecimal exact = new java.math.BigDecimal(literal);
            if (exact.signum() == 0 && Double.doubleToRawLongBits(approximate) < 0) return -0.0d;
            return exact;
        } catch (RuntimeException error) {
            // An exponent outside BigDecimal's scale range may still be a finite zero in JS.
            if (approximate == 0) return approximate;
            throw error;
        }
    }

    private static final class Frame {
        final Map<String, Object> object;
        final List<Object> array;
        String key;
        Frame(boolean isObject) {
            object = isObject ? new LinkedHashMap<>() : null;
            array = isObject ? null : new ArrayList<>();
        }
        void add(Object value) {
            if (object != null) object.put(key, value);
            else array.add(value);
        }
        Object freeze() { return object != null ? Collections.unmodifiableMap(object) : Collections.unmodifiableList(array); }
    }
}
