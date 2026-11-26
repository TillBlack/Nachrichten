package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Schlanker, zu org.json kompatibler JSON-Baustein (nur die Teile, die die App braucht).
 * Unter Android ist org.json eingebaut – am PC bringt die Windows-Version diese Fassung mit.
 */
public class JSONObject {

    /** Steht für ein JSON-null. */
    public static final Object NULL = new Object() {
        @Override public String toString() { return "null"; }
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
    };

    final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() { }

    public JSONObject(String s) {
        Parser p = new Parser(s);
        Object v = p.value();
        p.end();
        if (!(v instanceof JSONObject)) throw new JSONException("kein Objekt");
        map.putAll(((JSONObject) v).map);
    }

    public int length() { return map.size(); }
    public boolean has(String k) { return map.containsKey(k); }
    public boolean isNull(String k) { Object v = map.get(k); return v == null || v == NULL; }
    public Iterator<String> keys() { return map.keySet().iterator(); }
    public Object opt(String k) { return map.get(k); }
    public Object remove(String k) { return map.remove(k); }

    public JSONObject put(String k, Object v) {
        if (k == null) throw new JSONException("Schlüssel null");
        if (v == null) map.remove(k); else map.put(k, v);
        return this;
    }
    public JSONObject put(String k, long v) { map.put(k, v); return this; }
    public JSONObject put(String k, int v) { map.put(k, (long) v); return this; }
    public JSONObject put(String k, boolean v) { map.put(k, v); return this; }
    public JSONObject put(String k, double v) { map.put(k, v); return this; }

    // ---------------------------------------------------------------- lesen (opt = mit Rückfallwert)

    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String def) {
        Object v = map.get(k);
        if (v == null || v == NULL) return def;
        return v instanceof String ? (String) v : v.toString();
    }
    public int optInt(String k) { return optInt(k, 0); }
    public int optInt(String k, int def) { Number n = num(map.get(k)); return n == null ? def : n.intValue(); }
    public long optLong(String k) { return optLong(k, 0); }
    public long optLong(String k, long def) { Number n = num(map.get(k)); return n == null ? def : n.longValue(); }
    public double optDouble(String k, double def) { Number n = num(map.get(k)); return n == null ? def : n.doubleValue(); }
    public boolean optBoolean(String k) { return optBoolean(k, false); }
    public boolean optBoolean(String k, boolean def) {
        Object v = map.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) { if ("true".equalsIgnoreCase((String) v)) return true; if ("false".equalsIgnoreCase((String) v)) return false; }
        return def;
    }
    public JSONObject optJSONObject(String k) { Object v = map.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = map.get(k); return v instanceof JSONArray ? (JSONArray) v : null; }

    // ---------------------------------------------------------------- lesen (get = Pflicht)

    public Object get(String k) { Object v = map.get(k); if (v == null) throw new JSONException("fehlt: " + k); return v; }
    public String getString(String k) { Object v = get(k); if (!(v instanceof String)) throw new JSONException("kein Text: " + k); return (String) v; }
    public int getInt(String k) { Number n = num(get(k)); if (n == null) throw new JSONException("keine Zahl: " + k); return n.intValue(); }
    public long getLong(String k) { Number n = num(get(k)); if (n == null) throw new JSONException("keine Zahl: " + k); return n.longValue(); }
    public boolean getBoolean(String k) { Object v = get(k); if (v instanceof Boolean) return (Boolean) v; throw new JSONException("kein Wahrheitswert: " + k); }
    public JSONObject getJSONObject(String k) { Object v = get(k); if (!(v instanceof JSONObject)) throw new JSONException("kein Objekt: " + k); return (JSONObject) v; }
    public JSONArray getJSONArray(String k) { Object v = get(k); if (!(v instanceof JSONArray)) throw new JSONException("keine Liste: " + k); return (JSONArray) v; }

    static Number num(Object v) {
        if (v instanceof Number) return (Number) v;
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) {
                try { return Double.parseDouble((String) v); } catch (NumberFormatException e2) { return null; }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- schreiben

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        write(sb, this);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null || v == NULL) sb.append("null");
        else if (v instanceof String) quote(sb, (String) v);
        else if (v instanceof JSONObject) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((JSONObject) v).map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof JSONArray) {
            sb.append('[');
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(','); write(sb, a.opt(i)); }
            sb.append(']');
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else quote(sb, v.toString());
    }

    public static String quote(String s) { StringBuilder sb = new StringBuilder(); quote(sb, s); return sb.toString(); }

    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- Parser

    static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            if (s == null) throw new JSONException("leer");
            this.s = s;
        }

        void end() {
            ws();
            if (i < s.length()) throw new JSONException("Zeichen nach dem Ende bei " + i);
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++; else break;
            }
        }

        Object value() {
            ws();
            if (i >= s.length()) throw new JSONException("unerwartetes Ende");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (c == 'f' && s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (c == 'n' && s.startsWith("null", i)) { i += 4; return NULL; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new JSONException("unerwartetes Zeichen '" + c + "' bei " + i);
        }

        private JSONObject object() {
            JSONObject o = new JSONObject();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return o; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new JSONException("Schlüssel erwartet bei " + i);
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new JSONException("':' erwartet bei " + i);
                i++;
                Object v = value();
                o.map.put(k, v);
                ws();
                if (i >= s.length()) throw new JSONException("unerwartetes Ende");
                char c = s.charAt(i++);
                if (c == '}') return o;
                if (c != ',') throw new JSONException("',' oder '}' erwartet bei " + (i - 1));
            }
        }

        private JSONArray array() {
            JSONArray a = new JSONArray();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.list.add(value());
                ws();
                if (i >= s.length()) throw new JSONException("unerwartetes Ende");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') throw new JSONException("',' oder ']' erwartet bei " + (i - 1));
            }
        }

        private String string() {
            i++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JSONException("Text nicht beendet");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw new JSONException("Text nicht beendet");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new JSONException("\\u unvollständig");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new JSONException("unbekanntes Escape \\" + e);
                }
            }
        }

        private Object number() {
            int st = i;
            if (s.charAt(i) == '-') i++;
            boolean frac = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { frac = true; i++; }
                else break;
            }
            String n = s.substring(st, i);
            try {
                if (!frac) return Long.parseLong(n);
                return Double.parseDouble(n);
            } catch (NumberFormatException e) {
                try { return Double.parseDouble(n); } catch (NumberFormatException e2) { throw new JSONException("ungültige Zahl " + n); }
            }
        }
    }
}
