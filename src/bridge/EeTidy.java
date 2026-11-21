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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clean-up of the tracks that the job put into the result file.
 *
 * The engine gives one connection as many wires. A wire ends where the engine changes the width, and the engine
 * leaves small steps where two wires meet. This class works on the tracks of the job only. Tracks that were in
 * the file before the job stay as they are.
 *
 * Without change of the copper (always):
 * - Track records that join end to end become one record. A joint stays where a via, a pad or a third track
 *   of the net is.
 * - A corner point on a straight line is removed.
 *
 * With change of the copper (on the layers of p_copper_layers):
 * - A segment that the engine made narrower than the track rule gets the full width. Measured on a routed board:
 *   for 35 of 54 narrow segments the full width keeps the clearance rule by 0.0001 mm to 0.0005 mm. The engine
 *   keeps 0.0015 mm above the rule, so it made these segments narrow. The segment is first tested in its position
 *   and then with a shift to the side of 0.002 mm to 0.010 mm. The ends of the segment move along the lines of
 *   the segments before and after it.
 * - A small step (two segments of 0.2 mm or less at about a right angle) becomes one segment.
 * - A side step (a segment of 0.2 mm or less between two segments with the same direction, where the track moves
 *   sideways by 0.1 mm or less) goes away: one of the two segments moves to the line of the other.
 * - A corner point that is 0.004 mm or less from the straight line between the points before and after it is
 *   removed.
 * Each change of the copper is made only when the new copper keeps the clearance rule plus MARGIN_MM to all
 * copper of other nets, to the keep-outs and to the board edge. Where the old copper was nearer than that (a
 * pad on copper of an antenna, for example), the new copper must not be nearer than the old copper.
 *
 * The caller checks the result (open connections with the fill model, clearance) and takes it only when it is
 * not worse than the file before the clean-up.
 */
final class EeTidy
{
    private static final boolean DEBUG = Boolean.getBoolean("bridge.test.tidy_debug");
    /**
     * New copper keeps this distance above the clearance rule. Measured on a routed board: the smallest clearance of the
     * engine's tracks with the full width is 0.0015 mm above the rule. So the clean-up puts no copper nearer than the engine does.
     */
    static final double MARGIN_MM = 0.0015;
    /**
     * A narrow segment gets the full width when the full width keeps the clearance rule plus this value. It is smaller
     * than MARGIN_MM on purpose: a track below the smallest width of the board maker is the larger fault. The file
     * stores positions in steps of 0.000025 mm, so this value is 8 steps.
     */
    static final double WIDEN_MARGIN_MM = 0.0002;
    /** The largest shift to the side that gives a narrow segment the full width. */
    private static final double SHIFT_MAX_MM = 0.015;
    /** Added to the smallest shift that can work. */
    private static final double[] SHIFT_EXTRA_MM =
    {
        0.00005, 0.0005, 0.001, 0.002, 0.004
    };
    /** The margin of the test that runs now: MARGIN_MM, or WIDEN_MARGIN_MM while a narrow segment gets the full width. */
    private double margin = MARGIN_MM;
    /** What the last call of worst_room found nearest. */
    private String blocker = "";
    /** One line for each narrow segment that stays, with the cause. */
    final List<String> narrow_lines = new ArrayList<String>();
    /** The largest move of a corner point along the segment before or after a shifted segment. */
    private static final double SLIDE_MAX_MM = 0.03;
    /** A segment with one end that keeps its position turns by this angle (in radians) at most: about 1 degree. */
    private static final double TURN_MAX = 0.02;
    private static final double STEP_MAX_MM = 0.2;
    /** A side step: the short segment is this long at most, and the track moves sideways by SIDE_OFFSET_MAX_MM at most. */
    private static final double SIDE_MIDDLE_MAX_MM = 0.2;
    private static final double SIDE_OFFSET_MAX_MM = 0.1;
    private static final double SIDE_SLIDE_MAX_MM = 0.3;
    private static final double LINE_TOL_MM = 0.004;
    /** Below the resolution of the coordinates in the file. */
    private static final double EXACT_TOL_MM = 0.00006;
    private static final double ANCHOR_MM = 0.02;
    private static final double NEAR_MM = 0.05;

    /** The file with the new track records. The text of the input when nothing changed. */
    final String text;
    int records_before = 0;
    int records_after = 0;
    /** Corner points on a straight line, removed. */
    int points_removed = 0;
    int steps_removed = 0;
    /** Short segments between two segments with the same direction, removed. */
    int side_steps_removed = 0;
    int widened_in_place = 0;
    int widened_after_shift = 0;
    double largest_shift_mm = 0.0;
    /** Segments of the job that are narrower than the track rule after the clean-up. */
    int narrow_segments = 0;
    double narrow_min_mm = Double.MAX_VALUE;
    double narrow_length_mm = 0.0;

    private final EeBoard board;
    private final EePlan plan;
    private final List<Copper> fixed = new ArrayList<Copper>();
    private final List<Copper> keepouts = new ArrayList<Copper>();
    /** Net to the points where a track of the net must keep its end: centres of vias and pads. Values: x, y, layer index or -1. */
    private final Map<String, List<double[]>> anchors = new HashMap<String, List<double[]>>();
    /** Net to the segments of the tracks of the net that the job did not make. */
    private final Map<String, List<Copper>> fixed_tracks = new HashMap<String, List<Copper>>();
    private final List<Chain> chains = new ArrayList<Chain>();

    /** Tracks that join end to end without a via, a pad or a third track at a joint. One width for each segment. */
    private static final class Chain
    {
        String net;
        String layer;
        final List<double[]> pts = new ArrayList<double[]>();
        final List<Double> widths = new ArrayList<Double>();
        final List<String> ids = new ArrayList<String>();
        List<Copper> copper;
    }

    private static final class Piece
    {
        String net;
        String layer;
        String id;
        double width;
        double[] points;
        int record;
    }

