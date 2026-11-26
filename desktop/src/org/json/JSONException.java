package org.json;

/** Minimal-Ersatz für org.json (unter Android eingebaut, am PC nicht vorhanden). */
public class JSONException extends RuntimeException {
    public JSONException(String msg) { super(msg); }
}
