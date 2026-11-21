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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decisions that the bridge makes for one board before the engine starts:
 * which nets are routed, which nets have a plane, where copper is not permitted,
 * and which pads get a via because they have no exit.
 *
 * These are rules of thumb. Each one can be switched in config/rules.json and each use is reported.
 */
final class EePlan
{
    /** Cell size of the grid for the exit test. */
    private static final double CELL_MM = 0.05;

    static final class PadVia
    {
        EeBoard.Pad pad;
        double x, y;
    }

    /** A rectangle where new copper is not permitted. */
    static final class Keepout
    {
        String layer;
        double x0, y0, x1, y1;
    }

    /** A circle where new copper is not permitted (a hole that is not plated). */
    static final class KeepoutCircle
    {
        double x, y, radius;
    }

    /** Copper that the bridge places keeps the clearance plus this margin, so that no rounding makes an error. */
    private static final double PLACE_MARGIN_MM = 0.002;

    final EeBoard board;
    final Rules rules;
    /** Nets that the engine routes, in a fixed order. The value is the list of pads. */
    final Map<String, List<EeBoard.Pad>> nets = new LinkedHashMap<String, List<EeBoard.Pad>>();
    /** Net name to the copper areas that make it a plane net. */
    final Map<String, List<EeBoard.Region>> planes = new LinkedHashMap<String, List<EeBoard.Region>>();
    /** Layers (ids) that carry a plane. */
    final List<String> plane_layers = new ArrayList<String>();
    final List<String> skipped_nets = new ArrayList<String>();
    final List<PadVia> pad_vias = new ArrayList<PadVia>();
    final List<EeBoard.Pad> pads_without_exit_and_via = new ArrayList<EeBoard.Pad>();
    final List<Keepout> keepouts = new ArrayList<Keepout>();
    final List<KeepoutCircle> hole_keepouts = new ArrayList<KeepoutCircle>();
    /**
     * All copper areas of each routed net, on inner and outer layers, and the planes of the rules. The check of the
     * connections models the fill of each one. The engine gets only the entries of planes.
     */
    final Map<String, List<EeBoard.Region>> fill_areas = new LinkedHashMap<String, List<EeBoard.Region>>();
    /** Groups of pour pads of one net that are nearer to each other than the clearance. The engine gets each group as a net of its own. */
    final List<List<EeBoard.Pad>> pour_clusters = new ArrayList<List<EeBoard.Pad>>();
    /** Pads that the pour of their net joins. The engine gets them as pads without net. */
    final java.util.Set<EeBoard.Pad> pour_pads = new java.util.HashSet<EeBoard.Pad>();
    /** Supply nets with a copper area that the engine routes with tracks. Net to its areas. */
    final Map<String, List<EeBoard.Region>> supply_areas = new LinkedHashMap<String, List<EeBoard.Region>>();
    /** Short tracks from a pad of a plane net to a standard via beside the pad. */
    final List<Stub> stubs = new ArrayList<Stub>();

    static final class Stub
    {
        EeBoard.Pad pad;
        /** End of the track and position of the via. */
        double x, y;
        /** false: the track ends on the via of an earlier stub. */
        boolean new_via;
    }

    /** Parts whose copper without net is covered by keep-out rectangles. Pads of the part that lie on that copper stay open for a track. */
    final java.util.Set<String> boxed_parts = new java.util.HashSet<String>();
    final List<String> notes = new ArrayList<String>();
    double via_diameter_mm;
    double via_hole_mm;

    /** Changes of the plan for a further pass of the engine on a board that it has routed. */
    static final class Rework
    {
        /** Pads that get a via in the pad. The bridge takes away the tracks of other nets that are in the way. */
        final List<EeBoard.Pad> via_pads = new ArrayList<EeBoard.Pad>();
        /** Pads in a copper area that the engine connects with its own means. */
        final java.util.Set<EeBoard.Pad> net_pads = new java.util.HashSet<EeBoard.Pad>();
        /** Standard vias that the bridge added after an earlier pass and that stay fixed: x, y in mm. */
        final List<double[]> fixed_vias = new ArrayList<double[]>();
        /** The net of each fixed via. */
        final List<String> fixed_via_nets = new ArrayList<String>();
    }

    /** null: first pass */
    final Rework rework;

    EePlan(EeBoard p_board, Rules p_rules)
    {
        this(p_board, p_rules, null);
    }

    EePlan(EeBoard p_board, Rules p_rules, Rework p_rework)
    {
        this.board = p_board;
        this.rules = p_rules;
        this.rework = p_rework;
        via_diameter_mm = p_rules.via_diameter_mm > 0 ? p_rules.via_diameter_mm : p_board.via_mm;
        via_hole_mm = p_rules.via_hole_mm > 0 ? p_rules.via_hole_mm : p_board.via_hole_mm;
        choose_planes();
        choose_nets();
        // the areas whose fill the check of the connections models
        for (EeBoard.Region area : p_board.areas)
        {
            if (!area.net.isEmpty() && nets.containsKey(area.net))
            {
                fill_areas.computeIfAbsent(area.net, k -> new ArrayList<EeBoard.Region>()).add(area);
            }
        }
        for (Map.Entry<String, List<EeBoard.Region>> entry : planes.entrySet())
        {
            for (EeBoard.Region area : entry.getValue())
            {
                if (nets.containsKey(entry.getKey()) && area.record < 0)
                {
                    fill_areas.computeIfAbsent(entry.getKey(), k -> new ArrayList<EeBoard.Region>()).add(area);
                }
            }
        }
        choose_keepouts();
        // Pads that a copper area of their net connects on their own layer. They get no track and no via.
        if (p_rules.pours.equals("after"))
        {
            find_pour_pads();
        }
        if (p_rules.via_on_pads.equals("needed") || p_rules.via_on_pads.equals("mixed"))
        {
            find_pads_without_exit();
        }
        if (!p_rules.via_on_pads.equals("off") && !p_rules.plane_pad_vias.equals("none"))
        {
            find_plane_pads();
        }
        if (!p_rules.via_on_pads.equals("off") && !p_rules.pad_via_list.isEmpty())
        {
            add_listed_pads();
        }
        if (p_rework != null)
        {
            for (EeBoard.Pad pad : p_rework.via_pads)
            {
                boolean has_via = false;
                for (PadVia via : pad_vias)
                {
                    has_via |= via.pad == pad;
                }
                if (!has_via)
                {
                    PadVia via = new PadVia();
                    via.pad = pad;
                    via.x = pad.cx;
                    via.y = pad.cy;
                    pad_vias.add(via);
                }
            }
        }
        if (p_rules.pours.equals("after"))
        {
            settle_pour_pads();
        }
        if (p_rules.plane_stubs.equals("on") && !planes.isEmpty())
        {
            find_plane_stubs();
        }
        notes.addAll(p_board.notes);
    }

    /** The vias in pads of this plan as copper. They are in the engine input and not in the board file. */
    List<Copper> pad_via_copper()
    {
        List<Copper> result = new ArrayList<Copper>();
        for (PadVia via : pad_vias)
        {
            Copper c = Copper.disc(via.x, via.y, rules.pad_via_diameter_mm / 2.0, null, via.pad.net);
            c.label = "via in pad " + via.pad.ref + "." + via.pad.number;
            result.add(c);
        }
        return result;
    }

    // ------------------------------------------------------------------ nets and planes

    private void choose_planes()
    {
        if (!rules.planes_from_areas.equals("none"))
        {
            for (EeBoard.Region area : board.areas)
            {
                boolean outer = area.layer.equals("1") || area.layer.equals("2");
                if (area.net.isEmpty() || (outer && !rules.planes_from_areas.equals("all")))
                {
                    continue;
                }
                if (rules.supply_planes.equals("tracks") && !rules.ground_net.matcher(area.net).matches())
                {
                    if (!supply_areas.containsKey(area.net))
                    {
                        notes.add("Net " + area.net + " has a copper area on " + board.layer_name(area.layer)
                                + ". The engine routes this net with tracks. The area is not counted as a connection (rules: planes.supply).");
                    }
                    supply_areas.computeIfAbsent(area.net, k -> new ArrayList<EeBoard.Region>()).add(area);
                    continue;
                }
                planes.computeIfAbsent(area.net, k -> new ArrayList<EeBoard.Region>()).add(area);
                if (!plane_layers.contains(area.layer))
                {
                    plane_layers.add(area.layer);
                }
            }
        }
        for (Rules.PlaneEntry entry : rules.planes)
        {
            String layer = board.layer_id(entry.layer);
            if (layer == null)
            {
                notes.add("rules.json: planes: the board has no layer \"" + entry.layer + "\". Entry for net " + entry.net + " ignored.");
                continue;
            }
            EeBoard.Region area = new EeBoard.Region();
            area.layer = layer;
            area.net = entry.net;
            area.points = board.outline;
            planes.computeIfAbsent(entry.net, k -> new ArrayList<EeBoard.Region>()).add(area);
            if (!plane_layers.contains(layer))
            {
                plane_layers.add(layer);
            }
        }
    }

