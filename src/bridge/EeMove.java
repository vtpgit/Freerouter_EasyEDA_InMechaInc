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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Move of a part (a footprint record) of an EasyEDA board file.
 *
 * A move is a shift, with or without a turn by 180 degrees about a centre. Both keep each axis apart:
 * x' = sx * x + tx and y' = sy * y + ty with sx = sy = 1 (shift) or -1 (turn). So every coordinate of the record
 * can be changed alone, and a rectangle stays a rectangle. A turn by 90 degrees is not made.
 *
 * All shapes of the record move: pads, holes, copper, silk, texts and the outline of the 3D model. A record with a
 * shape kind that is not in the list below is not moved (see Part.fixed_reason).
 */
final class EeMove
{
    static final class Unsupported extends Exception
    {
        private static final long serialVersionUID = 1L;

        Unsupported(String p_text)
        {
            super(p_text);
        }
    }

    /** A footprint of the board. */
    static final class Part
    {
        String ref = "";
        /** Index of the record in the shape array. */
        int record;
        /** Text of the package attribute, for example R0201. */
        String pack = "";
        final List<EeBoard.Pad> pads = new ArrayList<EeBoard.Pad>();
        /** Box of the pads in mm. */
        double px0 = Double.MAX_VALUE, py0 = Double.MAX_VALUE, px1 = -Double.MAX_VALUE, py1 = -Double.MAX_VALUE;
        /** Box of the body in mm: the pads and the component shape of the footprint. */
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        /** Not null: the record has something that the bridge does not move. */
        String fixed_reason;

        double cx()
        {
            return (px0 + px1) / 2.0;
        }

