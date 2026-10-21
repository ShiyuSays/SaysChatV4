package sayschat.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析与序列化。
 * 不依赖任何第三方库,兼容 Java 8 与 Android。
 */
public final class Json {
    private Json() {}

    // ---------- 序列化 ----------
    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) {
            sb.append('"');
            esc(sb, (String) o);
            sb.append('"');
            return;
        }
        if (o instanceof Boolean || o instanceof Number) {
            sb.append(o.toString());
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                esc(sb, String.valueOf(e.getKey()));
                sb.append("\":");
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                write(sb, java.lang.reflect.Array.get(o, i));
            }
            sb.append(']');
            return;
        }
        throw new IllegalArgumentException("不支持的类型: " + o.getClass());
    }

    private static void esc(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            sb.append(HEX[(c >> shift) & 0xf]);
                        }
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ---------- 解析 ----------
    public static Object parse(String s) {
        P p = new P(s);
        Object o = p.value();
        p.ws();
        if (p.pos < s.length()) throw new IllegalArgumentException("JSON 末尾存在多余内容");
        return o;
    }

    /** 把对象转成 Map(对象必须是 JSON 对象)。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }

    /** 取字符串字段,缺失返回 null。 */
    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    /** 取布尔字段,缺失返回默认值。 */
    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : def;
    }

    /** 把对象转成数组(对象必须是 JSON 数组)。 */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return (List<Object>) o; }

    private static final class P {
        final String s;
        int pos;

        P(String s) { this.s = s; }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
            char c = s.charAt(pos);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { expect("true");  return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null");  return null; }
            return number();
        }

        void expect(String w) {
            if (!s.startsWith(w, pos)) throw new IllegalArgumentException("JSON 语法错误 @ " + pos);
            pos += w.length();
        }

        Map<String, Object> object() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("期望字符串键 @ " + pos);
                }
                String key = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("期望 ':' @ " + pos);
                }
                pos++;
                m.put(key, value());
                ws();
                if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("期望 ',' 或 '}' @ " + (pos - 1));
            }
        }

        List<Object> array() {
            pos++; // [
            List<Object> l = new ArrayList<Object>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("期望 ',' 或 ']' @ " + (pos - 1));
            }
        }

        String string() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new IllegalArgumentException("字符串未闭合");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("转义不完整");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("\\u 转义不完整");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            while (pos < s.length() && "-+0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String t = s.substring(start, pos);
            if (t.length() == 0) throw new IllegalArgumentException("非法数字 @ " + start);
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                return Double.valueOf(t);
            }
            return Long.valueOf(t);
        }
    }
}