    private void choose_nets()
    {
        Map<String, List<EeBoard.Pad>> all = new LinkedHashMap<String, List<EeBoard.Pad>>();
        for (EeBoard.Pad pad : board.pads)
        {
            if (!pad.net.isEmpty())
            {
                all.computeIfAbsent(pad.net, k -> new ArrayList<EeBoard.Pad>()).add(pad);
            }
        }
        for (Map.Entry<String, List<EeBoard.Pad>> entry : all.entrySet())
        {
            String net = entry.getKey();
            if (entry.getValue().size() < 2)
            {
                continue;
            }
            Rules.NetEntry rule = rules.net_entry(net);
            boolean skip = rule != null && rule.skip;
            if (!skip && board.skip_nets.contains(net))
            {
                boolean has_area = false;
                for (EeBoard.Region area : board.areas)
                {
                    has_area |= net.equals(area.net);
                }
                if (planes.containsKey(net) || (has_area && !supply_areas.containsKey(net) && rules.pours.equals("after")))
                {
                    notes.add("Net " + net + " is a skip net in the editor and has a copper area. The router connects its pads to that area.");
                }
                else
                {
                    skip = true;
                }
            }
            if (skip)
            {
                skipped_nets.add(net);
                continue;
            }
            nets.put(net, entry.getValue());
        }
        // A plane for a net that is not routed has no use.
        planes.keySet().removeIf(net -> !nets.containsKey(net));
    }

    // ------------------------------------------------------------------ keep-out

    private void choose_keepouts()
    {
        double engine_clearance = board.clearance_mm;
        for (EeBoard.Hole hole : board.holes)
        {
            KeepoutCircle circle = new KeepoutCircle();
            circle.x = hole.x;
            circle.y = hole.y;
            // The engine adds the clearance of the board to every keep-out.
            circle.radius = hole.radius + Math.max(0.0, rules.hole_clearance_mm - engine_clearance);
            hole_keepouts.add(circle);
        }
        for (Rules.KeepoutEntry entry : rules.keepouts)
        {
            List<String> ids = new ArrayList<String>();
            if (entry.layers.isEmpty())
            {
                ids.addAll(board.layers);
            }
            for (String name : entry.layers)
            {
                String id = board.layer_id(name);
                if (id != null)
                {
                    ids.add(id);
                }
            }
            for (String id : ids)
            {
                Keepout k = new Keepout();
                k.layer = id;
                k.x0 = entry.x0;
                k.y0 = entry.y0;
                k.x1 = entry.x1;
                k.y1 = entry.y1;
                keepouts.add(k);
            }
        }
        if (rules.antenna_margin_mm >= 0)
        {
            antenna_keepouts(Math.max(0.0, rules.antenna_margin_mm - engine_clearance));
        }
    }

    /** Height of the rows that approximate a closed area with sloped sides. */
    private static final double ROW_MM = 0.1;

    /**
     * Copper without net inside a footprint is taken as an antenna. The area around it is closed on all layers.
     * The area is the convex hull of the copper plus the margin (rules: keepout.antenna_shape = hull), or the
     * rectangle around the copper plus the margin (box). The hull is stored as rows of rectangles. A pad inside the
     * area keeps a lane on its own layer, in the axis direction with the shortest way out of the area.
     */
    private void antenna_keepouts(double p_margin)
    {
        Map<String, double[]> boxes = new LinkedHashMap<String, double[]>();
        Map<String, List<double[]>> point_lists = new LinkedHashMap<String, List<double[]>>();
        for (EeBoard.Region solid : board.solids)
        {
            if (!solid.net.isEmpty() || solid.ref.isEmpty())
            {
                continue;
            }
            double[] box = boxes.computeIfAbsent(solid.ref, k -> new double[]
            {
                Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE
            });
            point_lists.computeIfAbsent(solid.ref, k -> new ArrayList<double[]>()).add(solid.points);
            for (int i = 0; i + 1 < solid.points.length; i += 2)
            {
                box[0] = Math.min(box[0], solid.points[i]);
                box[1] = Math.min(box[1], solid.points[i + 1]);
                box[2] = Math.max(box[2], solid.points[i]);
                box[3] = Math.max(box[3], solid.points[i + 1]);
            }
        }
        double[] board_box = box_of(board.outline);
        for (Map.Entry<String, double[]> entry : boxes.entrySet())
        {
            double[] b = entry.getValue();
            double x0 = Math.max(board_box[0], b[0] - p_margin);
            double y0 = Math.max(board_box[1], b[1] - p_margin);
            double x1 = Math.min(board_box[2], b[2] + p_margin);
            double y1 = Math.min(board_box[3], b[3] + p_margin);
            // the closed area before the lanes, as rectangles
            List<double[]> region = null;
            if (rules.antenna_shape.equals("hull"))
            {
                region = hull_rows(point_lists.get(entry.getKey()), p_margin, board_box);
            }
            boolean hull = region != null && !region.isEmpty();
            if (!hull)
            {
                region = new ArrayList<double[]>();
                region.add(new double[]
                {
                    x0, y0, x1, y1
                });
            }
            double area = 0.0;
            for (double[] r : region)
            {
                area += (r[2] - r[0]) * (r[3] - r[1]);
            }
            int count = 0;
            for (String layer : board.layers)
            {
                // lanes for the pads that lie in the area on this layer
                List<double[]> lanes = new ArrayList<double[]>();
                // Wide enough for a track of the net of the pad. Too narrow for a track of another net beside the pad.
                double lane_half = board.track_mm / 2.0 + board.clearance_mm;
                for (EeBoard.Pad pad : board.pads)
                {
                    if (pad.net.isEmpty() || !(pad.through || pad.layer.equals(layer)))
                    {
                        continue;
                    }
                    double[] pb = pad.box();
                    if (!touches(region, pb))
                    {
                        continue;
                    }
                    // way out of the area from each side of the pad: left, top, right, bottom
                    double[] d =
                    {
                        way_out(region, pb[0], (pb[1] + pb[3]) / 2.0, -1, 0), way_out(region, (pb[0] + pb[2]) / 2.0, pb[1], 0, -1),
                        way_out(region, pb[2], (pb[1] + pb[3]) / 2.0, 1, 0), way_out(region, (pb[0] + pb[2]) / 2.0, pb[3], 0, 1)
                    };
                    int side = 0;
                    for (int i = 1; i < 4; ++i)
                    {
                        if (d[i] < d[side])
                        {
                            side = i;
                        }
                    }
                    double lx0 = pb[0] - lane_half, ly0 = pb[1] - lane_half, lx1 = pb[2] + lane_half, ly1 = pb[3] + lane_half;
                    if (side == 0)
                    {
                        lx0 = pb[0] - d[0] - 1.0;
                    }
                    else if (side == 1)
                    {
                        ly0 = pb[1] - d[1] - 1.0;
                    }
                    else if (side == 2)
                    {
                        lx1 = pb[2] + d[2] + 1.0;
                    }
                    else
                    {
                        ly1 = pb[3] + d[3] + 1.0;
                    }
                    lanes.add(new double[]
                    {
                        lx0, ly0, lx1, ly1
                    });
                }
                for (double[] rect : region)
                {
                    for (double[] r : subtract(rect, lanes))
                    {
                        Keepout k = new Keepout();
                        k.layer = layer;
                        k.x0 = r[0];
                        k.y0 = r[1];
                        k.x1 = r[2];
                        k.y1 = r[3];
                        keepouts.add(k);
                        ++count;
                    }
                }
            }
            boxed_parts.add(entry.getKey());
            if (hull)
            {
                notes.add(String.format(java.util.Locale.ROOT,
                        "Part %s has copper without net. The area around this copper is closed for new copper on all layers: the hull of the copper plus %.2f mm, %.0f mm2 (%d rectangles). "
                                + "The rectangle around the copper has %.0f mm2 (rules: keepout.antenna_shape).",
                        entry.getKey(), p_margin + board.clearance_mm, area, count, (x1 - x0) * (y1 - y0)));
            }
            else
            {
                notes.add(String.format(java.util.Locale.ROOT,
                        "Part %s has copper without net. The area %.1f x %.1f mm around it is closed for new copper on all layers (%d rectangles).",
                        entry.getKey(), x1 - x0, y1 - y0, count));
            }
        }
    }

