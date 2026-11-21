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
import java.util.TreeMap;

/**
 * Puts the result of the engine into the EasyEDA file.
 *
 * Only the shape array changes. Each record that stays keeps its exact text.
 * - Tracks and vias of the routed nets that were in the file are replaced by the tracks and vias of
 *   the engine. Copper that the engine had to keep comes back with the same geometry.
 * - New records get ids that are not in the file.
 * - The stored fill of each copper area is emptied, because it does not fit the new tracks.
 *   Rebuild the copper areas in the editor (Shift+B).
 */
final class EeResult
{
    final String text;
    int track_count = 0;
    int via_count = 0;
    int removed_records = 0;
    int areas_reset = 0;
    /** Segments that the engine made narrower than the track width of the board. */
    int narrow_from_engine = 0;
    /** Of these: segments that got the full width back, because the clearance permits it. */
    int narrow_restored = 0;
    /** Segments of the result that are narrower than the track width of the board. */
    int narrow_segments = 0;
    double narrow_min_mm = Double.MAX_VALUE;
    double narrow_length_mm = 0.0;
    /** Layer name to track length in mm. */
    final Map<String, Double> length_by_layer = new LinkedHashMap<String, Double>();
    /** Via size text to count. */
    final Map<String, Integer> vias_by_size = new TreeMap<String, Integer>();
    /** Result of the widening of power tracks, one line per net. Empty: no rule asks for it. */
    final List<String> widen_lines = new ArrayList<String>();

    /** A track of the result before it is written. */
    static final class NewTrack
    {
        String net;
        /** layer id of the editor */
        String layer;
        double width;
        /** x0 y0 x1 y1 ... in mm from the origin of the file, y down */
        double[] points;
    }

    static final class NewVia
    {
        String net;
        double x, y, diameter, hole;
    }

