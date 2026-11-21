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
 * A PCB of EasyEDA Standard, read from its JSON source.
 *
 * Lengths are millimetres from the canvas origin of the file. The y axis points down, as in the editor.
 * The file text is kept. The writer changes only the "shape" array and leaves all other text as it is.
 *
 * Record formats: see the format document of EasyEDA and the reference file of editor version 6.5.61.
 */
final class EeBoard
{
    /** One length unit of the file is 10 mil. */
    static final double UNIT_MM = 0.254;
    private static final String MULTI_LAYER = "11";
    private static final String OUTLINE_LAYER = "10";

    static final class Pad
    {
        int index;
        String id;
        String ref;
        String number;
        String net;
        /** true: on all copper layers. */
        boolean through;
        /** Layer id of a surface-mount pad. */
        String layer;
        double cx, cy;
        /** Circle: radius above 0 and no polygon. */
        double radius;
        /** Outline points x0 y0 x1 y1 ... */
        double[] polygon;
        /** A via inside a footprint, for example a thermal via. */
        boolean footprint_via;

        double[] box()
        {
            if (polygon == null)
            {
                return new double[]
                {
                    cx - radius, cy - radius, cx + radius, cy + radius
                };
            }
            double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < polygon.length; i += 2)
            {
                x0 = Math.min(x0, polygon[i]);
                x1 = Math.max(x1, polygon[i]);
                y0 = Math.min(y0, polygon[i + 1]);
                y1 = Math.max(y1, polygon[i + 1]);
            }
            return new double[]
            {
                x0, y0, x1, y1
            };
        }
    }

    static final class Track
    {
        String layer;
        String net;
        double width;
        double[] points;
        boolean locked;
        /** Index of the record in the shape array, -1 for copper inside a footprint. */
        int record = -1;
        /** Part of a track inside a footprint. Empty for a track of the board. */
        String ref = "";
    }

    static final class Via
    {
        double x, y, diameter, hole;
        String net;
        boolean locked;
        int record = -1;
    }

    static final class Region
    {
        String layer;
        String net;
        String ref;
        double[] points;
        int record = -1;
        /** Copper area: its clearance to copper of other nets in mm. 0: not given. */
        double clearance = 0.0;
    }

    static final class Hole
    {
        double x, y, radius;
    }

    final String text;
    /** Index of '[' and of ']' of the shape array in the text. */
    int shape_start = -1;
    int shape_end = -1;
    /** The records of the shape array. */
    final List<String> records = new ArrayList<String>();
    /** For each record the start and the end (exclusive) of its string literal in the text, quotes included. */
    final List<int[]> record_spans = new ArrayList<int[]>();

    double origin_x;
    double origin_y;
    /** Copper layer ids in physical order, top to bottom: 1, 21, 22, ..., 2. */
    final List<String> layers = new ArrayList<String>();
    double track_mm;
    double clearance_mm;
    double via_mm;
    double via_hole_mm;
    /**
     * The two rule blocks of the file, each as track width, clearance, via diameter and via hole in mm. 0: the block
     * has no value. design_rules: the values of the Design Rule dialog (DRCRULE, Default). router_rules: the values
     * of the Auto Router dialog, which the editor stores in the file (routerRule).
     */
    final double[] design_rules = new double[4];
    final double[] router_rules = new double[4];
    /** true: the design rules come first and the Auto Router values fill the gaps. false: the other order. */
    final boolean design_rules_first;
    final List<String> skip_nets = new ArrayList<String>();
    /** Net name to {width, clearance} of the special nets of the editor, in mm. */
    final Map<String, double[]> special_nets = new LinkedHashMap<String, double[]>();

    final List<Pad> pads = new ArrayList<Pad>();
    final List<Hole> holes = new ArrayList<Hole>();
    /** Copper regions inside footprints. */
    final List<Region> solids = new ArrayList<Region>();
    /** Copper tracks inside footprints. */
    final List<Track> footprint_tracks = new ArrayList<Track>();
    /** Tracks and vias of the board. */
    final List<Track> tracks = new ArrayList<Track>();
    final List<Via> vias = new ArrayList<Via>();
    final List<Region> areas = new ArrayList<Region>();
    /** Board outline, x0 y0 x1 y1 ... */
    double[] outline;
    final List<String> notes = new ArrayList<String>();
    int part_count = 0;

    EeBoard(String p_text)
    {
        this(p_text, true);
    }

    /**
     * @param p_design_rules_first true: track width, clearance and via size come from the design rules of the file.
     *                             false: from the Auto Router values of the file (the order up to version 1.2.6).
     */
    EeBoard(String p_text, boolean p_design_rules_first)
    {
        this.text = p_text;
        this.design_rules_first = p_design_rules_first;
        Map<String, Object> root = Json.parse_object(p_text);
        Object head = root.get("head");
        if (!(head instanceof Map) || !"3".equals(Json.as_string(((Map<?, ?>) head).get("docType"), "")))
        {
            throw new IllegalArgumentException("This is not a PCB file of EasyEDA Standard (docType 3).");
        }
        origin_x = number(((Map<?, ?>) head).get("x"));
        origin_y = number(((Map<?, ?>) head).get("y"));
        read_rules(root);
        find_shape_array();
        if (shape_start < 0)
        {
            throw new IllegalArgumentException("The file has no shape array.");
        }
        for (int i = 0; i < records.size(); ++i)
        {
            read_record(records.get(i), i);
        }
        if (outline == null)
        {
            throw new IllegalArgumentException("The file has no board outline (a closed track on layer 10).");
        }
    }

    // ------------------------------------------------------------------ rules

    private static double number(Object p_value)
    {
        if (p_value instanceof Number)
        {
            return ((Number) p_value).doubleValue();
        }
        if (p_value instanceof String)
        {
            String s = ((String) p_value).trim();
            if (s.endsWith("px"))
            {
                s = s.substring(0, s.length() - 2);
            }
            try
            {
                return Double.parseDouble(s);
            }
            catch (NumberFormatException e)
            {
                return 0.0;
            }
        }
        return 0.0;
    }

    private void read_rules(Map<String, Object> p_root)
    {
        Object router = p_root.get("routerRule");
        Object drc = p_root.get("DRCRULE");
        List<String> inner = new ArrayList<String>();
        boolean has_bottom = true;
        if (router instanceof Map)
        {
            Map<?, ?> rule = (Map<?, ?>) router;
            String unit = Json.as_string(rule.get("unit"), "mm");
            double factor = unit.equals("mm") ? 1.0 : unit.equals("mil") ? 0.0254 : unit.equals("inch") ? 25.4 : UNIT_MM;
            router_rules[0] = number(rule.get("trackWidth")) * factor;
            router_rules[1] = number(rule.get("trackClearance")) * factor;
            router_rules[2] = number(rule.get("viaDiameter")) * factor;
            router_rules[3] = number(rule.get("viaHoleD")) * factor;
            Object layer_list = rule.get("routerLayers");
            if (layer_list instanceof List)
            {
                has_bottom = false;
                for (Object layer : (List<?>) layer_list)
                {
                    String id = layer instanceof Number ? String.valueOf(((Number) layer).intValue()) : String.valueOf(layer);
                    if (id.equals("2"))
                    {
                        has_bottom = true;
                    }
                    else if (!id.equals("1"))
                    {
                        inner.add(id);
                    }
                }
            }
            Object skip = rule.get("skipNets");
            if (skip instanceof List)
            {
                for (Object net : (List<?>) skip)
                {
                    skip_nets.add(String.valueOf(net));
                }
            }
            Object special = rule.get("specialNets");
            if (special instanceof List)
            {
                for (Object item : (List<?>) special)
                {
                    if (item instanceof Map)
                    {
                        Map<?, ?> map = (Map<?, ?>) item;
                        // The special nets are stored in file units with the suffix px.
                        double width = number(map.get("width")) * UNIT_MM;
                        double clearance = number(map.get("clearance")) * UNIT_MM;
                        // The editor stores an entry for each net of the board, with the values that the Auto Router dialog had.
                        // With the design rules first, such an entry is a copy of old values and is not read. An entry with
                        // other values than the Auto Router values is a rule that the user made for the net. It is read.
                        boolean copy = Math.abs(width - router_rules[0]) < 1e-4 && Math.abs(clearance - router_rules[1]) < 1e-4;
                        if (!design_rules_first || !copy)
                        {
                            special_nets.put(Json.as_string(map.get("net"), ""), new double[]
                            {
                                width, clearance
                            });
                        }
                    }
                }
            }
        }
        if (drc instanceof Map && ((Map<?, ?>) drc).get("Default") instanceof Map)
        {
            Map<?, ?> rule = (Map<?, ?>) ((Map<?, ?>) drc).get("Default");
            // DRCRULE is in file units. viaHoleDiameter is the copper diameter, viaHoleD is the hole.
            design_rules[0] = number(rule.get("trackWidth")) * UNIT_MM;
            design_rules[1] = number(rule.get("clearance")) * UNIT_MM;
            design_rules[2] = number(rule.get("viaHoleDiameter")) * UNIT_MM;
            design_rules[3] = number(rule.get("viaHoleD")) * UNIT_MM;
        }
        double[] first = design_rules_first ? design_rules : router_rules;
        double[] second = design_rules_first ? router_rules : design_rules;
        track_mm = first[0] > 0 ? first[0] : second[0];
        clearance_mm = first[1] > 0 ? first[1] : second[1];
        // the diameter and the hole of the via come from the same block
        boolean via_first = first[2] > 0;
        via_mm = via_first ? first[2] : second[2];
        via_hole_mm = via_first ? first[3] : second[3];
        if (track_mm <= 0 || clearance_mm <= 0 || via_mm <= 0)
        {
            throw new IllegalArgumentException("The file has no usable rules (routerRule or DRCRULE).");
        }
        if (via_hole_mm <= 0 || via_hole_mm >= via_mm)
        {
            via_hole_mm = via_mm / 2.0;
        }
        java.util.Collections.sort(inner, (a, b) -> Integer.compare(parse_int(a), parse_int(b)));
        layers.add("1");
        layers.addAll(inner);
        if (has_bottom)
        {
            layers.add("2");
        }
    }

    private static int parse_int(String p_text)
    {
        try
        {
            return Integer.parseInt(p_text.trim());
        }
        catch (NumberFormatException e)
        {
            return Integer.MAX_VALUE;
        }
    }

    /** top, inner1, inner2, ..., bottom for a layer id. */
    String layer_name(String p_id)
    {
        if (p_id.equals("1"))
        {
            return "top";
        }
        if (p_id.equals("2"))
        {
            return "bottom";
        }
        int n = parse_int(p_id);
        return n >= 21 && n <= 52 ? "inner" + (n - 20) : p_id;
    }

    /** Layer id for top, bottom, inner1, ... or for the id itself. Null if the board does not have the layer. */
    String layer_id(String p_name)
    {
        for (String id : layers)
        {
            if (id.equals(p_name) || layer_name(id).equals(p_name))
            {
                return id;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ text scan

    /** Finds the shape array of the top-level object and the string literal of each record. */
    private void find_shape_array()
    {
        int n = text.length();
        int i = 0;
        int depth = 0;
        while (i < n)
        {
            char c = text.charAt(i);
            if (c == '"')
            {
                int end = skip_string(i);
                if (depth == 1 && text.regionMatches(i, "\"shape\"", 0, 7))
                {
                    int j = end;
                    while (j < n && Character.isWhitespace(text.charAt(j)))
                    {
                        ++j;
                    }
                    if (j < n && text.charAt(j) == ':')
                    {
                        ++j;
                        while (j < n && Character.isWhitespace(text.charAt(j)))
                        {
                            ++j;
                        }
                        if (j < n && text.charAt(j) == '[')
                        {
                            read_array(j);
                            return;
                        }
                    }
                }
                i = end;
            }
            else
            {
                if (c == '{' || c == '[')
                {
                    ++depth;
                }
                else if (c == '}' || c == ']')
                {
                    --depth;
                }
                ++i;
            }
        }
    }

    /** Index after the closing quote of the string that starts at p_start. */
    private int skip_string(int p_start)
    {
        int i = p_start + 1;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '\\')
            {
                i += 2;
            }
            else if (c == '"')
            {
                return i + 1;
            }
            else
            {
                ++i;
            }
        }
        throw new IllegalArgumentException("The file ends inside a string.");
    }

    private void read_array(int p_open)
    {
        shape_start = p_open;
        int i = p_open + 1;
        while (i < text.length())
        {
            char c = text.charAt(i);
            if (c == '"')
            {
                int end = skip_string(i);
                record_spans.add(new int[]
                {
                    i, end
                });
                records.add((String) Json.parse(text.substring(i, end)));
                i = end;
            }
            else if (c == ']')
            {
                shape_end = i;
                return;
            }
            else if (c == ',' || Character.isWhitespace(c))
            {
                ++i;
            }
            else
            {
                throw new IllegalArgumentException("The shape array holds something that is not a text record.");
            }
        }
        throw new IllegalArgumentException("The shape array has no end.");
    }

    // ------------------------------------------------------------------ records

    private double mm_x(String p_value)
    {
        return (parse(p_value) - origin_x) * UNIT_MM;
    }

    private double mm_y(String p_value)
    {
        return (parse(p_value) - origin_y) * UNIT_MM;
    }

    private static double parse(String p_value)
    {
        try
        {
            return Double.parseDouble(p_value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0.0;
        }
    }

    private double[] points_mm(String p_list)
    {
        String[] tokens = p_list.trim().replace(',', ' ').split("\\s+");
        int count = tokens.length / 2;
        double[] result = new double[count * 2];
        for (int i = 0; i < count; ++i)
        {
            result[2 * i] = mm_x(tokens[2 * i]);
            result[2 * i + 1] = mm_y(tokens[2 * i + 1]);
        }
        return result;
    }

    /** Points of an SVG path with M, L, Z. An arc (A) is taken as a line to its end point. */
    private double[] path_mm(String p_path)
    {
        List<Double> values = new ArrayList<Double>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[MLAZmlaz]|-?\\d+(?:\\.\\d+)?(?:[eE]-?\\d+)?").matcher(p_path);
        List<String> tokens = new ArrayList<String>();
        while (m.find())
        {
            tokens.add(m.group());
        }
        char command = ' ';
        int i = 0;
        boolean arc = false;
        while (i < tokens.size())
        {
            String token = tokens.get(i);
            if (token.length() == 1 && Character.isLetter(token.charAt(0)))
            {
                command = Character.toUpperCase(token.charAt(0));
                ++i;
                continue;
            }
            if ((command == 'M' || command == 'L') && i + 1 < tokens.size())
            {
                values.add(mm_x(tokens.get(i)));
                values.add(mm_y(tokens.get(i + 1)));
                i += 2;
            }
            else if (command == 'A' && i + 6 < tokens.size())
            {
                arc = true;
                values.add(mm_x(tokens.get(i + 5)));
                values.add(mm_y(tokens.get(i + 6)));
                i += 7;
            }
            else
            {
                ++i;
            }
        }
        if (arc)
        {
            notes.add("An outline with an arc was read with straight lines.");
        }
        // drop a closing point that repeats the first point
        int size = values.size();
        if (size >= 4 && Math.abs(values.get(0) - values.get(size - 2)) < 1e-9 && Math.abs(values.get(1) - values.get(size - 1)) < 1e-9)
        {
            size -= 2;
        }
        double[] result = new double[size];
        for (int k = 0; k < size; ++k)
        {
            result[k] = values.get(k);
        }
        return result;
    }

    private boolean is_copper(String p_layer)
    {
        return layers.contains(p_layer);
    }

    private void read_record(String p_record, int p_index)
    {
        if (p_record.startsWith("LIB~"))
        {
            read_footprint(p_record);
        }
        else if (p_record.startsWith("TRACK~"))
        {
            String[] f = p_record.split("~", -1);
            if (f.length < 5)
            {
                return;
            }
            double[] points = points_mm(f[4]);
            if (f[2].equals(OUTLINE_LAYER))
            {
                if (points.length >= 6 && (outline == null || points.length > outline.length))
                {
                    int size = points.length;
                    if (Math.abs(points[0] - points[size - 2]) < 1e-9 && Math.abs(points[1] - points[size - 1]) < 1e-9)
                    {
                        size -= 2;
                    }
                    outline = java.util.Arrays.copyOf(points, size);
                }
            }
            else if (is_copper(f[2]) && points.length >= 4)
            {
                Track track = new Track();
                track.width = parse(f[1]) * UNIT_MM;
                track.layer = f[2];
                track.net = f[3];
                track.points = points;
                track.locked = f.length > 6 && f[6].equals("1");
                track.record = p_index;
                tracks.add(track);
            }
        }
        else if (p_record.startsWith("VIA~"))
        {
            String[] f = p_record.split("~", -1);
            if (f.length < 6)
            {
                return;
            }
            Via via = new Via();
            via.x = mm_x(f[1]);
            via.y = mm_y(f[2]);
            via.diameter = parse(f[3]) * UNIT_MM;
            via.net = f[4];
            via.hole = parse(f[5]) * 2.0 * UNIT_MM;
            via.locked = f.length > 7 && f[7].equals("1");
            via.record = p_index;
            vias.add(via);
        }
        else if (p_record.startsWith("COPPERAREA~"))
        {
            String[] f = p_record.split("~", -1);
            if (f.length < 5 || !is_copper(f[2]))
            {
                return;
            }
            Region area = new Region();
            area.layer = f[2];
            area.net = f[3];
            area.points = path_mm(f[4]);
            area.record = p_index;
            if (f.length > 5)
            {
                try
                {
                    area.clearance = Math.max(0.0, Double.parseDouble(f[5].trim()) * UNIT_MM);
                }
                catch (NumberFormatException e)
                {
                    area.clearance = 0.0;
                }
            }
            if (area.points.length >= 6)
            {
                areas.add(area);
            }
        }
        else if (p_record.startsWith("HOLE~"))
        {
            String[] f = p_record.split("~", -1);
            if (f.length >= 4)
            {
                Hole hole = new Hole();
                hole.x = mm_x(f[1]);
                hole.y = mm_y(f[2]);
                hole.radius = parse(f[3]) * UNIT_MM;
                holes.add(hole);
            }
        }
        else if (p_record.startsWith("SOLIDREGION~"))
        {
            read_solid(p_record.split("~", -1), "");
        }
        else if (p_record.startsWith("PAD~"))
        {
            read_pad(p_record.split("~", -1), "");
        }
        else if (p_record.startsWith("ARC~") || p_record.startsWith("CIRCLE~") || p_record.startsWith("RECT~") || p_record.startsWith("TEXT~"))
        {
            String[] f = p_record.split("~", -1);
            String layer = p_record.startsWith("CIRCLE~") && f.length > 5 ? f[5] : p_record.startsWith("TEXT~") && f.length > 7 ? f[7] : f.length > 2 ? f[2] : "";
            if (is_copper(layer))
            {
                notes.add("A " + f[0] + " on copper layer " + layer_name(layer) + " is not read. The router does not know this copper.");
            }
        }
    }

    private void read_footprint(String p_record)
    {
        String[] parts = p_record.split("#@\\$", -1);
        ++part_count;
        String ref = "";
        for (int i = 1; i < parts.length; ++i)
        {
            if (parts[i].startsWith("TEXT~P~"))
            {
                String[] f = parts[i].split("~", -1);
                if (f.length > 10)
                {
                    ref = f[10];
                }
            }
        }
        for (int i = 1; i < parts.length; ++i)
        {
            String part = parts[i];
            if (part.startsWith("PAD~"))
            {
                read_pad(part.split("~", -1), ref);
            }
            else if (part.startsWith("VIA~"))
            {
                String[] f = part.split("~", -1);
                if (f.length < 6)
                {
                    continue;
                }
                Pad pad = new Pad();
                pad.index = pads.size();
                pad.id = f.length > 6 ? f[6] : "";
                pad.ref = ref;
                pad.number = "via";
                pad.net = f[4];
                pad.through = true;
                pad.cx = mm_x(f[1]);
                pad.cy = mm_y(f[2]);
                pad.radius = parse(f[3]) * UNIT_MM / 2.0;
                pad.footprint_via = true;
                pads.add(pad);
            }
            else if (part.startsWith("HOLE~"))
            {
                String[] f = part.split("~", -1);
                if (f.length >= 4)
                {
                    Hole hole = new Hole();
                    hole.x = mm_x(f[1]);
                    hole.y = mm_y(f[2]);
                    hole.radius = parse(f[3]) * UNIT_MM;
                    holes.add(hole);
                }
            }
            else if (part.startsWith("SOLIDREGION~"))
            {
                read_solid(part.split("~", -1), ref);
            }
            else if (part.startsWith("TRACK~"))
            {
                String[] f = part.split("~", -1);
                if (f.length >= 5 && is_copper(f[2]))
                {
                    Track track = new Track();
                    track.width = parse(f[1]) * UNIT_MM;
                    track.layer = f[2];
                    track.net = f[3];
                    track.points = points_mm(f[4]);
                    track.locked = true;
                    track.ref = ref;
                    if (track.points.length >= 4)
                    {
                        footprint_tracks.add(track);
                    }
                }
            }
            else if (part.startsWith("ARC~") || part.startsWith("CIRCLE~"))
            {
                String[] f = part.split("~", -1);
                String layer = part.startsWith("CIRCLE~") && f.length > 5 ? f[5] : f.length > 2 ? f[2] : "";
                if (is_copper(layer))
                {
                    notes.add("Part " + ref + " has a " + f[0] + " on copper layer " + layer_name(layer) + ". It is not read.");
                }
            }
        }
    }

    private void read_solid(String[] p_fields, String p_ref)
    {
        // SOLIDREGION~layer~net~path~type~id
        if (p_fields.length < 4)
        {
            return;
        }
        String layer = p_fields[1];
        String type = p_fields.length > 4 ? p_fields[4] : "solid";
        if (!(is_copper(layer) || layer.equals(MULTI_LAYER)))
        {
            return;
        }
        double[] points = path_mm(p_fields[3]);
        if (points.length < 6)
        {
            return;
        }
        List<String> on = layer.equals(MULTI_LAYER) ? layers : java.util.Collections.singletonList(layer);
        for (String id : on)
        {
            Region region = new Region();
            region.layer = id;
            // A cut-out or a no-copper region carries no copper, but new copper is not permitted there.
            region.net = type.equals("solid") ? p_fields[2] : "";
            region.ref = p_ref;
            region.points = points;
            solids.add(region);
        }
    }

    private void read_pad(String[] f, String p_ref)
    {
        // PAD~shape~cx~cy~width~height~layer~net~number~holeR~points~rotation~id~...
        if (f.length < 13)
        {
            return;
        }
        Pad pad = new Pad();
        pad.index = pads.size();
        pad.ref = p_ref;
        pad.id = f[12];
        pad.number = f[8];
        pad.net = f[7];
        pad.cx = mm_x(f[2]);
        pad.cy = mm_y(f[3]);
        String layer = f[6];
        pad.through = layer.equals(MULTI_LAYER);
        pad.layer = layer;
        if (!pad.through && !is_copper(layer))
        {
            return;
        }
        double width = parse(f[4]) * UNIT_MM;
        double height = parse(f[5]) * UNIT_MM;
        double rotation = parse(f[11]);
        String shape = f[1];
        double[] points = f[10].trim().isEmpty() ? new double[0] : points_mm(f[10]);
        if ((shape.equals("RECT") || shape.equals("POLYGON")) && points.length >= 6)
        {
            pad.polygon = points;
        }
        else if (shape.equals("RECT"))
        {
            pad.polygon = rotate(new double[]
            {
                -width / 2, -height / 2, width / 2, -height / 2, width / 2, height / 2, -width / 2, height / 2
            }, rotation, pad.cx, pad.cy);
        }
        else if (Math.abs(width - height) < 1e-6)
        {
            pad.radius = width / 2.0;
        }
        else
        {
            pad.polygon = rotate(slot(width, height), rotation, pad.cx, pad.cy);
        }
        if (pad.polygon == null && pad.radius <= 0)
        {
            return;
        }
        pads.add(pad);
    }

    /** Outline of a slot shape around 0 0. The arcs are polygons that lie outside the true arc. */
    private static double[] slot(double p_width, double p_height)
    {
        boolean horizontal = p_width > p_height;
        double r = Math.min(p_width, p_height) / 2.0;
        double half = Math.abs(p_width - p_height) / 2.0;
        int steps = 8;
        double outer = r / Math.cos(Math.PI / (2.0 * steps));
        List<Double> values = new ArrayList<Double>();
        for (int side = 0; side < 2; ++side)
        {
            // side 0: the arc around the centre (+half), side 1: around (-half)
            double centre = side == 0 ? half : -half;
            double start = side == 0 ? -Math.PI / 2 : Math.PI / 2;
            for (int k = 0; k < steps; ++k)
            {
                double angle = start + Math.PI * (k + 0.5) / steps;
                double x = centre + outer * Math.cos(angle);
                double y = outer * Math.sin(angle);
                values.add(horizontal ? x : -y);
                values.add(horizontal ? y : x);
            }
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; ++i)
        {
            result[i] = values.get(i);
        }
        return result;
    }

    /** Turns points around 0 0 by the angle of the editor and moves them to the centre. A positive angle is counter-clockwise on the screen. */
    private static double[] rotate(double[] p_points, double p_degrees, double p_cx, double p_cy)
    {
        double a = Math.toRadians(p_degrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        double[] result = new double[p_points.length];
        for (int i = 0; i + 1 < p_points.length; i += 2)
        {
            double x = p_points[i];
            double y = p_points[i + 1];
            result[i] = p_cx + x * cos + y * sin;
            result[i + 1] = p_cy - x * sin + y * cos;
        }
        return result;
    }
}