    /** true: the box touches one of the rectangles. */
    private static boolean touches(List<double[]> p_region, double[] p_box)
    {
        for (double[] r : p_region)
        {
            if (!(p_box[2] < r[0] || p_box[0] > r[2] || p_box[3] < r[1] || p_box[1] > r[3]))
            {
                return true;
            }
        }
        return false;
    }

    /** Distance from a point in a direction to the first position outside all rectangles. */
    private static double way_out(List<double[]> p_region, double p_x, double p_y, int p_dx, int p_dy)
    {
        final double step = 0.025;
        for (int n = 0; n < 4000; ++n)
        {
            double x = p_x + n * step * p_dx, y = p_y + n * step * p_dy;
            boolean inside = false;
            for (double[] r : p_region)
            {
                if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3])
                {
                    inside = true;
                    break;
                }
            }
            if (!inside)
            {
                return n * step;
            }
        }
        return 4000 * step;
    }

    /**
     * The convex hull of the points, grown by the margin, as rows of rectangles of the height ROW_MM. Each row
     * covers the hull in its range of y, so the rows are never smaller than the hull. null: the points have no area.
     */
    private static List<double[]> hull_rows(List<double[]> p_point_lists, double p_margin, double[] p_clip)
    {
        List<double[]> pts = new ArrayList<double[]>();
        for (double[] list : p_point_lists)
        {
            for (int i = 0; i + 1 < list.length; i += 2)
            {
                pts.add(new double[]
                {
                    list[i], list[i + 1]
                });
            }
        }
        if (pts.size() < 3)
        {
            return null;
        }
        pts.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] h = new double[2 * pts.size()][];
        int k = 0;
        for (int i = 0; i < pts.size(); ++i)
        {
            while (k >= 2 && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0)
            {
                --k;
            }
            h[k++] = pts.get(i);
        }
        for (int i = pts.size() - 2, t = k + 1; i >= 0; --i)
        {
            while (k >= t && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0)
            {
                --k;
            }
            h[k++] = pts.get(i);
        }
        int n = k - 1;
        if (n < 3)
        {
            return null;
        }
        double hy0 = Double.MAX_VALUE, hy1 = -Double.MAX_VALUE;
        for (int i = 0; i < n; ++i)
        {
            hy0 = Math.min(hy0, h[i][1]);
            hy1 = Math.max(hy1, h[i][1]);
        }
        List<double[]> rows = new ArrayList<double[]>();
        double start = Math.floor((hy0 - p_margin) / ROW_MM) * ROW_MM;
        for (double ya = start; ya < hy1 + p_margin; ya += ROW_MM)
        {
            double yb = ya + ROW_MM;
            double xa = Double.MAX_VALUE, xb = -Double.MAX_VALUE;
            double from = Math.max(hy0, ya - p_margin), to = Math.min(hy1, yb + p_margin);
            int samples = Math.max(1, (int) Math.ceil((to - from) / 0.01));
            for (int s = 0; s <= samples && from <= to; ++s)
            {
                double y = from + (to - from) * s / samples;
                double dy = y < ya ? ya - y : y > yb ? y - yb : 0.0;
                if (dy > p_margin)
                {
                    continue;
                }
                double grow = Math.sqrt(Math.max(0.0, p_margin * p_margin - dy * dy));
                for (int i = 0; i < n; ++i)
                {
                    double[] p = h[i], q = h[(i + 1) % n];
                    if ((p[1] <= y && y <= q[1]) || (q[1] <= y && y <= p[1]))
                    {
                        if (p[1] == q[1])
                        {
                            xa = Math.min(xa, Math.min(p[0], q[0]) - grow);
                            xb = Math.max(xb, Math.max(p[0], q[0]) + grow);
                        }
                        else
                        {
                            double x = p[0] + (y - p[1]) / (q[1] - p[1]) * (q[0] - p[0]);
                            xa = Math.min(xa, x - grow);
                            xb = Math.max(xb, x + grow);
                        }
                    }
                }
            }
            xa = Math.max(xa, p_clip[0]);
            xb = Math.min(xb, p_clip[2]);
            double ca = Math.max(ya, p_clip[1]), cb = Math.min(yb, p_clip[3]);
            if (xa < xb && ca < cb)
            {
                rows.add(new double[]
                {
                    xa, ca, xb, cb
                });
            }
        }
        return rows;
    }

    private static double cross(double[] p_o, double[] p_a, double[] p_b)
    {
        return (p_a[0] - p_o[0]) * (p_b[1] - p_o[1]) - (p_a[1] - p_o[1]) * (p_b[0] - p_o[0]);
    }

    private static double[] box_of(double[] p_points)
    {
        double[] box =
        {
            Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE
        };
        for (int i = 0; i + 1 < p_points.length; i += 2)
        {
            box[0] = Math.min(box[0], p_points[i]);
            box[1] = Math.min(box[1], p_points[i + 1]);
            box[2] = Math.max(box[2], p_points[i]);
            box[3] = Math.max(box[3], p_points[i + 1]);
        }
        return box;
    }

    /** The rectangle minus the holes, as a list of rectangles. */
    private static List<double[]> subtract(double[] p_rect, List<double[]> p_holes)
    {
        java.util.TreeSet<Double> xs = new java.util.TreeSet<Double>();
        java.util.TreeSet<Double> ys = new java.util.TreeSet<Double>();
        xs.add(p_rect[0]);
        xs.add(p_rect[2]);
        ys.add(p_rect[1]);
        ys.add(p_rect[3]);
        for (double[] h : p_holes)
        {
            for (int i = 0; i < 4; i += 2)
            {
                if (h[i] > p_rect[0] && h[i] < p_rect[2])
                {
                    xs.add(h[i]);
                }
                if (h[i + 1] > p_rect[1] && h[i + 1] < p_rect[3])
                {
                    ys.add(h[i + 1]);
                }
            }
        }
        Double[] xa = xs.toArray(new Double[0]);
        Double[] ya = ys.toArray(new Double[0]);
        List<double[]> result = new ArrayList<double[]>();
        for (int j = 0; j + 1 < ya.length; ++j)
        {
            double[] run = null;
            for (int i = 0; i + 1 < xa.length; ++i)
            {
                double mx = (xa[i] + xa[i + 1]) / 2.0;
                double my = (ya[j] + ya[j + 1]) / 2.0;
                boolean inside_hole = false;
                for (double[] h : p_holes)
                {
                    if (mx > h[0] && mx < h[2] && my > h[1] && my < h[3])
                    {
                        inside_hole = true;
                        break;
                    }
                }
                if (inside_hole)
                {
                    run = null;
                }
                else if (run != null)
                {
                    run[2] = xa[i + 1];
                }
                else
                {
                    run = new double[]
                    {
                        xa[i], ya[j], xa[i + 1], ya[j + 1]
                    };
                    result.add(run);
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ pads without exit

    /** Distance of a point to a pad shape. 0 inside. */
    static double distance(EeBoard.Pad p_pad, double p_x, double p_y)
    {
        if (p_pad.polygon == null)
        {
            return Math.max(0.0, Math.hypot(p_x - p_pad.cx, p_y - p_pad.cy) - p_pad.radius);
        }
        return distance(p_pad.polygon, p_x, p_y);
    }

    /** Distance of a point to a polygon. 0 inside. */
    static double distance(double[] p_polygon, double p_x, double p_y)
    {
        int n = p_polygon.length / 2;
        boolean inside = false;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double xi = p_polygon[2 * i], yi = p_polygon[2 * i + 1];
            double xj = p_polygon[2 * j], yj = p_polygon[2 * j + 1];
            if ((yi > p_y) != (yj > p_y) && p_x < (xj - xi) * (p_y - yi) / (yj - yi) + xi)
            {
                inside = !inside;
            }
            double dx = xj - xi, dy = yj - yi;
            double len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((p_x - xi) * dx + (p_y - yi) * dy) / len));
            best = Math.min(best, Math.hypot(p_x - (xi + t * dx), p_y - (yi + t * dy)));
        }
        return inside ? 0.0 : best;
    }

    /**
     * A pad has no exit when a track of the rule width cannot go from the pad to open board on the
     * layer of the pad, with only the pads and the fixed copper on the board. Such a pad gets a via in its centre.
     */
    private void find_pads_without_exit()
    {
        double[] bb = box_of(board.outline);
        int nx = (int) Math.ceil((bb[2] - bb[0]) / CELL_MM) + 1;
        int ny = (int) Math.ceil((bb[3] - bb[1]) / CELL_MM) + 1;
        if ((long) nx * ny > 40_000_000L)
        {
            notes.add("The board is too large for the exit test of the pads. No via was put into a pad.");
            return;
        }
        double reach = board.track_mm / 2.0 + board.clearance_mm;
        Map<String, Integer> net_ids = new HashMap<String, Integer>();
        for (String layer : new String[]
        {
            board.layers.get(0), board.layers.get(board.layers.size() - 1)
        })
        {
            List<EeBoard.Pad> on_layer = new ArrayList<EeBoard.Pad>();
            for (EeBoard.Pad pad : board.pads)
            {
                if (pad.through || pad.layer.equals(layer))
                {
                    on_layer.add(pad);
                }
            }
            boolean any_smd = false;
            for (EeBoard.Pad pad : on_layer)
            {
                any_smd |= !pad.through;
            }
            if (!any_smd)
            {
                continue;
            }
            // owner of a cell: 0 free, k > 0 only net k may pass, -1 closed for all
            int[] owner = new int[nx * ny];
            int next_private = 1_000_000;
            for (EeBoard.Pad pad : on_layer)
            {
                int id;
                if (pad.net.isEmpty() || !nets.containsKey(pad.net))
                {
                    id = next_private++;
                }
                else
                {
                    Integer known = net_ids.get(pad.net);
                    if (known == null)
                    {
                        known = net_ids.size() + 1;
                        net_ids.put(pad.net, known);
                    }
                    id = known;
                }
                double[] pb = pad.box();
                mark(owner, nx, ny, bb, pb[0] - reach, pb[1] - reach, pb[2] + reach, pb[3] + reach, id, (x, y) -> distance(pad, x, y) <= reach);
            }
            for (EeBoard.Region solid : board.solids)
            {
                if (solid.layer.equals(layer) && solid.net.isEmpty())
                {
                    double[] sb = box_of(solid.points);
                    final double[] pts = solid.points;
                    mark(owner, nx, ny, bb, sb[0] - reach, sb[1] - reach, sb[2] + reach, sb[3] + reach, -1, (x, y) -> distance(pts, x, y) <= reach);
                }
            }
            for (Keepout k : keepouts)
            {
                if (k.layer.equals(layer))
                {
                    mark(owner, nx, ny, bb, k.x0 - reach, k.y0 - reach, k.x1 + reach, k.y1 + reach, -1,
                            (x, y) -> x >= k.x0 - reach && x <= k.x1 + reach && y >= k.y0 - reach && y <= k.y1 + reach);
                }
            }
            // the rim of the board
            double rim = rules.copper_to_edge_mm + board.track_mm / 2.0;
            final double[] outline = board.outline;
            for (int j = 0; j < ny; ++j)
            {
                for (int i = 0; i < nx; ++i)
                {
                    double x = bb[0] + i * CELL_MM, y = bb[1] + j * CELL_MM;
                    if (x < bb[0] + rim || x > bb[2] - rim || y < bb[1] + rim || y > bb[3] - rim)
                    {
                        if (distance_to_edge(outline, x, y) < rim)
                        {
                            owner[j * nx + i] = -1;
                        }
                    }
                }
            }
            for (EeBoard.Pad pad : on_layer)
            {
                if (pad.through || pad.net.isEmpty() || !nets.containsKey(pad.net) || touches_copper_without_net(pad) || has_through_copper(pad)
                        || has_kept_copper(pad) || pour_pads.contains(pad))
                {
                    continue;
                }
                int id = net_ids.get(pad.net);
                double[] pb = pad.box();
                double need = Math.max(1.0, Math.hypot(pb[2] - pb[0], pb[3] - pb[1]) / 2.0 + 4.0 * (board.track_mm + board.clearance_mm));
                if ((pad.ref + "." + pad.number).equals(System.getProperty("bridge.test.exit_map")))
                {
                    print_exit_map(owner, nx, ny, bb, pad, id, need);
                }
                if (!has_exit(owner, nx, ny, bb, pad, id, need))
                {
                    if (via_fits(pad))
                    {
                        PadVia via = new PadVia();
                        via.pad = pad;
                        via.x = pad.cx;
                        via.y = pad.cy;
                        pad_vias.add(via);
                    }
                    else
                    {
                        pads_without_exit_and_via.add(pad);
                    }
                }
            }
        }
        if (!pad_vias.isEmpty())
        {
            Map<String, Integer> by_part = new java.util.TreeMap<String, Integer>();
            for (PadVia via : pad_vias)
            {
                by_part.merge(via.pad.ref, 1, Integer::sum);
            }
            notes.add(pad_vias.size() + " pads have no exit on their layer and get a via of " + rules.pad_via_diameter_mm + " mm in the pad: " + by_part
                    + ". The board needs filled and capped vias for these.");
        }
        if (!pads_without_exit_and_via.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            for (EeBoard.Pad pad : pads_without_exit_and_via)
            {
                sb.append(sb.length() > 0 ? ", " : "").append(pad.ref).append('.').append(pad.number);
            }
            notes.add(pads_without_exit_and_via.size() + " pads have no exit and no room for a via in the pad: " + sb);
        }
    }

    /**
     * Pads of plane nets on small parts (resistors, capacitors, diodes, transistors) get a short track to a standard
     * via beside the pad before the engine starts. Without this, signal tracks that are routed first can close the
     * last free position for the via of such a pad.
     */
    private void find_plane_stubs()
    {
        double via_r = via_diameter_mm / 2.0;
        double half = board.track_mm / 2.0;
        double cl = board.clearance_mm;
        boolean keep = rules.existing.equals("keep");

        List<Copper> fixed = Copper.of_footprints(board);
        for (EeBoard.Track track : board.tracks)
        {
            if ((keep || track.locked) && board.layers.contains(track.layer))
            {
                for (int i = 0; i + 3 < track.points.length; i += 2)
                {
                    Copper c = Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0,
                            track.layer, track.net);
                    c.routed = true;
                    fixed.add(c);
                }
            }
        }
        for (EeBoard.Via via : board.vias)
        {
            if (keep || via.locked)
            {
                Copper c = Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net);
                c.routed = true;
                fixed.add(c);
            }
        }
        for (PadVia via : pad_vias)
        {
            Copper c = Copper.disc(via.x, via.y, rules.pad_via_diameter_mm / 2.0, null, via.pad.net);
            c.routed = true;
            fixed.add(c);
        }
        List<Copper> stub_vias = new ArrayList<Copper>();

        Map<String, List<EeBoard.Pad>> by_part = new LinkedHashMap<String, List<EeBoard.Pad>>();
        for (EeBoard.Pad pad : board.pads)
        {
            by_part.computeIfAbsent(pad.ref, k -> new ArrayList<EeBoard.Pad>()).add(pad);
        }
        Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
        int shared = 0;
        for (EeBoard.Pad pad : board.pads)
        {
            List<EeBoard.Region> areas = planes.get(pad.net);
            List<EeBoard.Pad> part = by_part.get(pad.ref);
            if (areas == null || pad.through || !nets.containsKey(pad.net) || pad.ref.isEmpty() || part.size() > 4 || touches_copper_without_net(pad)
                    || has_through_copper(pad))
            {
                continue;
            }
            boolean done = false;
            for (PadVia via : pad_vias)
            {
                done |= via.pad == pad;
            }
            Copper pad_copper = pad.polygon != null ? Copper.polygon(pad.polygon, pad.layer, pad.net) : Copper.disc(pad.cx, pad.cy, pad.radius, pad.layer, pad.net);
            for (Copper c : fixed)
            {
                // existing copper of the net that touches the pad: the pad has its connection
                done |= c.routed && c.net.equals(pad.net) && c.shares_layer(pad_copper) && !c.far_from(pad_copper, 0.0) && c.distance(pad_copper) <= 1e-6;
            }
            if (done)
            {
                continue;
            }
            // 1. the via of an earlier stub of the same net, when it is near
            Copper near = null;
            double near_distance = 1.2;
            for (Copper via : stub_vias)
            {
                double d = Math.hypot(via.x0 - pad.cx, via.y0 - pad.cy);
                if (via.net.equals(pad.net) && d < near_distance && stub_is_free(pad, via.x0, via.y0, half, cl, fixed))
                {
                    near = via;
                    near_distance = d;
                }
            }
            Stub stub = null;
            if (near != null)
            {
                stub = new Stub();
                stub.x = near.x0;
                stub.y = near.y0;
                stub.new_via = false;
                ++shared;
            }
            else
            {
                // 2. a new via beside the pad: first away from the middle of the part
                double mx = 0, my = 0;
                for (EeBoard.Pad other : part)
                {
                    mx += other.cx;
                    my += other.cy;
                }
                final double ox = pad.cx - mx / part.size(), oy = pad.cy - my / part.size();
                List<double[]> directions = new ArrayList<double[]>();
                for (int k = 0; k < 8; ++k)
                {
                    directions.add(new double[]
                    {
                        Math.cos(k * Math.PI / 4.0), Math.sin(k * Math.PI / 4.0)
                    });
                }
                directions.sort((a, b) -> Double.compare(b[0] * ox + b[1] * oy, a[0] * ox + a[1] * oy));
                double[] box = pad.box();
                double hw = (box[2] - box[0]) / 2.0, hh = (box[3] - box[1]) / 2.0;
                double bx = (box[0] + box[2]) / 2.0, by = (box[1] + box[3]) / 2.0;
                for (double[] d : directions)
                {
                    double extent = Math.min(Math.abs(d[0]) < 1e-9 ? Double.MAX_VALUE : hw / Math.abs(d[0]),
                            Math.abs(d[1]) < 1e-9 ? Double.MAX_VALUE : hh / Math.abs(d[1]));
                    // The nearest point of the pad is not always on the line to the via. Move out until the via is clear of its own pad.
                    double reach = extent + cl + via_r + PLACE_MARGIN_MM;
                    double x = bx + d[0] * reach, y = by + d[1] * reach;
                    for (int step = 0; step < 8 && pad_copper.distance(Copper.disc(x, y, via_r, null, pad.net)) < cl + PLACE_MARGIN_MM; ++step)
                    {
                        reach += 0.05;
                        x = bx + d[0] * reach;
                        y = by + d[1] * reach;
                    }
                    boolean in_plane = false;
                    for (EeBoard.Region area : areas)
                    {
                        in_plane |= EeCheck.inside(area.points, x, y);
                    }
                    if (!in_plane || !stub_via_is_free(pad, x, y, via_r, cl, fixed, stub_vias) || !stub_is_free(pad, x, y, half, cl, fixed))
                    {
                        continue;
                    }
                    stub = new Stub();
                    stub.x = x;
                    stub.y = y;
                    stub.new_via = true;
                    Copper via = Copper.disc(x, y, via_r, null, pad.net);
                    via.routed = true;
                    stub_vias.add(via);
                    fixed.add(via);
                    break;
                }
            }
            if (stub == null)
            {
                continue;
            }
            stub.pad = pad;
            stubs.add(stub);
            Copper track = Copper.segment(pad.cx, pad.cy, stub.x, stub.y, half, pad.layer, pad.net);
            track.routed = true;
            fixed.add(track);
            counts.merge(pad.net, 1, Integer::sum);
        }
        if (!stubs.isEmpty())
        {
            notes.add(stubs.size() + " pads of plane nets on small parts get a short track to a standard via beside the pad before the engine starts: "
                    + counts + ". " + (stubs.size() - shared) + " new vias. They are fixed for the engine (rules: via.plane_stubs).");
        }
    }

    /** true: a track from the centre of the pad to the point keeps the clearance to the copper of other nets on the layer of the pad. */
    private boolean stub_is_free(EeBoard.Pad p_pad, double p_x, double p_y, double p_half, double p_clearance, List<Copper> p_fixed)
    {
        Copper track = Copper.segment(p_pad.cx, p_pad.cy, p_x, p_y, p_half, p_pad.layer, p_pad.net);
        for (Copper c : p_fixed)
        {
            if ((c.net.equals(p_pad.net) && !c.net.isEmpty()) || !c.shares_layer(track) || c.far_from(track, p_clearance))
            {
                continue;
            }
            if (c.distance(track) < p_clearance + PLACE_MARGIN_MM)
            {
                return false;
            }
        }
        for (Keepout k : keepouts)
        {
            if (k.layer.equals(p_pad.layer) && track.bx1 > k.x0 && track.bx0 < k.x1 && track.by1 > k.y0 && track.by0 < k.y1)
            {
                Copper box = Copper.polygon(new double[]
                {
                    k.x0, k.y0, k.x1, k.y0, k.x1, k.y1, k.x0, k.y1
                }, k.layer, "");
                if (box.centre_distance(track) < p_half + p_clearance)
                {
                    return false;
                }
            }
        }
        return true;
    }

    /** true: a standard via at the point keeps all rules. */
    private boolean stub_via_is_free(EeBoard.Pad p_pad, double p_x, double p_y, double p_r, double p_clearance, List<Copper> p_fixed,
            List<Copper> p_stub_vias)
    {
        Copper via = Copper.disc(p_x, p_y, p_r, null, p_pad.net);
        for (Copper c : p_fixed)
        {
            if (c.far_from(via, p_clearance))
            {
                continue;
            }
            boolean same_net = c.net.equals(p_pad.net) && !c.net.isEmpty();
            double d = c.distance(via);
            if (!same_net && d < p_clearance + PLACE_MARGIN_MM)
            {
                return false;
            }
            // A standard via keeps the clearance to a surface-mount pad of its own net too. The engine demands this
            // for a via type that is not permitted on pads. Two vias do not overlap.
            if (same_net && d < p_clearance + PLACE_MARGIN_MM && (c.kind == Copper.DISC || (c.label.startsWith("pad ") && c.layer != null)))
            {
                return false;
            }
        }
        for (Copper other : p_stub_vias)
        {
            if (Math.hypot(other.x0 - p_x, other.y0 - p_y) < 2.0 * p_r + p_clearance)
            {
                return false;
            }
        }
        double space = p_r + p_clearance;
        for (Keepout k : keepouts)
        {
            if (p_x > k.x0 - space && p_x < k.x1 + space && p_y > k.y0 - space && p_y < k.y1 + space)
            {
                return false;
            }
        }
        for (KeepoutCircle c : hole_keepouts)
        {
            if (Math.hypot(c.x - p_x, c.y - p_y) < c.radius + space)
            {
                return false;
            }
        }
        return distance_to_edge(board.outline, p_x, p_y) >= p_r + rules.copper_to_edge_mm + PLACE_MARGIN_MM;
    }

    /** Pads that the rules name get a via in the pad. */
    private void add_listed_pads()
    {
        List<String> placed = new ArrayList<String>();
        for (String name : rules.pad_via_list)
        {
            EeBoard.Pad found = null;
            for (EeBoard.Pad pad : board.pads)
            {
                if ((pad.ref + "." + pad.number).equalsIgnoreCase(name))
                {
                    found = pad;
                    break;
                }
            }
            String reason = null;
            if (found == null)
            {
                reason = "the board has no such pad";
            }
            else if (found.through)
            {
                reason = "the pad has a hole";
            }
            else if (!nets.containsKey(found.net))
            {
                reason = "its net is not routed";
            }
            else
            {
                boolean has_via = false;
                for (PadVia via : pad_vias)
                {
                    has_via |= via.pad == found;
                }
                if (has_via || has_kept_copper(found))
                {
                    continue;
                }
                if (!via_fits(found))
                {
                    reason = "a via in the pad does not keep the clearance";
                }
            }
            if (reason != null)
            {
                notes.add("rules.json: via.pads: " + name + " gets no via (" + reason + ").");
                continue;
            }
            PadVia via = new PadVia();
            via.pad = found;
            via.x = found.cx;
            via.y = found.cy;
            pad_vias.add(via);
            placed.add(name);
        }
        if (!placed.isEmpty())
        {
            notes.add(placed.size() + " pads named in the rules get a via of " + rules.pad_via_diameter_mm + " mm in the pad: " + String.join(", ", placed)
                    + ".");
        }
    }

    /**
     * Pads that the pour of their net is expected to join: the engine does not get them as pads of the net.
     * The net must have copper areas on 2 or more layers, so that a via can join an island of one layer to another layer.
     */
    /**
     * The pads that a copper area of their net connects on their own layer: the fill of the board without routes
     * reaches the pad. The engine gets such a pad as a pad without net.
     */
    private void find_pour_pads()
    {
        List<String> weak_contact = new ArrayList<String>();
        for (Map.Entry<String, List<EeBoard.Region>> entry : fill_areas.entrySet())
        {
            String net = entry.getKey();
            // planes.supply = tracks: the area of a supply net is not taken as a connection
            if (!nets.containsKey(net) || supply_areas.containsKey(net))
            {
                continue;
            }
            EeNet graph = unrouted_fill(net);
            for (int i = 0; i < graph.items.size(); ++i)
            {
                EeBoard.Pad pad = graph.pad_of.get(i);
                if (pad == null || has_kept_copper(pad) || is_listed(pad)
                        || (rework != null && (rework.net_pads.contains(pad) || (rework.via_pads.contains(pad) && has_engine_plane(net)))))
                {
                    continue;
                }
                boolean reached = false;
                List<EeBoard.Region> areas = entry.getValue();
                for (int k = 0; k < areas.size() && k < graph.planes.size(); ++k)
                {
                    EeBoard.Region area = areas.get(k);
                    // A pad with a hole is on every layer. The engine connects it to a plane by itself, so it is a pour pad only without a plane.
                    boolean on_layer = pad.through ? !has_engine_plane(net) : area.layer.equals(pad.layer);
                    if (!on_layer || !EeCheck.inside(area.points, pad.cx, pad.cy) || graph.planes.get(k).islands_at(graph.items.get(i)).isEmpty())
                    {
                        continue;
                    }
                    // The fill must be beside a real part of the pad outline. A contact at one corner is not taken as a connection.
                    double contact = graph.planes.get(k).contact(pad);
                    if (contact + 1e-9 >= rules.pour_contact)
                    {
                        reached = true;
                    }
                    else
                    {
                        weak_contact.add(pad.ref + "." + pad.number);
                    }
                }
                if (reached)
                {
                    pour_pads.add(pad);
                }
            }
        }
        weak_contact.removeIf(name -> {
            for (EeBoard.Pad pad : pour_pads)
            {
                if ((pad.ref + "." + pad.number).equals(name))
                {
                    return true;
                }
            }
            return false;
        });
        if (!weak_contact.isEmpty())
        {
            notes.add(weak_contact.size() + " pads lie in a copper area of their net on their own layer, but the fill is beside less than "
                    + Math.round(rules.pour_contact * 100) + " % of the pad outline. They are not taken as connected by the area: "
                    + String.join(", ", weak_contact) + " (rules: planes.pour_contact).");
        }
    }

    /** true: the rules name the pad for a via in the pad. */
    private boolean is_listed(EeBoard.Pad p_pad)
    {
        for (String name : rules.pad_via_list)
        {
            if ((p_pad.ref + "." + p_pad.number).equalsIgnoreCase(name))
            {
                return true;
            }
        }
        return false;
    }

    private static Copper copper_of_pad(EeBoard.Pad p_pad)
    {
        String layer = p_pad.through ? null : p_pad.layer;
        return p_pad.polygon != null ? Copper.polygon(p_pad.polygon, layer, p_pad.net) : Copper.disc(p_pad.cx, p_pad.cy, p_pad.radius, layer, p_pad.net);
    }

    /** The last step for the pour pads, after the vias in pads are planned. */
    private void settle_pour_pads()
    {
        // A pad with a via into an area that the engine does not have as a plane: for the engine the pad and its via are
        // a net of their own. The engine then makes no track for the pad.
        for (PadVia via : pad_vias)
        {
            String net = via.pad.net;
            if (fill_areas.containsKey(net) && nets.containsKey(net) && !has_engine_plane(net) && !supply_areas.containsKey(net)
                    && (via_reaches_area(via.pad) || (rework != null && rework.via_pads.contains(via.pad))))
            {
                pour_pads.add(via.pad);
            }
        }
        // A net without a plane for the engine: the engine does not know that the copper area joins the pour pads to
        // the other pads of the net. One pour pad stays in the net. The engine routes the other pads to it.
        for (String net : fill_areas.keySet())
        {
            if (!nets.containsKey(net) || has_engine_plane(net) || supply_areas.containsKey(net))
            {
                continue;
            }
            List<EeBoard.Pad> rest = new ArrayList<EeBoard.Pad>();
            List<EeBoard.Pad> pour = new ArrayList<EeBoard.Pad>();
            for (EeBoard.Pad pad : nets.get(net))
            {
                (pour_pads.contains(pad) ? pour : rest).add(pad);
            }
            if (rest.isEmpty() || pour.isEmpty())
            {
                continue;
            }
            EeBoard.Pad anchor = null;
            double best = Double.MAX_VALUE;
            for (EeBoard.Pad pad : pour)
            {
                for (EeBoard.Pad other : rest)
                {
                    double d = Math.hypot(pad.cx - other.cx, pad.cy - other.cy);
                    if (d < best)
                    {
                        best = d;
                        anchor = pad;
                    }
                }
            }
            pour_pads.remove(anchor);
            notes.add("Net " + net + " has no plane for the engine. Pad " + anchor.ref + "." + anchor.number + " in its copper area stays in the net. The engine routes the "
                    + rest.size() + " pads that the area does not connect to this pad.");
        }
        // A pour pad that is nearer than the clearance to a pad of its net that stays in the net (the plated holes in
        // an exposed pad, a pad with a via into a plane) stays in the net too. As a pad without net it would be a clearance error.
        boolean changed = true;
        while (changed)
        {
            changed = false;
            for (EeBoard.Pad pad : new ArrayList<EeBoard.Pad>(pour_pads))
            {
                Copper a = copper_of_pad(pad);
                for (EeBoard.Pad other : nets.get(pad.net))
                {
                    if (other == pad || pour_pads.contains(other))
                    {
                        continue;
                    }
                    Copper b = copper_of_pad(other);
                    if (a.shares_layer(b) && !a.far_from(b, board.clearance_mm + 0.01) && a.distance(b) < board.clearance_mm + 0.01)
                    {
                        pour_pads.remove(pad);
                        changed = true;
                        break;
                    }
                }
            }
        }
        // Pour pads of one net that are nearer to each other than the clearance (the segments of a ring pad) stay
        // together in a net of their own for the engine. As pads without net they would be clearance errors.
        // A pour pad with a via is in such a net too, so that its via has a net.
        java.util.Set<EeBoard.Pad> with_via = new java.util.HashSet<EeBoard.Pad>();
        for (PadVia via : pad_vias)
        {
            with_via.add(via.pad);
        }
        List<EeBoard.Pad> list = new ArrayList<EeBoard.Pad>(pour_pads);
        list.sort((a, b) -> Integer.compare(a.index, b.index));
        int[] parent = new int[list.size()];
        for (int i = 0; i < parent.length; ++i)
        {
            parent[i] = i;
        }
        List<Copper> shapes = new ArrayList<Copper>();
        for (EeBoard.Pad pad : list)
        {
            shapes.add(copper_of_pad(pad));
        }
        for (int i = 0; i < list.size(); ++i)
        {
            for (int j = i + 1; j < list.size(); ++j)
            {
                Copper a = shapes.get(i), b = shapes.get(j);
                if (list.get(i).net.equals(list.get(j).net) && a.shares_layer(b) && !a.far_from(b, board.clearance_mm + 0.01)
                        && a.distance(b) < board.clearance_mm + 0.01)
                {
                    int ra = i, rb = j;
                    while (parent[ra] != ra)
                    {
                        ra = parent[ra];
                    }
                    while (parent[rb] != rb)
                    {
                        rb = parent[rb];
                    }
                    parent[ra] = rb;
                }
            }
        }
        Map<Integer, List<EeBoard.Pad>> clusters = new LinkedHashMap<Integer, List<EeBoard.Pad>>();
        for (int i = 0; i < list.size(); ++i)
        {
            int r = i;
            while (parent[r] != r)
            {
                r = parent[r];
            }
            clusters.computeIfAbsent(r, k -> new ArrayList<EeBoard.Pad>()).add(list.get(i));
        }
        for (List<EeBoard.Pad> cluster : clusters.values())
        {
            boolean any_via = false;
            for (EeBoard.Pad pad : cluster)
            {
                any_via |= with_via.contains(pad);
            }
            if (cluster.size() > 1 || any_via)
            {
                pour_clusters.add(cluster);
            }
        }
        Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
        Map<String, Integer> via_counts = new java.util.TreeMap<String, Integer>();
        for (EeBoard.Pad pad : pour_pads)
        {
            (with_via.contains(pad) ? via_counts : counts).merge(pad.net, 1, Integer::sum);
        }
        if (!counts.isEmpty())
        {
            int total = 0;
            for (int value : counts.values())
            {
                total += value;
            }
            notes.add(total + " pads lie in a copper area of their net on their own layer, and the fill reaches them: " + counts
                    + ". The engine makes no track and no via for them. After routing the bridge models the fill and repairs the pads that tracks cut off"
                    + " (rules: planes.pours).");
        }
        if (!via_counts.isEmpty())
        {
            notes.add("Pads whose via in the pad is the connection to a copper area that the engine does not have as a plane: " + via_counts + ".");
        }
    }

    /** true: the layer is the top or the bottom layer. */
    boolean is_outer(String p_layer)
    {
        int index = board.layers.indexOf(p_layer);
        return index == 0 || index == board.layers.size() - 1;
    }

    /** Existing tracks and vias that stay on the board, as copper. Built once. */
    private List<Copper> kept_copper;

    private List<Copper> kept_copper()
    {
        if (kept_copper == null)
        {
            kept_copper = new ArrayList<Copper>();
            boolean keep = rules.existing.equals("keep");
            for (EeBoard.Track track : board.tracks)
            {
                if ((keep || track.locked) && board.layers.contains(track.layer))
                {
                    for (int i = 0; i + 3 < track.points.length; i += 2)
                    {
                        kept_copper.add(Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3],
                                track.width / 2.0, track.layer, track.net));
                    }
                }
            }
            for (EeBoard.Via via : board.vias)
            {
                if (keep || via.locked)
                {
                    kept_copper.add(Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net));
                }
            }
        }
        return kept_copper;
    }

    /** true: a track or a via of the net of the pad that stays on the board touches the pad. The pad has its connection. */
    boolean has_kept_copper(EeBoard.Pad p_pad)
    {
        Copper pad = p_pad.polygon != null ? Copper.polygon(p_pad.polygon, p_pad.through ? null : p_pad.layer, p_pad.net)
                : Copper.disc(p_pad.cx, p_pad.cy, p_pad.radius, p_pad.through ? null : p_pad.layer, p_pad.net);
        for (Copper c : kept_copper())
        {
            if (c.net.equals(p_pad.net) && c.shares_layer(pad) && !c.far_from(pad, 0.0) && c.distance(pad) <= 1e-6)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Pads of a net with a plane get a via in the pad, straight into the plane. This keeps the space
     * around a fine-pitch part free for the signal tracks. The via is placed before the engine starts.
     */
    /**
     * A surface-mount pad of a net that has a copper area on a layer other than the layer of the pad gets a via in the
     * pad, into that area. The via is the whole connection of the pad: the engine makes no track for it.
     * Not for a pad that a copper area connects on its own layer (a pour pad).
     */
    private void find_plane_pads()
    {
        boolean all = rules.plane_pad_vias.equals("all");
        Map<String, Integer> by_part = new java.util.TreeMap<String, Integer>();
        List<String> no_room = new ArrayList<String>();
        int count = 0;
        for (EeBoard.Pad pad : board.pads)
        {
            List<EeBoard.Region> areas = fill_areas.get(pad.net);
            if (areas == null || pad.through || !nets.containsKey(pad.net) || pour_pads.contains(pad) || touches_copper_without_net(pad)
                    || has_through_copper(pad) || has_kept_copper(pad) || (rework != null && rework.net_pads.contains(pad)))
            {
                continue;
            }
            boolean has_via = false;
            for (PadVia via : pad_vias)
            {
                has_via |= via.pad == pad;
            }
            if (has_via)
            {
                continue;
            }
            double[] box = pad.box();
            boolean fine = Math.min(box[2] - box[0], box[3] - box[1]) <= rules.fine_pitch_pad_mm + 1e-6;
            if (!all && !fine)
            {
                continue;
            }
            // The via must stand in the fill of an area of the net on a different layer. The fill is the one of the board without routes.
            if (!via_reaches_area(pad))
            {
                continue;
            }
            if (!via_fits(pad))
            {
                no_room.add(pad.ref + "." + pad.number);
                continue;
            }
            PadVia via = new PadVia();
            via.pad = pad;
            via.x = pad.cx;
            via.y = pad.cy;
            pad_vias.add(via);
            by_part.merge(pad.ref, 1, Integer::sum);
            ++count;
        }
        if (count > 0)
        {
            notes.add(count + " pads get a via of " + rules.pad_via_diameter_mm + " mm in the pad, into a copper area of their net on a different layer: "
                    + by_part + ". The engine makes no track for them." + (all ? "" : " These are pads up to " + rules.fine_pitch_pad_mm + " mm wide.")
                    + " (rules: via.plane_pads)");
        }
        if (!no_room.isEmpty())
        {
            notes.add(no_room.size() + " pads of nets with a copper area on a different layer cannot take a via in the pad, because the via does not keep the"
                    + " clearance. The engine connects them: " + String.join(", ", no_room) + ".");
        }
    }

    /** The fill of the copper areas of a net on the board without routes. */
    private EeNet unrouted_fill(String p_net)
    {
        EeNet graph = unrouted_fills.get(p_net);
        if (graph == null)
        {
            boolean locked_only = rules.existing.equals("remove");
            graph = new EeNet(board, this, p_net, EeNet.copper_of(board, this, locked_only), locked_only);
            unrouted_fills.put(p_net, graph);
        }
        return graph;
    }

    private final Map<String, EeNet> unrouted_fills = new HashMap<String, EeNet>();

    /** true: a via in the centre of the pad stands in the fill of an area of the net of the pad on a layer other than the layer of the pad. */
    private boolean via_reaches_area(EeBoard.Pad p_pad)
    {
        List<EeBoard.Region> areas = fill_areas.get(p_pad.net);
        if (areas == null)
        {
            return false;
        }
        EeNet graph = unrouted_fill(p_pad.net);
        Copper via = Copper.disc(p_pad.cx, p_pad.cy, rules.pad_via_diameter_mm / 2.0, null, p_pad.net);
        for (int k = 0; k < areas.size() && k < graph.planes.size(); ++k)
        {
            if (!areas.get(k).layer.equals(p_pad.layer) && !graph.planes.get(k).islands_at(via).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    /** true: the engine gets a copper area of the net as a plane. It then takes a pad with a via into the plane as connected. */
    private boolean has_engine_plane(String p_net)
    {
        return planes.containsKey(p_net);
    }

    /** true: the pad lies on copper without net of its own footprint, for example the feed pad of an antenna. */
    private boolean touches_copper_without_net(EeBoard.Pad p_pad)
    {
        double[] pb = p_pad.box();
        double half = Math.max(pb[2] - pb[0], pb[3] - pb[1]) / 2.0 + 0.01;
        for (EeBoard.Region solid : board.solids)
        {
            if (solid.net.isEmpty() && solid.ref.equals(p_pad.ref) && distance(solid.points, p_pad.cx, p_pad.cy) <= half)
            {
                return true;
            }
        }
        return false;
    }

    /** true: a through-hole pad or a via of the footprint with the same net lies on the pad. The pad is then on all layers already. */
    private boolean has_through_copper(EeBoard.Pad p_pad)
    {
        for (EeBoard.Pad other : board.pads)
        {
            if (other != p_pad && other.through && other.net.equals(p_pad.net) && distance(p_pad, other.cx, other.cy) <= (other.polygon == null ? other.radius : 0.0))
            {
                return true;
            }
        }
        return false;
    }

    private interface Inside
    {
        boolean test(double p_x, double p_y);
    }

    private static void mark(int[] p_owner, int p_nx, int p_ny, double[] p_bb, double p_x0, double p_y0, double p_x1, double p_y1, int p_id, Inside p_inside)
    {
        int i0 = Math.max(0, (int) Math.floor((p_x0 - p_bb[0]) / CELL_MM));
        int i1 = Math.min(p_nx - 1, (int) Math.ceil((p_x1 - p_bb[0]) / CELL_MM));
        int j0 = Math.max(0, (int) Math.floor((p_y0 - p_bb[1]) / CELL_MM));
        int j1 = Math.min(p_ny - 1, (int) Math.ceil((p_y1 - p_bb[1]) / CELL_MM));
        for (int j = j0; j <= j1; ++j)
        {
            for (int i = i0; i <= i1; ++i)
            {
                if (p_inside.test(p_bb[0] + i * CELL_MM, p_bb[1] + j * CELL_MM))
                {
                    int cell = j * p_nx + i;
                    int old = p_owner[cell];
                    p_owner[cell] = old == 0 || old == p_id ? p_id : -1;
                }
            }
        }
    }

    static double distance_to_edge(double[] p_outline, double p_x, double p_y)
    {
        int n = p_outline.length / 2;
        boolean inside = false;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double xi = p_outline[2 * i], yi = p_outline[2 * i + 1];
            double xj = p_outline[2 * j], yj = p_outline[2 * j + 1];
            if ((yi > p_y) != (yj > p_y) && p_x < (xj - xi) * (p_y - yi) / (yj - yi) + xi)
            {
                inside = !inside;
            }
            double dx = xj - xi, dy = yj - yi;
            double len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((p_x - xi) * dx + (p_y - yi) * dy) / len));
            best = Math.min(best, Math.hypot(p_x - (xi + t * dx), p_y - (yi + t * dy)));
        }
        return inside ? best : 0.0;
    }

    /** Test output: the cells around a pad. '.' free, 'o' own net, 'x' other net, '#' closed, 'P' inside the pad. */
    private static void print_exit_map(int[] p_owner, int p_nx, int p_ny, double[] p_bb, EeBoard.Pad p_pad, int p_id, double p_need)
    {
        int ci = (int) Math.round((p_pad.cx - p_bb[0]) / CELL_MM);
        int cj = (int) Math.round((p_pad.cy - p_bb[1]) / CELL_MM);
        int w = (int) Math.ceil(p_need / CELL_MM) + 4;
        System.out.println("exit map of " + p_pad.ref + "." + p_pad.number + " at " + p_pad.cx + " " + p_pad.cy + ", need " + p_need + " mm, cell " + CELL_MM + " mm, x to the right, y down");
        for (int j = cj - w; j <= cj + w; ++j)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = ci - w; i <= ci + w; ++i)
            {
                if (i < 0 || j < 0 || i >= p_nx || j >= p_ny)
                {
                    sb.append(' ');
                    continue;
                }
                int o = p_owner[j * p_nx + i];
                boolean in_pad = distance(p_pad, p_bb[0] + i * CELL_MM, p_bb[1] + j * CELL_MM) <= 0.0;
                sb.append(in_pad ? (o == p_id ? 'P' : 'p') : o == 0 ? '.' : o == p_id ? 'o' : o == -1 ? '#' : 'x');
            }
            System.out.println(sb);
        }
    }

    /** Breadth-first search over free cells from the pad. true when a cell at the distance p_need from the pad centre is reached. */
    private static boolean has_exit(int[] p_owner, int p_nx, int p_ny, double[] p_bb, EeBoard.Pad p_pad, int p_id, double p_need)
    {
        int ci = (int) Math.round((p_pad.cx - p_bb[0]) / CELL_MM);
        int cj = (int) Math.round((p_pad.cy - p_bb[1]) / CELL_MM);
        int window = (int) Math.ceil(p_need / CELL_MM) + 2;
        int size = 2 * window + 1;
        boolean[] seen = new boolean[size * size];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<int[]>();
        // start cells: all cells inside the pad
        double[] pb = p_pad.box();
        for (int j = (int) Math.floor((pb[1] - p_bb[1]) / CELL_MM); j <= (int) Math.ceil((pb[3] - p_bb[1]) / CELL_MM); ++j)
        {
            for (int i = (int) Math.floor((pb[0] - p_bb[0]) / CELL_MM); i <= (int) Math.ceil((pb[2] - p_bb[0]) / CELL_MM); ++i)
            {
                int wi = i - ci + window, wj = j - cj + window;
                if (wi < 0 || wj < 0 || wi >= size || wj >= size || i < 0 || j < 0 || i >= p_nx || j >= p_ny)
                {
                    continue;
                }
                if (distance(p_pad, p_bb[0] + i * CELL_MM, p_bb[1] + j * CELL_MM) <= 0.0)
                {
                    int o = p_owner[j * p_nx + i];
                    if (o == p_id)
                    {
                        seen[wj * size + wi] = true;
                        queue.add(new int[]
                        {
                            i, j
                        });
                    }
                }
            }
        }
        if (queue.isEmpty())
        {
            // The pad is covered by the clearance of other copper. It has no exit.
            return false;
        }
        while (!queue.isEmpty())
        {
            int[] cell = queue.poll();
            double dx = (cell[0] - ci) * CELL_MM, dy = (cell[1] - cj) * CELL_MM;
            if (Math.hypot(dx, dy) >= p_need)
            {
                return true;
            }
            for (int dj = -1; dj <= 1; ++dj)
            {
                for (int di = -1; di <= 1; ++di)
                {
                    if (di == 0 && dj == 0)
                    {
                        continue;
                    }
                    int i = cell[0] + di, j = cell[1] + dj;
                    int wi = i - ci + window, wj = j - cj + window;
                    if (wi < 0 || wj < 0 || wi >= size || wj >= size || i < 0 || j < 0 || i >= p_nx || j >= p_ny || seen[wj * size + wi])
                    {
                        continue;
                    }
                    int o = p_owner[j * p_nx + i];
                    if (o != 0 && o != p_id)
                    {
                        continue;
                    }
                    seen[wj * size + wi] = true;
                    queue.add(new int[]
                    {
                        i, j
                    });
                }
            }
        }
        return false;
    }

    /** true: a pad via in the centre of the pad keeps the clearance to all other copper on all layers. */
    private boolean via_fits(EeBoard.Pad p_pad)
    {
        double r = rules.pad_via_diameter_mm / 2.0;
        double need = r + board.clearance_mm + 0.0005;
        for (EeBoard.Pad other : board.pads)
        {
            if (other == p_pad || (!other.net.isEmpty() && other.net.equals(p_pad.net)))
            {
                continue;
            }
            // a surface-mount pad is on one layer, the via is on all layers, so every pad counts
            if (distance(other, p_pad.cx, p_pad.cy) < need)
            {
                return false;
            }
        }
        for (PadVia via : pad_vias)
        {
            if (!via.pad.net.equals(p_pad.net) && Math.hypot(via.x - p_pad.cx, via.y - p_pad.cy) < 2.0 * r + board.clearance_mm + 0.0005)
            {
                return false;
            }
        }
        for (EeBoard.Region solid : board.solids)
        {
            if (solid.net.isEmpty() && distance(solid.points, p_pad.cx, p_pad.cy) < need)
            {
                return false;
            }
        }
        // copper of other nets that stays on the board
        boolean keep = rules.existing.equals("keep");
        List<EeBoard.Track> fixed = new ArrayList<EeBoard.Track>(board.footprint_tracks);
        for (EeBoard.Track track : board.tracks)
        {
            if (keep || track.locked)
            {
                fixed.add(track);
            }
        }
        for (EeBoard.Track track : fixed)
        {
            if (track.net.equals(p_pad.net) || !board.layers.contains(track.layer))
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                if (EeCheck.segment_distance(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], p_pad.cx, p_pad.cy, p_pad.cx,
                        p_pad.cy) < need + track.width / 2.0)
                {
                    return false;
                }
            }
        }
        for (EeBoard.Via other : board.vias)
        {
            if ((keep || other.locked) && !other.net.equals(p_pad.net)
                    && Math.hypot(other.x - p_pad.cx, other.y - p_pad.cy) < need + other.diameter / 2.0)
            {
                return false;
            }
        }
        for (Keepout k : keepouts)
        {
            if (p_pad.cx > k.x0 - need && p_pad.cx < k.x1 + need && p_pad.cy > k.y0 - need && p_pad.cy < k.y1 + need)
            {
                return false;
            }
        }
        for (KeepoutCircle c : hole_keepouts)
        {
            if (Math.hypot(c.x - p_pad.cx, c.y - p_pad.cy) < c.radius + need)
            {
                return false;
            }
        }
        return distance_to_edge(board.outline, p_pad.cx, p_pad.cy) >= r + rules.copper_to_edge_mm;
    }
}