    /**
     * @param p_original      the file before the job. Its records are not changed. null: all tracks of the routed nets
     *                        that are not locked are tracks of the job.
     * @param p_routed        the result file
     * @param p_copper_layers the layers where the copper can change. null: all layers. Empty: no change of copper.
     */
    EeTidy(EeBoard p_original, EeBoard p_routed, EePlan p_plan, Set<String> p_copper_layers)
    {
        this.board = p_routed;
        this.plan = p_plan;
        Set<String> original = new HashSet<String>();
        if (p_original != null)
        {
            original.addAll(p_original.records);
        }
        List<Piece> pieces = new ArrayList<Piece>();
        Set<Integer> piece_records = new HashSet<Integer>();
        fixed.addAll(Copper.of_footprints(p_routed));
        for (EeBoard.Track track : p_routed.tracks)
        {
            if (!p_routed.layers.contains(track.layer) || track.points.length < 4)
            {
                continue;
            }
            boolean of_job = track.record >= 0 && !track.locked && !track.net.isEmpty() && p_plan.nets.containsKey(track.net)
                    && !original.contains(p_routed.records.get(track.record)) && !piece_records.contains(track.record);
            String[] f = of_job ? p_routed.records.get(track.record).split("~", -1) : null;
            if (of_job && f.length > 5 && !f[5].isEmpty())
            {
                Piece piece = new Piece();
                piece.net = track.net;
                piece.layer = track.layer;
                piece.width = track.width;
                piece.points = track.points;
                piece.record = track.record;
                piece.id = f[5];
                pieces.add(piece);
                piece_records.add(track.record);
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                Copper c = Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer,
                        track.net);
                fixed.add(c);
                if (!track.net.isEmpty())
                {
                    fixed_tracks.computeIfAbsent(track.net, k -> new ArrayList<Copper>()).add(c);
                }
            }
        }
        for (EeBoard.Via via : p_routed.vias)
        {
            fixed.add(Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net));
            if (!via.net.isEmpty())
            {
                anchors.computeIfAbsent(via.net, k -> new ArrayList<double[]>()).add(new double[]
                {
                    via.x, via.y, -1
                });
            }
        }
        for (EeBoard.Pad pad : p_routed.pads)
        {
            if (!pad.net.isEmpty())
            {
                anchors.computeIfAbsent(pad.net, k -> new ArrayList<double[]>()).add(new double[]
                {
                    pad.cx, pad.cy, pad.through ? -1 : p_routed.layers.indexOf(pad.layer)
                });
            }
        }
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
        records_before = pieces.size();
        if (pieces.isEmpty())
        {
            this.text = p_routed.text;
            return;
        }

        build_chains(pieces);
        for (Chain chain : chains)
        {
            boolean copper = p_copper_layers == null || p_copper_layers.contains(chain.layer);
            if (copper)
            {
                double rule = rule_width(chain.net);
                for (int i = 0; i < chain.widths.size(); ++i)
                {
                    if (chain.widths.get(i) < rule - 0.0005)
                    {
                        widen(chain, i, rule);
                    }
                }
            }
            boolean changed = true;
            for (int round = 0; changed && round < 20; ++round)
            {
                changed = false;
                for (int i = 1; copper && i + 1 < chain.pts.size();)
                {
                    if (remove_step(chain, i) || remove_side_step(chain, i))
                    {
                        changed = true;
                        i = Math.max(1, i - 2);
                    }
                    else
                    {
                        ++i;
                    }
                }
                for (int i = 1; i + 1 < chain.pts.size();)
                {
                    if (remove_line_point(chain, i, EXACT_TOL_MM, false) || (copper && remove_line_point(chain, i, LINE_TOL_MM, true)))
                    {
                        changed = true;
                        i = Math.max(1, i - 1);
                    }
                    else
                    {
                        ++i;
                    }
                }
            }
        }

        // The other tracks have their last form now. A narrow segment that had no room before can have it now.
        boolean wider = true;
        for (int round = 0; wider && round < 5; ++round)
        {
            wider = false;
            for (Chain chain : chains)
            {
                if (!(p_copper_layers == null || p_copper_layers.contains(chain.layer)))
                {
                    continue;
                }
                double rule = rule_width(chain.net);
                for (int i = 0; i < chain.widths.size(); ++i)
                {
                    if (chain.widths.get(i) < rule - 0.0005 && widen(chain, i, rule))
                    {
                        wider = true;
                    }
                }
                for (int i = 1; i + 1 < chain.pts.size();)
                {
                    if (remove_line_point(chain, i, EXACT_TOL_MM, false))
                    {
                        i = Math.max(1, i - 1);
                    }
                    else
                    {
                        ++i;
                    }
                }
            }
        }
        for (Chain chain : chains)
        {
            double rule = rule_width(chain.net);
            for (int i = 0; i < chain.widths.size(); ++i)
            {
                double width = chain.widths.get(i);
                if (width >= rule - 0.0005)
                {
                    continue;
                }
                double[] a = chain.pts.get(i), b = chain.pts.get(i + 1);
                List<Copper> full = new ArrayList<Copper>();
                full.add(segment(chain, a, b, rule));
                double room = worst_room(chain, full);
                String cause = room >= WIDEN_MARGIN_MM ? "the full width fits in this position, and a joint with the next segment prevents the change"
                        : room >= 0.0 ? String.format(java.util.Locale.ROOT, "the full width has %.4f mm to spare to %s, which is less than %.4f mm", room, blocker, WIDEN_MARGIN_MM)
                                : String.format(java.util.Locale.ROOT, "the full width is %.3f mm too near to %s", -room, blocker);
                narrow_lines.add(String.format(java.util.Locale.ROOT, "%s on %s, width %.3f mm, length %.2f mm, from (%.2f, %.2f) to (%.2f, %.2f): %s.", chain.net,
                        layer_name(chain.layer), width, Math.hypot(b[0] - a[0], b[1] - a[1]), a[0], a[1], b[0], b[1], cause));
            }
        }

        // the new records: one for each run of segments with the same width
        List<String> added = new ArrayList<String>();
        for (Chain chain : chains)
        {
            double rule = rule_width(chain.net);
            int start = 0;
            int run = 0;
            while (start < chain.widths.size())
            {
                int end = start;
                while (end + 1 < chain.widths.size() && Math.abs(chain.widths.get(end + 1) - chain.widths.get(start)) < 1e-6)
                {
                    ++end;
                }
                double width = chain.widths.get(start);
                StringBuilder points = new StringBuilder();
                double length = 0.0;
                for (int i = start; i <= end + 1; ++i)
                {
                    double[] p = chain.pts.get(i);
                    if (i > start)
                    {
                        double[] q = chain.pts.get(i - 1);
                        length += Math.hypot(p[0] - q[0], p[1] - q[1]);
                        points.append(' ');
                    }
                    points.append(EeResult.unit(p_routed.origin_x + p[0] / EeBoard.UNIT_MM)).append(' ')
                            .append(EeResult.unit(p_routed.origin_y + p[1] / EeBoard.UNIT_MM));
                }
                String id = run < chain.ids.size() ? chain.ids.get(run) : chain.ids.get(0) + "t" + run;
                added.add("TRACK~" + EeResult.unit(width / EeBoard.UNIT_MM) + "~" + chain.layer + "~" + chain.net + "~" + points + "~" + id + "~0");
                ++records_after;
                if (width < rule - 0.0005)
                {
                    narrow_segments += end - start + 1;
                    narrow_min_mm = Math.min(narrow_min_mm, width);
                    narrow_length_mm += length;
                }
                ++run;
                start = end + 1;
            }
        }
        StringBuilder sb = new StringBuilder(p_routed.text.length());
        sb.append(p_routed.text, 0, p_routed.shape_start + 1);
        boolean first = true;
        for (int i = 0; i < p_routed.records.size(); ++i)
        {
            if (piece_records.contains(i))
            {
                continue;
            }
            int[] span = p_routed.record_spans.get(i);
            sb.append(first ? "" : ",").append(p_routed.text, span[0], span[1]);
            first = false;
        }
        for (String record : added)
        {
            sb.append(first ? "" : ",");
            Json.append_string(sb, record);
            first = false;
        }
        sb.append(p_routed.text, p_routed.shape_end, p_routed.text.length());
        this.text = sb.toString();
    }

    private String layer_name(String p_layer)
    {
        int index = board.layers.indexOf(p_layer);
        if (index < 0)
        {
            return "layer " + p_layer;
        }
        return index == 0 ? "top" : index == board.layers.size() - 1 ? "bottom" : "inner" + index;
    }

    /** true: the clean-up changed the width or the position of copper. */
    boolean copper_changed()
    {
        return widened_in_place + widened_after_shift + steps_removed + side_steps_removed > 0 || line_points_moved > 0;
    }

    private int line_points_moved = 0;

    private double rule_width(String p_net)
    {
        double[] own = board.special_nets.get(p_net);
        return own != null && own.length > 0 && own[0] > 0 ? own[0] : board.track_mm;
    }

    private static String key(String p_net, String p_layer, double p_x, double p_y)
    {
        return p_net + "|" + p_layer + "|" + Math.round(p_x * 10000.0) + "|" + Math.round(p_y * 10000.0);
    }

    private void build_chains(List<Piece> p_pieces)
    {
        int n = p_pieces.size();
        int[][] link = new int[n][2];
        Map<String, List<int[]>> ends = new HashMap<String, List<int[]>>();
        for (int p = 0; p < n; ++p)
        {
            Piece piece = p_pieces.get(p);
            link[p][0] = -1;
            link[p][1] = -1;
            int last = piece.points.length - 2;
            ends.computeIfAbsent(key(piece.net, piece.layer, piece.points[0], piece.points[1]), k -> new ArrayList<int[]>()).add(new int[]
            {
                p, 0
            });
            ends.computeIfAbsent(key(piece.net, piece.layer, piece.points[last], piece.points[last + 1]), k -> new ArrayList<int[]>()).add(new int[]
            {
                p, 1
            });
        }
        for (List<int[]> list : ends.values())
        {
            if (list.size() != 2 || list.get(0)[0] == list.get(1)[0])
            {
                continue;
            }
            int[] a = list.get(0);
            int[] b = list.get(1);
            Piece piece = p_pieces.get(a[0]);
            int at = a[1] == 0 ? 0 : piece.points.length - 2;
            if (anchored(piece.net, piece.layer, piece.points[at], piece.points[at + 1]))
            {
                continue;
            }
            link[a[0]][a[1]] = b[0] * 2 + b[1];
            link[b[0]][b[1]] = a[0] * 2 + a[1];
        }
        boolean[] used = new boolean[n];
        for (int pass = 0; pass < 2; ++pass)
        {
            for (int p = 0; p < n; ++p)
            {
                if (used[p] || (pass == 0 && link[p][0] != -1 && link[p][1] != -1))
                {
                    continue;
                }
                Chain chain = new Chain();
                chain.net = p_pieces.get(p).net;
                chain.layer = p_pieces.get(p).layer;
                int current = p;
                int enter = link[p][0] == -1 ? 0 : link[p][1] == -1 ? 1 : 0;
                while (true)
                {
                    used[current] = true;
                    Piece piece = p_pieces.get(current);
                    int count = piece.points.length / 2;
                    for (int i = 0; i < count; ++i)
                    {
                        int index = enter == 0 ? i : count - 1 - i;
                        if (i == 0 && !chain.pts.isEmpty())
                        {
                            continue;
                        }
                        chain.pts.add(new double[]
                        {
                            piece.points[2 * index], piece.points[2 * index + 1]
                        });
                        if (i > 0)
                        {
                            chain.widths.add(piece.width);
                        }
                    }
                    chain.ids.add(piece.id);
                    int next = link[current][1 - enter];
                    if (next == -1 || used[next / 2])
                    {
                        break;
                    }
                    current = next / 2;
                    enter = next % 2;
                }
                chains.add(chain);
            }
        }
    }

    /** true: a via, a pad or a track of the net that the job did not make is at the point. */
    private boolean anchored(String p_net, String p_layer, double p_x, double p_y)
    {
        return near_anchor(p_net, p_layer, p_x, p_y, p_x, p_y, ANCHOR_MM, null, null);
    }

    /**
     * true: a via, a pad, a track that the job did not make, or the end of a second chain of the net is nearer than p_dist
     * to the segment and is not at one of the two points that keep their position.
     */
    private boolean near_anchor(String p_net, String p_layer, double p_x0, double p_y0, double p_x1, double p_y1, double p_dist, double[] p_keep_a,
            double[] p_keep_b)
    {
        int layer_index = board.layers.indexOf(p_layer);
        List<double[]> list = anchors.get(p_net);
        if (list != null)
        {
            for (double[] a : list)
            {
                if ((a[2] < 0 || a[2] == layer_index) && point_segment(a[0], a[1], p_x0, p_y0, p_x1, p_y1) <= p_dist && !kept(a[0], a[1], p_keep_a, p_keep_b))
                {
                    return true;
                }
            }
        }
        List<Copper> tracks = fixed_tracks.get(p_net);
        if (tracks != null)
        {
            for (Copper t : tracks)
            {
                if (t.layer.equals(p_layer) && EeCheck.segment_distance(t.x0, t.y0, t.x1, t.y1, p_x0, p_y0, p_x1, p_y1) <= p_dist)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean near_chain_end(Chain p_chain, double p_x0, double p_y0, double p_x1, double p_y1, double p_dist, double[] p_keep_a, double[] p_keep_b)
    {
        for (Chain other : chains)
        {
            if (other == p_chain || !other.net.equals(p_chain.net) || !other.layer.equals(p_chain.layer))
            {
                continue;
            }
            double[] a = other.pts.get(0);
            double[] b = other.pts.get(other.pts.size() - 1);
            if ((point_segment(a[0], a[1], p_x0, p_y0, p_x1, p_y1) <= p_dist && !kept(a[0], a[1], p_keep_a, p_keep_b))
                    || (point_segment(b[0], b[1], p_x0, p_y0, p_x1, p_y1) <= p_dist && !kept(b[0], b[1], p_keep_a, p_keep_b)))
            {
                return true;
            }
        }
        return false;
    }

    private boolean disturbs(Chain p_chain, double[] p_a, double[] p_b, double[] p_keep_a, double[] p_keep_b)
    {
        return near_anchor(p_chain.net, p_chain.layer, p_a[0], p_a[1], p_b[0], p_b[1], NEAR_MM, p_keep_a, p_keep_b)
                || near_chain_end(p_chain, p_a[0], p_a[1], p_b[0], p_b[1], NEAR_MM, p_keep_a, p_keep_b);
    }

    private static boolean kept(double p_x, double p_y, double[] p_keep_a, double[] p_keep_b)
    {
        return (p_keep_a != null && Math.hypot(p_x - p_keep_a[0], p_y - p_keep_a[1]) <= ANCHOR_MM)
                || (p_keep_b != null && Math.hypot(p_x - p_keep_b[0], p_y - p_keep_b[1]) <= ANCHOR_MM);
    }

    private static double point_segment(double p_x, double p_y, double p_x0, double p_y0, double p_x1, double p_y1)
    {
        double dx = p_x1 - p_x0;
        double dy = p_y1 - p_y0;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p_x - p_x0) * dx + (p_y - p_y0) * dy) / len2));
        return Math.hypot(p_x - p_x0 - t * dx, p_y - p_y0 - t * dy);
    }

    private Copper segment(Chain p_chain, double[] p_a, double[] p_b, double p_width)
    {
        return Copper.segment(p_a[0], p_a[1], p_b[0], p_b[1], p_width / 2.0, p_chain.layer, p_chain.net);
    }

    private List<Copper> copper_of(Chain p_chain)
    {
        if (p_chain.copper == null)
        {
            p_chain.copper = new ArrayList<Copper>();
            for (int i = 0; i + 1 < p_chain.pts.size(); ++i)
            {
                p_chain.copper.add(segment(p_chain, p_chain.pts.get(i), p_chain.pts.get(i + 1), p_chain.widths.get(i)));
            }
        }
        return p_chain.copper;
    }

    /** Smallest distance from the copper of the segments to the copper of p_other, minus p_need. */
    private static double room(List<Copper> p_segments, Copper p_other, double p_need)
    {
        double other_r = p_other.kind == Copper.POLYGON ? 0.0 : p_other.r;
        double best = Double.MAX_VALUE;
        for (Copper s : p_segments)
        {
            best = Math.min(best, s.centre_distance(p_other) - other_r - s.r - p_need);
        }
        return best;
    }

    private static boolean all_far(List<Copper> p_segments, Copper p_other, double p_gap)
    {
        for (Copper s : p_segments)
        {
            if (!s.far_from(p_other, p_gap))
            {
                return false;
            }
        }
        return true;
    }

    /** true: the new segments keep the rules where the old segments kept them, and are not nearer to other copper where the old ones were too near. */
    private boolean accept(Chain p_chain, List<Copper> p_new, List<Copper> p_old)
    {
        double clearance = board.clearance_mm;
        double[] own = board.special_nets.get(p_chain.net);
        if (own != null && own.length > 1)
        {
            clearance = Math.max(clearance, own[1]);
        }
        for (Copper o : fixed)
        {
            if ((o.net.equals(p_chain.net) && !o.net.isEmpty()) || !p_new.get(0).shares_layer(o))
            {
                continue;
            }
            double need = clearance;
            double[] special = board.special_nets.get(o.net);
            if (special != null && special.length > 1)
            {
                need = Math.max(need, special[1]);
            }
            if (!fits(p_new, p_old, o, need))
            {
                return false;
            }
        }
        for (Chain other : chains)
        {
            if (other == p_chain || other.net.equals(p_chain.net) || !other.layer.equals(p_chain.layer))
            {
                continue;
            }
            double need = clearance;
            double[] special = board.special_nets.get(other.net);
            if (special != null && special.length > 1)
            {
                need = Math.max(need, special[1]);
            }
            for (Copper o : copper_of(other))
            {
                if (!fits(p_new, p_old, o, need))
                {
                    return false;
                }
            }
        }
        for (Copper k : keepouts)
        {
            if (p_new.get(0).shares_layer(k) && !fits(p_new, p_old, k, 0.0))
            {
                return false;
            }
        }
        int outline_points = board.outline == null ? 0 : board.outline.length / 2;
        for (int i = 0; i < outline_points; ++i)
        {
            int j = (i + 1) % outline_points;
            double room_new = edge_room(p_new, i, j);
            if (room_new < margin && room_new < edge_room(p_old, i, j) - 1e-7)
            {
                return false;
            }
        }
        return true;
    }

    private boolean fits(List<Copper> p_new, List<Copper> p_old, Copper p_other, double p_need)
    {
        if (all_far(p_new, p_other, p_need + margin))
        {
            return true;
        }
        double room_new = room(p_new, p_other, p_need);
        return room_new >= margin || room_new >= room(p_old, p_other, p_need) - 1e-7;
    }

    private double edge_room(List<Copper> p_segments, int p_i, int p_j)
    {
        double best = Double.MAX_VALUE;
        for (Copper s : p_segments)
        {
            best = Math.min(best, EeCheck.segment_distance(board.outline[2 * p_i], board.outline[2 * p_i + 1], board.outline[2 * p_j], board.outline[2 * p_j + 1],
                    s.x0, s.y0, s.x1, s.y1) - s.r - plan.rules.copper_to_edge_mm);
        }
        return best;
    }

    /** The full width for the narrow segment p_i of the chain: in its position, or after a small shift to the side. */
    /**
     * The smallest room of the segments to copper of other nets and to keep-outs: the distance minus the clearance.
     * Below 0: the segments break the clearance rule by that value. Sets the field blocker.
     */
    private double worst_room(Chain p_chain, List<Copper> p_new)
    {
        double clearance = board.clearance_mm;
        double[] own = board.special_nets.get(p_chain.net);
        if (own != null && own.length > 1)
        {
            clearance = Math.max(clearance, own[1]);
        }
        double worst = Double.MAX_VALUE;
        blocker = "";
        for (Copper o : fixed)
        {
            if ((o.net.equals(p_chain.net) && !o.net.isEmpty()) || !p_new.get(0).shares_layer(o))
            {
                continue;
            }
            double need = clearance;
            double[] special = board.special_nets.get(o.net);
            if (special != null && special.length > 1)
            {
                need = Math.max(need, special[1]);
            }
            if (all_far(p_new, o, need + 0.05))
            {
                continue;
            }
            double room = room(p_new, o, need);
            if (room < worst)
            {
                worst = room;
                blocker = !o.label.isEmpty() ? o.label : o.net.isEmpty() ? "copper without net" : (o.kind == Copper.POLYGON ? "copper of " : "a via of ") + o.net;
            }
        }
        for (Chain other : chains)
        {
            if (other == p_chain || other.net.equals(p_chain.net) || !other.layer.equals(p_chain.layer))
            {
                continue;
            }
            double need = clearance;
            double[] special = board.special_nets.get(other.net);
            if (special != null && special.length > 1)
            {
                need = Math.max(need, special[1]);
            }
            for (Copper o : copper_of(other))
            {
                if (all_far(p_new, o, need + 0.05))
                {
                    continue;
                }
                double room = room(p_new, o, need);
                if (room < worst)
                {
                    worst = room;
                    blocker = "the track of " + other.net;
                }
            }
        }
        for (Copper k : keepouts)
        {
            if (!p_new.get(0).shares_layer(k) || all_far(p_new, k, 0.05))
            {
                continue;
            }
            double room = room(p_new, k, 0.0);
            if (room < worst)
            {
                worst = room;
                blocker = "a keep-out";
            }
        }
        return worst;
    }

    /**
     * true: the point is the centre of a pad or a via of the net of the chain, and that copper is so large that a point
     * SHIFT_MAX_MM away is still well inside it.
     */
    private boolean in_own_copper(Chain p_chain, double[] p_point)
    {
        final double at = 0.003, half = 0.06;
        for (EeBoard.Pad pad : board.pads)
        {
            if (!pad.net.equals(p_chain.net) || !(pad.through || pad.layer.equals(p_chain.layer)))
            {
                continue;
            }
            if (Math.abs(pad.cx - p_point[0]) < at && Math.abs(pad.cy - p_point[1]) < at)
            {
                double[] box = pad.box();
                return Math.min(box[2] - box[0], box[3] - box[1]) / 2.0 >= half;
            }
        }
        for (EeBoard.Via via : board.vias)
        {
            if (via.net.equals(p_chain.net) && Math.abs(via.x - p_point[0]) < at && Math.abs(via.y - p_point[1]) < at)
            {
                return via.diameter / 2.0 >= half;
            }
        }
        return false;
    }

    private boolean widen(Chain p_chain, int p_i, double p_rule)
    {
        margin = WIDEN_MARGIN_MM;
        try
        {
            return widen_with_margin(p_chain, p_i, p_rule);
        }
        finally
        {
            margin = MARGIN_MM;
        }
    }

    private boolean widen_with_margin(Chain p_chain, int p_i, double p_rule)
    {
        double[] a = p_chain.pts.get(p_i);
        double[] b = p_chain.pts.get(p_i + 1);
        double width = p_chain.widths.get(p_i);
        List<Copper> old_copper = new ArrayList<Copper>();
        List<Copper> new_copper = new ArrayList<Copper>();
        old_copper.add(segment(p_chain, a, b, width));
        new_copper.add(segment(p_chain, a, b, p_rule));
        if (accept(p_chain, new_copper, old_copper))
        {
            p_chain.widths.set(p_i, p_rule);
            p_chain.copper = null;
            ++widened_in_place;
            if (DEBUG)
            {
                System.out.println(String.format(java.util.Locale.ROOT, "  in place: %s layer %s (%.3f,%.3f)-(%.3f,%.3f) width %.4f", p_chain.net, p_chain.layer,
                        a[0], a[1], b[0], b[1], width));
            }
            return true;
        }
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        if (length < 0.01)
        {
            return false;
        }
        double nx = -(b[1] - a[1]) / length;
        double ny = (b[0] - a[0]) / length;
        double[] before = p_i > 0 ? p_chain.pts.get(p_i - 1) : null;
        double[] after = p_i + 2 < p_chain.pts.size() ? p_chain.pts.get(p_i + 2) : null;
        // the smallest shift that can work is the value by which the full width is too near in its position
        double deficit = Math.max(0.0, margin - worst_room(p_chain, new_copper));
        List<Double> shifts = new ArrayList<Double>();
        for (double extra : SHIFT_EXTRA_MM)
        {
            if (deficit + extra <= SHIFT_MAX_MM)
            {
                shifts.add(deficit + extra);
            }
        }
        for (double shift : shifts)
        {
            for (int sign = 1; sign >= -1; sign -= 2)
            {
                double dx = sign * shift * nx;
                double dy = sign * shift * ny;
                // An end of the track in the centre of a pad or a via of its net can move with the segment: it stays inside that copper.
                boolean free_a = before == null && in_own_copper(p_chain, a);
                boolean free_b = after == null && in_own_copper(p_chain, b);
                double[] a2 = free_a ? new double[]
                {
                    a[0] + dx, a[1] + dy
                } : before == null ? null : slide(before, a, a[0] + dx, a[1] + dy, b[0] + dx, b[1] + dy);
                double[] b2 = free_b ? new double[]
                {
                    b[0] + dx, b[1] + dy
                } : after == null ? null : slide(after, b, a[0] + dx, a[1] + dy, b[0] + dx, b[1] + dy);
                if (a2 == null && b2 == null)
                {
                    if (DEBUG)
                    {
                        System.out.println("  no shift: " + p_chain.net + ": no end of the segment can move. deficit " + deficit + " blocker " + blocker);
                    }
                    return false; // no end of the segment can move
                }
                if ((a2 == null || b2 == null) && shift / length > TURN_MAX)
                {
                    if (DEBUG)
                    {
                        System.out.println("  no shift: " + p_chain.net + ": one end is fixed and the turn is too large. shift " + shift + " length " + length);
                    }
                    return false; // one end keeps its position: the segment turns, and the turn would be too large
                }
                double[] keep_a = a2 == null || free_a ? a : null;
                double[] keep_b = b2 == null || free_b ? b : null;
                if (disturbs(p_chain, a, b, keep_a, keep_b))
                {
                    if (DEBUG)
                    {
                        System.out.println("  no shift: " + p_chain.net + ": the shift disturbs a joint");
                    }
                    return false;
                }
                double[] na = a2 == null ? a : a2;
                double[] nb = b2 == null ? b : b2;
                old_copper.clear();
                new_copper.clear();
                old_copper.add(segment(p_chain, a, b, width));
                new_copper.add(segment(p_chain, na, nb, p_rule));
                if (a2 != null && !free_a)
                {
                    old_copper.add(segment(p_chain, before, a, p_chain.widths.get(p_i - 1)));
                    new_copper.add(segment(p_chain, before, na, p_chain.widths.get(p_i - 1)));
                }
                if (b2 != null && !free_b)
                {
                    old_copper.add(segment(p_chain, b, after, p_chain.widths.get(p_i + 1)));
                    new_copper.add(segment(p_chain, nb, after, p_chain.widths.get(p_i + 1)));
                }
                if (accept(p_chain, new_copper, old_copper))
                {
                    p_chain.pts.set(p_i, na);
                    p_chain.pts.set(p_i + 1, nb);
                    p_chain.widths.set(p_i, p_rule);
                    p_chain.copper = null;
                    ++widened_after_shift;
                    largest_shift_mm = Math.max(largest_shift_mm, shift);
                    if (DEBUG)
                    {
                        System.out.println(String.format(java.util.Locale.ROOT, "  shift %.3f: %s layer %s (%.3f,%.3f)-(%.3f,%.3f) width %.4f, ends moved: %s %s",
                                sign * shift, p_chain.net, p_chain.layer, a[0], a[1], b[0], b[1], width, a2 != null, b2 != null));
                    }
                    return true;
                }
                if (DEBUG)
                {
                    System.out.println(String.format(java.util.Locale.ROOT, "  shift %.5f not accepted: %s, ends moved: %s %s, room after %.5f to %s", sign * shift, p_chain.net,
                            a2 != null, b2 != null, worst_room(p_chain, new_copper), blocker));
                }
            }
        }
        return false;
    }

    /**
     * The new position of the corner p_joint between a segment that keeps its line (p_far to p_joint) and a segment
     * that moves to the line through (p_lx0, p_ly0) and (p_lx1, p_ly1). null: the corner keeps its position, because
     * the two lines are nearly parallel or the corner would move too far.
     */
    private static double[] slide(double[] p_far, double[] p_joint, double p_lx0, double p_ly0, double p_lx1, double p_ly1)
    {
        return slide(p_far, p_joint, p_lx0, p_ly0, p_lx1, p_ly1, SLIDE_MAX_MM);
    }

    private static double[] slide(double[] p_far, double[] p_joint, double p_lx0, double p_ly0, double p_lx1, double p_ly1, double p_max_move)
    {
        double ux = p_joint[0] - p_far[0];
        double uy = p_joint[1] - p_far[1];
        double vx = p_lx1 - p_lx0;
        double vy = p_ly1 - p_ly0;
        double ul = Math.hypot(ux, uy);
        double vl = Math.hypot(vx, vy);
        if (ul < 0.02 || vl < 1e-9)
        {
            return null;
        }
        double cross = ux * vy - uy * vx;
        if (Math.abs(cross) / (ul * vl) < 0.3)
        {
            return null;
        }
        // p_far + t * u = l0 + s * v
        double t = ((p_lx0 - p_far[0]) * vy - (p_ly0 - p_far[1]) * vx) / cross;
        double[] q =
        {
            p_far[0] + t * ux, p_far[1] + t * uy
        };
        if (t * ul < 0.01 || Math.hypot(q[0] - p_joint[0], q[1] - p_joint[1]) > p_max_move)
        {
            return null;
        }
        return q;
    }

    /** Two short segments at about a right angle become one segment. */
    private boolean remove_step(Chain p_chain, int p_i)
    {
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double l1 = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double l2 = Math.hypot(c[0] - b[0], c[1] - b[1]);
        if (l1 > STEP_MAX_MM || l2 > STEP_MAX_MM || l1 < 1e-9 || l2 < 1e-9)
        {
            return false;
        }
        double cos = ((b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1])) / (l1 * l2);
        if (Math.abs(cos) > 0.5 || Math.hypot(c[0] - a[0], c[1] - a[1]) < 0.01)
        {
            return false;
        }
        double w1 = p_chain.widths.get(p_i - 1);
        double w2 = p_chain.widths.get(p_i);
        double width = Math.max(w1, w2);
        if (disturbs(p_chain, a, b, a, null) || disturbs(p_chain, b, c, null, c))
        {
            return false;
        }
        List<Copper> old_copper = new ArrayList<Copper>();
        List<Copper> new_copper = new ArrayList<Copper>();
        old_copper.add(segment(p_chain, a, b, w1));
        old_copper.add(segment(p_chain, b, c, w2));
        new_copper.add(segment(p_chain, a, c, width));
        if (!accept(p_chain, new_copper, old_copper))
        {
            return false;
        }
        p_chain.pts.remove(p_i);
        p_chain.widths.remove(p_i);
        p_chain.widths.set(p_i - 1, width);
        p_chain.copper = null;
        ++steps_removed;
        return true;
    }

    /**
     * A side step: a short segment between two segments with the same direction. One of the two segments moves to the
     * line of the other, and the short segment goes away. The far end of the segment that moves goes along the line of
     * the segment next to it. The shorter of the two segments is tested first.
     *
     * @param p_i the index of the short segment
     */
    private boolean remove_side_step(Chain p_chain, int p_i)
    {
        if (p_i < 1 || p_i + 2 >= p_chain.pts.size())
        {
            return false;
        }
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double[] d = p_chain.pts.get(p_i + 2);
        double width = p_chain.widths.get(p_i - 1);
        if (Math.abs(p_chain.widths.get(p_i) - width) > 1e-6 || Math.abs(p_chain.widths.get(p_i + 1) - width) > 1e-6)
        {
            return false;
        }
        double middle = Math.hypot(c[0] - b[0], c[1] - b[1]);
        double l1 = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double l2 = Math.hypot(d[0] - c[0], d[1] - c[1]);
        if (middle > SIDE_MIDDLE_MAX_MM || middle < 1e-9 || l1 < 1e-9 || l2 < 1e-9)
        {
            return false;
        }
        double ux = (b[0] - a[0]) / l1;
        double uy = (b[1] - a[1]) / l1;
        if (ux * (d[0] - c[0]) / l2 + uy * (d[1] - c[1]) / l2 < 0.9998)
        {
            return false; // not the same direction
        }
        double offset = Math.abs((c[0] - a[0]) * uy - (c[1] - a[1]) * ux);
        if (offset > SIDE_OFFSET_MAX_MM || offset <= EXACT_TOL_MM)
        {
            return false;
        }
        boolean before_first = l1 <= l2;
        boolean done = (before_first ? move_before(p_chain, p_i) : move_after(p_chain, p_i))
                || (before_first ? move_after(p_chain, p_i) : move_before(p_chain, p_i));
        if (DEBUG && !done)
        {
            System.out.println(String.format(java.util.Locale.ROOT, "  side step stays: %s layer %s at (%.3f,%.3f), sideways %.4f. before: %s. after: %s", p_chain.net,
                    p_chain.layer, b[0], b[1], offset, why(p_chain, p_i, true), why(p_chain, p_i, false)));
        }
        return done;
    }

    /** For tests: the reason why a segment next to the side step p_i cannot move. */
    private String why(Chain p_chain, int p_i, boolean p_before)
    {
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double[] d = p_chain.pts.get(p_i + 2);
        if (p_before)
        {
            if (p_i < 2)
            {
                return "starts at a via, pad or joint";
            }
            double[] z = p_chain.pts.get(p_i - 2);
            double[] a2 = slide(z, a, c[0], c[1], d[0], d[1], SIDE_SLIDE_MAX_MM);
            if (a2 == null)
            {
                return "no position for the corner";
            }
            return disturbs(p_chain, b, c, null, null) || disturbs(p_chain, a, b, z, null) || disturbs(p_chain, a, a2, z, null) ? "via, pad or track end near" : "clearance";
        }
        if (p_i + 3 >= p_chain.pts.size())
        {
            return "ends at a via, pad or joint";
        }
        double[] e = p_chain.pts.get(p_i + 3);
        double[] d2 = slide(e, d, a[0], a[1], b[0], b[1], SIDE_SLIDE_MAX_MM);
        if (d2 == null)
        {
            return "no position for the corner";
        }
        return disturbs(p_chain, b, c, null, null) || disturbs(p_chain, c, d, e, null) || disturbs(p_chain, d, d2, e, null) ? "via, pad or track end near" : "clearance";
    }

    /** The segment after the short segment p_i moves to the line of the segment before it. */
    private boolean move_after(Chain p_chain, int p_i)
    {
        if (p_i + 3 >= p_chain.pts.size())
        {
            return false; // the segment ends at a via, a pad or a joint
        }
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double[] d = p_chain.pts.get(p_i + 2);
        double[] e = p_chain.pts.get(p_i + 3);
        double width = p_chain.widths.get(p_i - 1);
        double next_width = p_chain.widths.get(p_i + 2);
        double[] d2 = slide(e, d, a[0], a[1], b[0], b[1], SIDE_SLIDE_MAX_MM);
        if (d2 == null || (d2[0] - a[0]) * (b[0] - a[0]) + (d2[1] - a[1]) * (b[1] - a[1]) <= 0.0 || Math.hypot(d2[0] - a[0], d2[1] - a[1]) < 0.01)
        {
            return false;
        }
        if (disturbs(p_chain, b, c, null, null) || disturbs(p_chain, c, d, e, null) || disturbs(p_chain, d, d2, e, null))
        {
            return false;
        }
        List<Copper> old_copper = new ArrayList<Copper>();
        List<Copper> new_copper = new ArrayList<Copper>();
        old_copper.add(segment(p_chain, a, b, width));
        old_copper.add(segment(p_chain, b, c, width));
        old_copper.add(segment(p_chain, c, d, width));
        old_copper.add(segment(p_chain, d, e, next_width));
        new_copper.add(segment(p_chain, a, d2, width));
        new_copper.add(segment(p_chain, d2, e, next_width));
        if (!accept(p_chain, new_copper, old_copper))
        {
            return false;
        }
        p_chain.pts.set(p_i + 2, d2);
        p_chain.pts.remove(p_i + 1);
        p_chain.pts.remove(p_i);
        p_chain.widths.remove(p_i + 1);
        p_chain.widths.remove(p_i);
        p_chain.copper = null;
        ++side_steps_removed;
        return true;
    }

    /** The segment before the short segment p_i moves to the line of the segment after it. */
    private boolean move_before(Chain p_chain, int p_i)
    {
        if (p_i < 2)
        {
            return false; // the segment starts at a via, a pad or a joint
        }
        double[] z = p_chain.pts.get(p_i - 2);
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double[] d = p_chain.pts.get(p_i + 2);
        double width = p_chain.widths.get(p_i - 1);
        double before_width = p_chain.widths.get(p_i - 2);
        double[] a2 = slide(z, a, c[0], c[1], d[0], d[1], SIDE_SLIDE_MAX_MM);
        if (a2 == null || (d[0] - a2[0]) * (d[0] - c[0]) + (d[1] - a2[1]) * (d[1] - c[1]) <= 0.0 || Math.hypot(d[0] - a2[0], d[1] - a2[1]) < 0.01)
        {
            return false;
        }
        if (disturbs(p_chain, b, c, null, null) || disturbs(p_chain, a, b, z, null) || disturbs(p_chain, a, a2, z, null))
        {
            return false;
        }
        List<Copper> old_copper = new ArrayList<Copper>();
        List<Copper> new_copper = new ArrayList<Copper>();
        old_copper.add(segment(p_chain, z, a, before_width));
        old_copper.add(segment(p_chain, a, b, width));
        old_copper.add(segment(p_chain, b, c, width));
        old_copper.add(segment(p_chain, c, d, width));
        new_copper.add(segment(p_chain, z, a2, before_width));
        new_copper.add(segment(p_chain, a2, d, width));
        if (!accept(p_chain, new_copper, old_copper))
        {
            return false;
        }
        p_chain.pts.set(p_i - 1, a2);
        p_chain.pts.remove(p_i + 1);
        p_chain.pts.remove(p_i);
        p_chain.widths.remove(p_i);
        p_chain.widths.remove(p_i - 1);
        p_chain.copper = null;
        ++side_steps_removed;
        return true;
    }

    /** A corner point on the straight line between the points before and after it is removed. */
    private boolean remove_line_point(Chain p_chain, int p_i, double p_tolerance, boolean p_check)
    {
        double[] a = p_chain.pts.get(p_i - 1);
        double[] b = p_chain.pts.get(p_i);
        double[] c = p_chain.pts.get(p_i + 1);
        double w1 = p_chain.widths.get(p_i - 1);
        double w2 = p_chain.widths.get(p_i);
        if (Math.abs(w1 - w2) > 1e-6)
        {
            return false;
        }
        double dot = (b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1]);
        double chord = Math.hypot(c[0] - a[0], c[1] - a[1]);
        if (dot <= 0.0 || chord < 1e-9)
        {
            return false;
        }
        double deviation = Math.abs((c[0] - a[0]) * (b[1] - a[1]) - (c[1] - a[1]) * (b[0] - a[0])) / chord;
        if (deviation > p_tolerance)
        {
            return false;
        }
        if (p_check)
        {
            if (disturbs(p_chain, b, b, null, null))
            {
                return false;
            }
            List<Copper> old_copper = new ArrayList<Copper>();
            List<Copper> new_copper = new ArrayList<Copper>();
            old_copper.add(segment(p_chain, a, b, w1));
            old_copper.add(segment(p_chain, b, c, w2));
            new_copper.add(segment(p_chain, a, c, w1));
            if (!accept(p_chain, new_copper, old_copper))
            {
                return false;
            }
            ++line_points_moved;
        }
        p_chain.pts.remove(p_i);
        p_chain.widths.remove(p_i);
        p_chain.copper = null;
        ++points_removed;
        return true;
    }
}
