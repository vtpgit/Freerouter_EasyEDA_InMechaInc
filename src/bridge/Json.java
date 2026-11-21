/*
 * EasyEDA to Freerouting bridge.
 * Copyright (C) 2026 Valentine Petrov, INMECHA INC.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. It comes WITHOUT ANY WARRANTY. See the file LICENSE.
 * The routing engine is Freerouting by Alfons Wirtz and the Freerouting contributors. See NOTICE.md.
 */
package bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and string escaper. No external library.
 * Objects become LinkedHashMap, arrays ArrayList, numbers Double, plus String, Boolean and null.
 */
final class Json
{
    private final String text;
    private int pos;

    private Json(String p_text)
    {
        this.text = p_text;
        this.pos = 0;
    }

    static Object parse(String p_text)
    {
        Json reader = new Json(p_text);
        reader.skip_whitespace();
        Object result = reader.read_value();
        reader.skip_whitespace();
        if (reader.pos != p_text.length())
        {
            throw new IllegalArgumentException("JSON: text after the end of the value at " + reader.pos);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse_object(String p_text)
    {
        Object result = parse(p_text);
        if (!(result instanceof Map))
        {
            throw new IllegalArgumentException("JSON: object expected");
        }
        return (Map<String, Object>) result;
    }

    private Object read_value()
    {
        if (pos >= text.length())
        {
            throw new IllegalArgumentException("JSON: unexpected end");
        }
        char c = text.charAt(pos);
        if (c == '{')
        {
            return read_object();
        }
        if (c == '[')
        {
            return read_array();
        }
        if (c == '"')
        {
            return read_string();
        }
        if (text.startsWith("true", pos))
        {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos))
        {
            pos += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos))
        {
            pos += 4;
            return null;
        }
        return read_number();
    }

    private Map<String, Object> read_object()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        ++pos; // {
        skip_whitespace();
        if (peek() == '}')
        {
            ++pos;
            return result;
        }
        for (;;)
        {
            skip_whitespace();
            if (peek() != '"')
            {
                throw new IllegalArgumentException("JSON: key expected at " + pos);
            }
            String key = read_string();
            skip_whitespace();
            expect(':');
            skip_whitespace();
            result.put(key, read_value());
            skip_whitespace();
            char c = peek();
            ++pos;
            if (c == '}')
            {
                return result;
            }
            if (c != ',')
            {
                throw new IllegalArgumentException("JSON: , or } expected at " + (pos - 1));
            }
        }
    }

    private List<Object> read_array()
    {
        List<Object> result = new ArrayList<Object>();
        ++pos; // [
        skip_whitespace();
        if (peek() == ']')
        {
            ++pos;
            return result;
        }
        for (;;)
        {
            skip_whitespace();
            result.add(read_value());
            skip_whitespace();
            char c = peek();
            ++pos;
            if (c == ']')
            {
                return result;
            }
            if (c != ',')
            {
                throw new IllegalArgumentException("JSON: , or ] expected at " + (pos - 1));
            }
        }
    }

    private String read_string()
    {
        ++pos; // opening quote
        // Fast path: no escape in the string.
        int end = pos;
        while (end < text.length())
        {
            char c = text.charAt(end);
            if (c == '"')
            {
                String result = text.substring(pos, end);
                pos = end + 1;
                return result;
            }
            if (c == '\\')
            {
                break;
            }
            ++end;
        }
        StringBuilder sb = new StringBuilder(Math.max(16, text.length() - pos < 1024 ? 64 : (text.length() - pos)));
        sb.append(text, pos, end);
        pos = end;
        while (pos < text.length())
        {
            char c = text.charAt(pos++);
            if (c == '"')
            {
                return sb.toString();
            }
            if (c != '\\')
            {
                sb.append(c);
                continue;
            }
            if (pos >= text.length())
            {
                break;
            }
            char e = text.charAt(pos++);
            switch (e)
            {
                case 'n':
                    sb.append('\n');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'u':
                    if (pos + 4 > text.length())
                    {
                        throw new IllegalArgumentException("JSON: bad \\u escape");
                    }
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    sb.append(e); // \" \\ \/
            }
        }
        throw new IllegalArgumentException("JSON: string not closed");
    }

    private Double read_number()
    {
        int start = pos;
        while (pos < text.length())
        {
            char c = text.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')
            {
                ++pos;
            }
            else
            {
                break;
            }
        }
        if (start == pos)
        {
            throw new IllegalArgumentException("JSON: value expected at " + pos);
        }
        return Double.valueOf(text.substring(start, pos));
    }

    private char peek()
    {
        if (pos >= text.length())
        {
            throw new IllegalArgumentException("JSON: unexpected end");
        }
        return text.charAt(pos);
    }

    private void expect(char p_c)
    {
        if (peek() != p_c)
        {
            throw new IllegalArgumentException("JSON: " + p_c + " expected at " + pos);
        }
        ++pos;
    }

    private void skip_whitespace()
    {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos)))
        {
            ++pos;
        }
    }

    /** Appends p_value as a JSON string with quotes. */
    static void append_string(StringBuilder p_sb, String p_value)
    {
        p_sb.append('"');
        for (int i = 0; i < p_value.length(); ++i)
        {
            char c = p_value.charAt(i);
            switch (c)
            {
                case '"':
                    p_sb.append("\\\"");
                    break;
                case '\\':
                    p_sb.append("\\\\");
                    break;
                case '\n':
                    p_sb.append("\\n");
                    break;
                case '\r':
                    p_sb.append("\\r");
                    break;
                case '\t':
                    p_sb.append("\\t");
                    break;
                default:
                    if (c < 0x20)
                    {
                        p_sb.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        p_sb.append(c);
                    }
            }
        }
        p_sb.append('"');
    }

    /** Number with at most 4 decimals and no trailing zeros. */
    static void append_number(StringBuilder p_sb, double p_value)
    {
        if (Double.isNaN(p_value) || Double.isInfinite(p_value))
        {
            p_sb.append('0');
            return;
        }
        long scaled = Math.round(p_value * 10000.0);
        if (scaled < 0)
        {
            p_sb.append('-');
            scaled = -scaled;
        }
        long whole = scaled / 10000;
        long frac = scaled % 10000;
        p_sb.append(whole);
        if (frac != 0)
        {
            String digits = String.valueOf(10000 + frac).substring(1);
            int end = digits.length();
            while (end > 0 && digits.charAt(end - 1) == '0')
            {
                --end;
            }
            p_sb.append('.').append(digits, 0, end);
        }
    }

    static int as_int(Object p_value, int p_default)
    {
        if (p_value instanceof Number)
        {
            return ((Number) p_value).intValue();
        }
        return p_default;
    }

    static boolean as_bool(Object p_value, boolean p_default)
    {
        if (p_value instanceof Boolean)
        {
            return ((Boolean) p_value).booleanValue();
        }
        return p_default;
    }

    static String as_string(Object p_value, String p_default)
    {
        if (p_value instanceof String)
        {
            return (String) p_value;
        }
        return p_default;
    }
}