    EeResult(EeBoard p_board, EePlan p_plan, EeDsn p_dsn, String p_ses)
    {
        List<String> added = new ArrayList<String>();
        List<NewTrack> new_tracks = new ArrayList<NewTrack>();
        List<NewVia> new_vias = new ArrayList<NewVia>();
        String prefix = free_prefix(p_board.text);
        int next_id = 1;

        List<Object> root = SesConverter.parse(p_ses);
        List<List<Object>> routes = new ArrayList<List<Object>>();
        SesConverter.find_all(root, "routes", routes);
        Object scope = routes.isEmpty() ? root : routes.get(0);
        double per_mm = 10000.0;
        List<List<Object>> resolutions = new ArrayList<List<Object>>();
        SesConverter.find_all(scope, "resolution", resolutions);
        if (!resolutions.isEmpty() && resolutions.get(0).size() >= 3)
        {
            String unit = String.valueOf(resolutions.get(0).get(1));
            double value = SesConverter.number(resolutions.get(0).get(2));
            double unit_mm = unit.equals("um") ? 0.001 : unit.equals("mm") ? 1.0 : unit.equals("mil") ? 0.0254 : unit.equals("inch") ? 25.4 : 0.001;
            if (value > 0)
            {
                per_mm = value / unit_mm;
            }
        }
        for (String id : p_board.layers)
        {
            length_by_layer.put(p_board.layer_name(id), 0.0);
        }
        List<List<Object>> network = new ArrayList<List<Object>>();
        SesConverter.find_all(scope, "network_out", network);
        for (List<Object> network_out : network)
        {
            for (Object child : network_out)
            {
                if (!(child instanceof List))
                {
                    continue;
                }
                List<?> net = (List<?>) child;
                if (net.size() < 2 || !"net".equals(net.get(0)))
                {
                    continue;
                }
                String name = p_dsn.net_names.get(String.valueOf(net.get(1)));
                if (name == null)
                {
                    continue;
                }
                for (Object item : net)
                {
                    if (!(item instanceof List))
                    {
                        continue;
                    }
                    List<?> list = (List<?>) item;
                    if (list.isEmpty())
                    {
                        continue;
                    }
                    if ("wire".equals(list.get(0)))
                    {
                        for (Object part : list)
                        {
                            if (!(part instanceof List) || ((List<?>) part).size() < 7 || !"path".equals(((List<?>) part).get(0)))
                            {
                                continue;
                            }
                            List<?> path = (List<?>) part;
                            String layer = p_dsn.layer_ids.get(String.valueOf(path.get(1)));
                            if (layer == null)
                            {
                                continue;
                            }
                            double width_mm = SesConverter.number(path.get(2)) / per_mm;
                            List<Double> values = new ArrayList<Double>();
                            double last_x = Double.NaN, last_y = Double.NaN;
                            for (int i = 3; i + 1 < path.size(); i += 2)
                            {
                                double x = SesConverter.number(path.get(i)) / per_mm;
                                double y = -SesConverter.number(path.get(i + 1)) / per_mm;
                                if (!values.isEmpty() && Math.abs(x - last_x) < 1e-7 && Math.abs(y - last_y) < 1e-7)
                                {
                                    continue;
                                }
                                values.add(x);
                                values.add(y);
                                last_x = x;
                                last_y = y;
                            }
                            if (values.size() < 4)
                            {
                                continue;
                            }
                            if (values.size() == 4 && is_link(p_dsn, values))
                            {
                                continue;
                            }
                            NewTrack track = new NewTrack();
                            track.net = name;
                            track.layer = layer;
                            track.width = width_mm;
                            track.points = new double[values.size()];
                            for (int i = 0; i < track.points.length; ++i)
                            {
                                track.points[i] = values.get(i);
                            }
                            new_tracks.add(track);
                        }
                    }
                    else if ("via".equals(list.get(0)) && list.size() >= 4)
                    {
                        double[] size = p_dsn.via_sizes.get(String.valueOf(list.get(1)));
                        if (size == null)
                        {
                            size = p_dsn.via_sizes.get(EeDsn.VIA_STANDARD);
                        }
                        NewVia via = new NewVia();
                        via.net = name;
                        via.x = SesConverter.number(list.get(2)) / per_mm;
                        via.y = -SesConverter.number(list.get(3)) / per_mm;
                        via.diameter = size[0];
                        via.hole = size[1];
                        new_vias.add(via);
                    }
                }
            }
        }

        // heuristic of the rules: wider tracks for power nets where the clearance permits
        for (NewTrack track : new_tracks)
        {
            if (track.width < p_board.track_mm - 0.0005)
            {
                narrow_from_engine += track.points.length / 2 - 1;
            }
        }
        int[] restored = new int[1];
        new_tracks = EeWiden.apply(p_board, p_plan, new_tracks, new_vias, widen_lines, restored);
        narrow_restored = restored[0];

        for (NewTrack track : new_tracks)
        {
            StringBuilder points = new StringBuilder();
            double length = 0.0;
            for (int i = 0; i + 1 < track.points.length; i += 2)
            {
                if (i > 0)
                {
                    length += Math.hypot(track.points[i] - track.points[i - 2], track.points[i + 1] - track.points[i - 1]);
                    points.append(' ');
                }
                points.append(unit(p_board.origin_x + track.points[i] / EeBoard.UNIT_MM)).append(' ')
                        .append(unit(p_board.origin_y + track.points[i + 1] / EeBoard.UNIT_MM));
            }
            added.add("TRACK~" + unit(track.width / EeBoard.UNIT_MM) + "~" + track.layer + "~" + track.net + "~" + points + "~" + prefix + (next_id++) + "~0");
            ++track_count;
            length_by_layer.merge(p_board.layer_name(track.layer), length, Double::sum);
            if (track.width < p_board.track_mm - 0.0005)
            {
                narrow_segments += track.points.length / 2 - 1;
                narrow_min_mm = Math.min(narrow_min_mm, track.width);
                narrow_length_mm += length;
            }
        }
        for (NewVia via : new_vias)
        {
            added.add("VIA~" + unit(p_board.origin_x + via.x / EeBoard.UNIT_MM) + "~" + unit(p_board.origin_y + via.y / EeBoard.UNIT_MM) + "~"
                    + unit(via.diameter / EeBoard.UNIT_MM) + "~" + via.net + "~" + unit(via.hole / 2.0 / EeBoard.UNIT_MM) + "~" + prefix + (next_id++) + "~0");
            ++via_count;
            vias_by_size.merge(String.format(Locale.ROOT, "%.3f mm with %.2f mm hole", via.diameter, via.hole), 1, Integer::sum);
        }

        // build the new shape array
        StringBuilder sb = new StringBuilder(p_board.text.length() + added.size() * 80);
        sb.append(p_board.text, 0, p_board.shape_start + 1);
        boolean first = true;
        for (int i = 0; i < p_board.records.size(); ++i)
        {
            String record = p_board.records.get(i);
            String literal = null;
            if (record.startsWith("TRACK~") || record.startsWith("VIA~"))
            {
                String[] f = record.split("~", -1);
                boolean track = record.startsWith("TRACK~");
                String net = track ? (f.length > 3 ? f[3] : "") : (f.length > 4 ? f[4] : "");
                boolean copper = !track || (f.length > 2 && p_board.layers.contains(f[2]));
                if (copper && p_plan.nets.containsKey(net))
                {
                    ++removed_records;
                    continue;
                }
            }
            else if (record.startsWith("COPPERAREA~"))
            {
                String[] f = record.split("~", -1);
                if (f.length > 10 && !f[10].trim().equals("[]"))
                {
                    f[10] = "[]";
                    StringBuilder changed = new StringBuilder();
                    Json.append_string(changed, String.join("~", f));
                    literal = changed.toString();
                    ++areas_reset;
                }
            }
            if (literal == null)
            {
                int[] span = p_board.record_spans.get(i);
                literal = p_board.text.substring(span[0], span[1]);
            }
            sb.append(first ? "" : ",").append(literal);
            first = false;
        }
        for (String record : added)
        {
            sb.append(first ? "" : ",");
            Json.append_string(sb, record);
            first = false;
        }
        sb.append(p_board.text, p_board.shape_end, p_board.text.length());
        this.text = sb.toString();
    }