        double cy()
        {
            return (py0 + py1) / 2.0;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
    private static final Pattern PATH_TOKEN = Pattern.compile("[A-Za-z]|-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
    /** Layer of the component shape of a footprint. */
    private static final String BODY_LAYER = "99";

    private final double sx, tx, sy, ty;
    private final boolean turn;

    /** Shift in mm and centre of the turn in mm. The record holds file units. */
    private EeMove(EeBoard p_board, double p_dx_mm, double p_dy_mm, boolean p_turn, double p_cx_mm, double p_cy_mm)
    {
        double dx = p_dx_mm / EeBoard.UNIT_MM, dy = p_dy_mm / EeBoard.UNIT_MM;
        double cx = p_board.origin_x + p_cx_mm / EeBoard.UNIT_MM, cy = p_board.origin_y + p_cy_mm / EeBoard.UNIT_MM;
        turn = p_turn;
        sx = p_turn ? -1.0 : 1.0;
        sy = sx;
        tx = p_turn ? 2.0 * cx + dx : dx;
        ty = p_turn ? 2.0 * cy + dy : dy;
    }

    private String fx(String p_value) throws Unsupported
    {
        return EeResult.unit(sx * number(p_value) + tx);
    }

    private String fy(String p_value) throws Unsupported
    {
        return EeResult.unit(sy * number(p_value) + ty);
    }

    private static double number(String p_value) throws Unsupported
    {
        try
        {
            return Double.parseDouble(p_value.trim());
        }
        catch (NumberFormatException e)
        {
            throw new Unsupported("a coordinate that is not a number: " + p_value);
        }
    }

    /** A list of numbers x y x y ... with any separators. */
    private String points(String p_list)
    {
        Matcher m = NUMBER.matcher(p_list);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean is_x = true;
        while (m.find())
        {
            sb.append(p_list, last, m.start());
            double v = Double.parseDouble(m.group());
            sb.append(EeResult.unit(is_x ? sx * v + tx : sy * v + ty));
            is_x = !is_x;
            last = m.end();
        }
        sb.append(p_list, last, p_list.length());
        return sb.toString();
    }

    /** A path with the commands M L H V C S Q T A Z in absolute form. */
    private String path(String p_path) throws Unsupported
    {
        Matcher m = PATH_TOKEN.matcher(p_path);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        char command = ' ';
        int index = 0;
        while (m.find())
        {
            sb.append(p_path, last, m.start());
            last = m.end();
            String token = m.group();
            char c = token.charAt(0);
            if (Character.isLetter(c) && token.length() == 1)
            {
                if ("MLHVCSQTAZz".indexOf(c) < 0)
                {
                    throw new Unsupported("a path with the command " + c);
                }
                command = c;
                index = 0;
                sb.append(token);
                continue;
            }
            double v = Double.parseDouble(token);
            boolean is_x, is_y;
            switch (command)
            {
                case 'H':
                    is_x = true;
                    is_y = false;
                    break;
                case 'V':
                    is_x = false;
                    is_y = true;
                    break;
                case 'A':
                    is_x = index % 7 == 5;
                    is_y = index % 7 == 6;
                    break;
                case 'M':
                case 'L':
                case 'C':
                case 'S':
                case 'Q':
                case 'T':
                    is_x = index % 2 == 0;
                    is_y = !is_x;
                    break;
                default:
                    throw new Unsupported("a path with a number after the command " + command);
            }
            ++index;
            sb.append(is_x ? EeResult.unit(sx * v + tx) : is_y ? EeResult.unit(sy * v + ty) : token);
        }
        sb.append(p_path, last, p_path.length());
        return sb.toString();
    }

    private String angle(String p_value)
    {
        if (!turn)
        {
            return p_value;
        }
        double a;
        try
        {
            a = p_value.trim().isEmpty() ? 0.0 : Double.parseDouble(p_value.trim());
        }
        catch (NumberFormatException e)
        {
            return p_value;
        }
        a = ((a + 180.0) % 360.0 + 360.0) % 360.0;
        return a == Math.rint(a) ? String.valueOf((long) a) : EeResult.unit(a);
    }

    /** The outline of the 3D model: a JSON object with a centre and lists of points. */
    private String svg_node(String p_json)
    {
        String result = replace_value(p_json, "c_origin", 0);
        result = replace_value(result, "points", 0);
        if (turn)
        {
            result = replace_value(result, "c_rotation", 1);
        }
        return result;
    }

    /** p_mode 0: the value is a list of points. 1: the value is "a,b,angle" and the angle turns. */
    private String replace_value(String p_json, String p_key, int p_mode)
    {
        Matcher m = Pattern.compile("\"" + p_key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(p_json);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find())
        {
            sb.append(p_json, last, m.start(1));
            String value = m.group(1);
            if (p_mode == 0)
            {
                sb.append(points(value));
            }
            else
            {
                String[] f = value.split(",", -1);
                if (f.length == 3)
                {
                    f[2] = angle(f[2]);
                    sb.append(String.join(",", f));
                }
                else
                {
                    sb.append(value);
                }
            }
            last = m.end(1);
        }
        sb.append(p_json, last, p_json.length());
        return sb.toString();
    }

    private String shape(String p_shape) throws Unsupported
    {
        if (p_shape.startsWith("SVGNODE~"))
        {
            return "SVGNODE~" + svg_node(p_shape.substring(8));
        }
        String[] f = p_shape.split("~", -1);
        String kind = f[0];
        if (kind.equals("PAD"))
        {
            need(f, 13, kind);
            f[2] = fx(f[2]);
            f[3] = fy(f[3]);
            f[10] = points(f[10]);
            f[11] = angle(f[11]);
            if (f.length > 14)
            {
                f[14] = points(f[14]);
            }
            if (f.length > 19)
            {
                f[19] = points(f[19]);
            }
        }
        else if (kind.equals("TEXT"))
        {
            need(f, 12, kind);
            f[2] = fx(f[2]);
            f[3] = fy(f[3]);
            f[5] = angle(f[5]);
            f[11] = path(f[11]);
        }
        else if (kind.equals("SOLIDREGION"))
        {
            need(f, 4, kind);
            f[3] = path(f[3]);
        }
        else if (kind.equals("TRACK"))
        {
            need(f, 5, kind);
            f[4] = points(f[4]);
        }
        else if (kind.equals("ARC"))
        {
            need(f, 5, kind);
            f[4] = path(f[4]);
        }
        else if (kind.equals("CIRCLE") || kind.equals("HOLE") || kind.equals("VIA"))
        {
            need(f, 3, kind);
            f[1] = fx(f[1]);
            f[2] = fy(f[2]);
        }
        else if (kind.equals("RECT"))
        {
            need(f, 5, kind);
            double x = number(f[1]), y = number(f[2]), w = number(f[3]), h = number(f[4]);
            // the corner with the smallest x and y after the move
            f[1] = EeResult.unit(turn ? sx * (x + w) + tx : x + tx);
            f[2] = EeResult.unit(turn ? sy * (y + h) + ty : y + ty);
        }
        else
        {
            throw new Unsupported("a shape of the kind " + kind);
        }
        return String.join("~", f);
    }

    private static void need(String[] p_fields, int p_count, String p_kind) throws Unsupported
    {
        if (p_fields.length < p_count)
        {
            throw new Unsupported("a " + p_kind + " shape with " + p_fields.length + " fields");
        }
    }

    private static final java.util.Set<String> KNOWN = new java.util.HashSet<String>(
            java.util.Arrays.asList("PAD", "TEXT", "SOLIDREGION", "TRACK", "ARC", "CIRCLE", "HOLE", "VIA", "RECT", "SVGNODE"));

    /**
     * The footprint record after a shift in mm and, with p_turn, a turn by 180 degrees about the centre in mm.
     *
     * @throws Unsupported when the record holds something that cannot be moved
     */
    static String moved_record(EeBoard p_board, String p_record, double p_dx_mm, double p_dy_mm, boolean p_turn, double p_cx_mm, double p_cy_mm) throws Unsupported
    {
        EeMove move = new EeMove(p_board, p_dx_mm, p_dy_mm, p_turn, p_cx_mm, p_cy_mm);
        String[] shapes = p_record.split("#@\\$", -1);
        String[] head = shapes[0].split("~", -1);
        if (!head[0].equals("LIB") || head.length < 5)
        {
            throw new Unsupported("a record that is not a footprint");
        }
        head[1] = move.fx(head[1]);
        head[2] = move.fy(head[2]);
        head[4] = move.angle(head[4]);
        StringBuilder sb = new StringBuilder(String.join("~", head));
        for (int i = 1; i < shapes.length; ++i)
        {
            sb.append("#@$").append(shapes[i].isEmpty() ? "" : move.shape(shapes[i]));
        }
        return sb.toString();
    }

    /** The text of the board with other records at the given indexes. */
    static String with_records(EeBoard p_board, Map<Integer, String> p_records)
    {
        StringBuilder sb = new StringBuilder(p_board.text.length() + 1024);
        sb.append(p_board.text, 0, p_board.shape_start + 1);
        for (int i = 0; i < p_board.records.size(); ++i)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            String changed = p_records.get(i);
            if (changed != null)
            {
                Json.append_string(sb, changed);
            }
            else
            {
                int[] span = p_board.record_spans.get(i);
                sb.append(p_board.text, span[0], span[1]);
            }
        }
        sb.append(p_board.text, p_board.shape_end, p_board.text.length());
        return sb.toString();
    }

    /** The parts of the board by reference. A reference that occurs two times is left out. */
    static Map<String, Part> parts(EeBoard p_board)
    {
        Map<String, Part> result = new LinkedHashMap<String, Part>();
        java.util.Set<String> twice = new java.util.HashSet<String>();
        Map<String, List<EeBoard.Pad>> pads_by_ref = new LinkedHashMap<String, List<EeBoard.Pad>>();
        for (EeBoard.Pad pad : p_board.pads)
        {
            pads_by_ref.computeIfAbsent(pad.ref, k -> new ArrayList<EeBoard.Pad>()).add(pad);
        }
        for (int r = 0; r < p_board.records.size(); ++r)
        {
            String record = p_board.records.get(r);
            if (!record.startsWith("LIB~"))
            {
                continue;
            }
            String[] shapes = record.split("#@\\$", -1);
            Part part = new Part();
            part.record = r;
            String[] head = shapes[0].split("~", -1);
            if (head.length > 3)
            {
                String[] attrs = head[3].split("`", -1);
                for (int i = 0; i + 1 < attrs.length; i += 2)
                {
                    if (attrs[i].equals("package"))
                    {
                        part.pack = attrs[i + 1];
                    }
                }
            }
            for (int i = 1; i < shapes.length; ++i)
            {
                String shape = shapes[i];
                if (shape.isEmpty())
                {
                    continue;
                }
                int tilde = shape.indexOf('~');
                String kind = tilde < 0 ? shape : shape.substring(0, tilde);
                if (!KNOWN.contains(kind) && part.fixed_reason == null)
                {
                    part.fixed_reason = "it has a shape of the kind " + kind;
                }
                if (shape.startsWith("TEXT~P~"))
                {
                    String[] f = shape.split("~", -1);
                    if (f.length > 10)
                    {
                        part.ref = f[10];
                    }
                }
                else if (shape.startsWith("SOLIDREGION~" + BODY_LAYER + "~"))
                {
                    String[] f = shape.split("~", -1);
                    Matcher m = NUMBER.matcher(f.length > 3 ? f[3] : "");
                    boolean is_x = true;
                    while (m.find())
                    {
                        double v = Double.parseDouble(m.group());
                        if (is_x)
                        {
                            double x = (v - p_board.origin_x) * EeBoard.UNIT_MM;
                            part.x0 = Math.min(part.x0, x);
                            part.x1 = Math.max(part.x1, x);
                        }
                        else
                        {
                            double y = (v - p_board.origin_y) * EeBoard.UNIT_MM;
                            part.y0 = Math.min(part.y0, y);
                            part.y1 = Math.max(part.y1, y);
                        }
                        is_x = !is_x;
                    }
                }
            }
            if (part.ref.isEmpty())
            {
                continue;
            }
            if (result.containsKey(part.ref) || twice.contains(part.ref))
            {
                result.remove(part.ref);
                twice.add(part.ref);
                continue;
            }
            List<EeBoard.Pad> pads = pads_by_ref.get(part.ref);
            if (pads == null || pads.isEmpty())
            {
                continue;
            }
            part.pads.addAll(pads);
            for (EeBoard.Pad pad : pads)
            {
                double[] b = pad.box();
                part.px0 = Math.min(part.px0, b[0]);
                part.py0 = Math.min(part.py0, b[1]);
                part.px1 = Math.max(part.px1, b[2]);
                part.py1 = Math.max(part.py1, b[3]);
            }
            part.x0 = Math.min(part.x0, part.px0);
            part.y0 = Math.min(part.y0, part.py0);
            part.x1 = Math.max(part.x1, part.px1);
            part.y1 = Math.max(part.y1, part.py1);
            result.put(part.ref, part);
        }
        return result;
    }

    static String mm(double p_value)
    {
        return String.format(Locale.ROOT, "%.3f", p_value);
    }
}
