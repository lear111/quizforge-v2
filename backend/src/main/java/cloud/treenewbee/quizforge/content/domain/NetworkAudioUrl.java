package cloud.treenewbee.quizforge.content.domain;

import io.github.stephanebastian.whatwg.url.Url;
import java.net.InetAddress;

/** 只解析字面地址，不解析域名、不下载。与宿主浏览器使用相同的地址限制。 */
public final class NetworkAudioUrl {
    private NetworkAudioUrl() {}

    public static boolean isValid(Object raw) {
        if (!(raw instanceof String value) || value.length() > 2048 || !value.startsWith("https://")
                || value.chars().anyMatch(c -> c <= 32 || c == 127) || value.indexOf('#') >= 0) return false;
        try {
            Url url = Url.create(value);
            if (!url.username().isEmpty() || !url.password().isEmpty()) return false;
            String host = url.hostname();
            if (host.startsWith("[")) {
                // 0.8.8 序列化高位 IPv6 段时会符号扩展；保留其解析校验，单独规范化字面主机。
                // 输入限定为 IPv6 字面量，此调用不会进行 DNS 查询。
                if (!value.startsWith("https://[")) return false;
                String literal = value.substring(9, value.indexOf(']'));
                if (!literal.matches("[0-9a-fA-F:.]+")) return false;
                byte[] bytes = InetAddress.getByName(literal).getAddress();
                if (!publicV6(bytes)) return false;
                String canonical = "https://[" + canonicalV6(bytes) + "]" + url.href().substring(url.href().indexOf(']') + 1);
                return value.equals(canonical);
            }
            if (!value.equals(url.href())) return false;
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
                String[] parts = host.split("\\.");
                return publicV4(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            return host.contains(".") && !host.equals("localhost") && !host.endsWith(".localhost") && !host.endsWith(".local");
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String canonicalV6(byte[] bytes) {
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) words[i] = (bytes[2 * i] & 255) * 256 + (bytes[2 * i + 1] & 255);
        int start = -1, length = 1;
        for (int i = 0; i < 8;) {
            if (words[i] != 0) { i++; continue; }
            int end = i;
            while (end < 8 && words[end] == 0) end++;
            if (end - i > length) { start = i; length = end - i; }
            i = end;
        }
        var result = new StringBuilder();
        for (int i = 0; i < 8;) {
            if (i == start) { result.append("::"); i += length; }
            else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
                result.append(Integer.toHexString(words[i++]));
            }
        }
        return result.toString();
    }

    private static boolean publicV4(int a, int b, int c) {
        return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 100 && b >= 64 && b <= 127
                || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
                || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113);
    }

    private static boolean publicV6(byte[] a) {
        if (a.length != 16 || (a[0] & 0xe0) != 0x20) return false;
        int b = a[1] & 255, c = a[2] & 255, d = a[3] & 255;
        return !(a[0] == 0x20 && (b == 1 && ((c & 0xfe) == 0 || c == 0x0d && d == 0xb8) || b == 2)
                || a[0] == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }
}