    /** true: the two points are a link track that the bridge added for the engine only. */
    private static boolean is_link(EeDsn p_dsn, List<Double> p_values)
    {
        for (double[] link : p_dsn.links)
        {
            boolean same = Math.abs(link[0] - p_values.get(0)) < 0.0015 && Math.abs(link[1] - p_values.get(1)) < 0.0015
                    && Math.abs(link[2] - p_values.get(2)) < 0.0015 && Math.abs(link[3] - p_values.get(3)) < 0.0015;
            boolean reversed = Math.abs(link[0] - p_values.get(2)) < 0.0015 && Math.abs(link[1] - p_values.get(3)) < 0.0015
                    && Math.abs(link[2] - p_values.get(0)) < 0.0015 && Math.abs(link[3] - p_values.get(1)) < 0.0015;
            if (same || reversed)
            {
                return true;
            }
        }
        return false;
    }

    /** Number in file units with 4 decimals, without trailing zeros. */
    static String unit(double p_value)
    {
        String s = String.format(Locale.ROOT, "%.4f", p_value);
        if (s.indexOf('.') >= 0)
        {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /** An id prefix that is not in the file. Ids of the editor have the form gge plus a number. */
    static String free_prefix(String p_text)
    {
        for (char c = 'a'; c <= 'z'; ++c)
        {
            String prefix = "ggebr" + c;
            if (!p_text.contains(prefix))
            {
                return prefix;
            }
        }
        return "ggebr" + System.nanoTime() + "x";
    }
}
