package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Schlanker, zu org.json kompatibler JSON-Array-Baustein (siehe JSONObject). */
public class JSONArray {

    final List<Object> list = new ArrayList<>();

    public JSONArray() { }

    public JSONArray(String s) {
        JSONObject.Parser p = new JSONObject.Parser(s);
        Object v = p.value();
        p.end();
        if (!(v instanceof JSONArray)) throw new JSONException("keine Liste");
        list.addAll(((JSONArray) v).list);
    }

    public JSONArray(Collection<?> c) { if (c != null) list.addAll(c); }

    public int length() { return list.size(); }
    public Object opt(int i) { return i >= 0 && i < list.size() ? list.get(i) : null; }
    public JSONArray put(Object v) { list.add(v == null ? JSONObject.NULL : v); return this; }
    public JSONArray put(long v) { list.add(v); return this; }
    public JSONArray put(boolean v) { list.add(v); return this; }
    public Object remove(int i) { return i >= 0 && i < list.size() ? list.remove(i) : null; }

    public String optString(int i) { return optString(i, ""); }
    public String optString(int i, String def) {
        Object v = opt(i);
        if (v == null || v == JSONObject.NULL) return def;
        return v instanceof String ? (String) v : v.toString();
    }
    public int optInt(int i) { Number n = JSONObject.num(opt(i)); return n == null ? 0 : n.intValue(); }
    public long optLong(int i) { Number n = JSONObject.num(opt(i)); return n == null ? 0 : n.longValue(); }
    public boolean optBoolean(int i) { Object v = opt(i); return v instanceof Boolean && (Boolean) v; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(int i) { Object v = opt(i); return v instanceof JSONArray ? (JSONArray) v : null; }

    public Object get(int i) { Object v = opt(i); if (v == null) throw new JSONException("Index " + i); return v; }
    public String getString(int i) { Object v = get(i); if (!(v instanceof String)) throw new JSONException("kein Text bei " + i); return (String) v; }
    public int getInt(int i) { Number n = JSONObject.num(get(i)); if (n == null) throw new JSONException("keine Zahl bei " + i); return n.intValue(); }
    public long getLong(int i) { Number n = JSONObject.num(get(i)); if (n == null) throw new JSONException("keine Zahl bei " + i); return n.longValue(); }
    public JSONObject getJSONObject(int i) { Object v = get(i); if (!(v instanceof JSONObject)) throw new JSONException("kein Objekt bei " + i); return (JSONObject) v; }
    public JSONArray getJSONArray(int i) { Object v = get(i); if (!(v instanceof JSONArray)) throw new JSONException("keine Liste bei " + i); return (JSONArray) v; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        JSONObject.write(sb, this);
        return sb.toString();
    }
}
