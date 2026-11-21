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

/**
 * Wider tracks for power nets, after routing.
 *
 * The engine routes all nets at the width of the board. A net with widen_to_mm in the rules then gets,
 * for each track segment, the largest width in steps of 0.05 mm up to that value that keeps the clearance
 * to all copper of other nets, to the board edge and to the keep-outs. A segment that has no room stays
 * as it is. Each change is checked against the widths that earlier changes have set.
 */
final class EeWiden
{
    private static final double STEP_MM = 0.05;
    /** Margin so that rounding in the file does not make a clearance error. */
    private static final double MARGIN_MM = 0.003;

    private EeWiden()
    {
    }

    /**
     * @param p_lines    gets one line per net with wider tracks
     * @param p_restored gets at index 0 the number of narrow segments that got the full width back
     */
    static List<EeResult.NewTrack> apply(EeBoard p_board, EePlan p_plan, List<EeResult.NewTrack> p_tracks, List<EeResult.NewVia> p_vias,
            List<String> p_lines, int[] p_restored)
    {
        Rules rules = p_plan.rules;
        boolean any = false;
        for (Rules.NetEntry entry : rules.nets)
        {
            any |= entry.widen_to_mm > 0.0;
        }
        // The engine narrows a track near a pad. Where the full width keeps the clearance, the segment gets it back.
        boolean restore = rules.narrow_tracks.equals("repair") || rules.narrow_tracks.equals("mixed") || rules.narrow_tracks.equals("avoid");
        if (!any && !restore)
        {
            return p_tracks;
        }

        // all copper of the result board
        List<Copper> others = Copper.of_footprints(p_board);
        for (EeBoard.Track track : p_board.tracks)
        {
            // tracks of nets that the job does not route stay in the file
            if (p_plan.nets.containsKey(track.net) || !p_board.layers.contains(track.layer))
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                others.add(Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer,
                        track.net));
            }
        }
        for (EeBoard.Via via : p_board.vias)
        {
            if (!p_plan.nets.containsKey(via.net))
            {
                others.add(Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net));
            }
        }
        for (EeResult.NewVia via : p_vias)
        {
            others.add(Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net));
        }
        // the segments of the new tracks: one list per track, in the order of the track
        List<List<Copper>> segments = new ArrayList<List<Copper>>();
        for (EeResult.NewTrack track : p_tracks)
        {
            List<Copper> list = new ArrayList<Copper>();
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                Copper c = Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer,
                        track.net);
                list.add(c);
                others.add(c);
            }
            segments.add(list);
        }
        List<Copper> keepouts = new ArrayList<Copper>();
        for (EePlan.Keepout k : p_plan.keepouts)
        {
            keepouts.add(Copper.polygon(new double[]
            {
                k.x0, k.y0, k.x1, k.y0, k.x1, k.y1, k.x0, k.y1
            }, k.layer, ""));
        }
        for (EePlan.KeepoutCircle c : p_plan.hole_keepouts)
        {
            keepouts.add(Copper.disc(c.x, c.y, c.radius, null, ""));
        }

        Map<String, double[]> by_net = new LinkedHashMap<String, double[]>(); // net -> {length widened, length not widened, largest width}
        int outline_points = p_board.outline.length / 2;
        for (int t = 0; t < p_tracks.size(); ++t)
        {
            EeResult.NewTrack track = p_tracks.get(t);
            Rules.NetEntry entry = rules.net_entry(track.net);
            double rule_width = p_board.track_mm;
            double[] own = p_board.special_nets.get(track.net);
            if (own != null && own.length > 0 && own[0] > 0)
            {
                rule_width = own[0];
            }
            boolean narrow = restore && track.width < rule_width - 0.0005;
            double target = entry != null && entry.widen_to_mm > 0.0 ? Math.max(entry.widen_to_mm, rule_width) : narrow ? rule_width : 0.0;
            if (target <= track.width + 1e-9)
            {
                continue;
            }
            boolean power = entry != null && entry.widen_to_mm > track.width + 1e-9;
            double[] sums = power ? by_net.computeIfAbsent(track.net, k -> new double[3]) : new double[3];
            for (Copper s : segments.get(t))
            {
                double reach = target / 2.0 + p_board.clearance_mm + MARGIN_MM;
                // free distance from the centre line of the segment to the nearest copper of another net
                double free = Double.MAX_VALUE;
                for (Copper o : others)
                {
                    if (o == s || (o.net.equals(s.net) && !o.net.isEmpty()) || !s.shares_layer(o))
                    {
                        continue;
                    }
                    double other_r = o.kind == Copper.POLYGON ? 0.0 : o.r;
                    if (o.bx0 - reach > Math.max(s.x0, s.x1) || o.bx1 + reach < Math.min(s.x0, s.x1) || o.by0 - reach > Math.max(s.y0, s.y1)
                            || o.by1 + reach < Math.min(s.y0, s.y1))
                    {
                        continue;
                    }
                    double need = p_board.clearance_mm;
                    double[] special = p_board.special_nets.get(o.net);
                    if (special != null && special.length > 1)
                    {
                        need = Math.max(need, special[1]);
                    }
                    free = Math.min(free, s.centre_distance(o) - other_r - need);
                }
                for (Copper k : keepouts)
                {
                    if (s.shares_layer(k))
                    {
                        free = Math.min(free, s.centre_distance(k) - (k.kind == Copper.POLYGON ? 0.0 : k.r));
                    }
                }
                for (int i = 0; i < outline_points; ++i)
                {
                    int j = (i + 1) % outline_points;
                    free = Math.min(free, EeCheck.segment_distance(p_board.outline[2 * i], p_board.outline[2 * i + 1], p_board.outline[2 * j],
                            p_board.outline[2 * j + 1], s.x0, s.y0, s.x1, s.y1) - rules.copper_to_edge_mm);
                }
                double width = Math.min(target, 2.0 * (free - MARGIN_MM));
                if (width >= rule_width - 1e-9)
                {
                    // from the rule width up: steps of 0.05 mm
                    width = rule_width + Math.floor((width - rule_width) / STEP_MM + 1e-9) * STEP_MM;
                }
                else
                {
                    // below the rule width a wider segment is still an error of the design rule check: no change
                    width = 0.0;
                }
                double length = Math.hypot(s.x1 - s.x0, s.y1 - s.y0);
                if (width > 2.0 * s.r + 1e-9)
                {
                    if (2.0 * s.r < rule_width - 0.0005)
                    {
                        ++p_restored[0];
                    }
                    s.r = width / 2.0;
                    s.bounds();
                    sums[0] += length;
                }
                else
                {
                    sums[1] += length;
                }
                sums[2] = Math.max(sums[2], 2.0 * s.r);
            }
        }

        // tracks again: one track for each run of segments with the same width
        List<EeResult.NewTrack> result = new ArrayList<EeResult.NewTrack>();
        for (int t = 0; t < p_tracks.size(); ++t)
        {
            EeResult.NewTrack track = p_tracks.get(t);
            List<Copper> list = segments.get(t);
            int start = 0;
            while (start < list.size())
            {
                int end = start;
                while (end + 1 < list.size() && Math.abs(list.get(end + 1).r - list.get(start).r) < 1e-9)
                {
                    ++end;
                }
                EeResult.NewTrack piece = new EeResult.NewTrack();
                piece.net = track.net;
                piece.layer = track.layer;
                piece.width = 2.0 * list.get(start).r;
                piece.points = new double[2 * (end - start + 2)];
                for (int i = start; i <= end; ++i)
                {
                    piece.points[2 * (i - start)] = list.get(i).x0;
                    piece.points[2 * (i - start) + 1] = list.get(i).y0;
                }
                piece.points[piece.points.length - 2] = list.get(end).x1;
                piece.points[piece.points.length - 1] = list.get(end).y1;
                result.add(piece);
                start = end + 1;
            }
        }
        for (Map.Entry<String, double[]> e : by_net.entrySet())
        {
            double[] sums = e.getValue();
            p_lines.add(String.format(Locale.ROOT, "%s: %.1f mm made wider (largest width %.2f mm), %.1f mm stay at the routed width.", e.getKey(), sums[0],
                    sums[2], sums[1]));
        }
        return result;
    }
}
