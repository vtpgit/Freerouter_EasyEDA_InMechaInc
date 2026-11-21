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
 * Connection check of a board file. It does not use the engine.
 *
 * For each net of the plan it joins the copper that touches on the same layer: pads, tracks, vias,
 * copper regions of footprints and the plane areas of the plan. A net is complete when all its pads
 * are in one group. The count of open connections of a net is the number of groups minus 1.
 *
 * Copper areas that are not plane areas of the plan are not used, because their fill is not known
 * before the editor rebuilds them.
 */
final class EeCheck
{
    static final class NetState
    {
        String net;
        int pads;
        int groups;
        /** Pads outside the largest group, as part and pad number. */
        final List<String> loose_pads = new ArrayList<String>();
    }

    /** The fill of one plane area. */
    static final class PlaneState
    {
        String net;
        String layer;
        /** Separate pieces of the fill. */
        int islands;
        /** Pieces that reach copper of the net. */
        int islands_with_copper;
    }

    final Map<String, NetState> nets = new LinkedHashMap<String, NetState>();
    final List<PlaneState> planes = new ArrayList<PlaneState>();
    int open_connections = 0;
    int open_nets = 0;

    private static final double EPS = 1e-6;

    /** One piece of copper. kind 0: disc, 1: track segment, 2: polygon. layer null: all layers. */
    private static final class Item
    {
        int kind;
        String layer;
        double x0, y0, x1, y1, r;
        double[] polygon;
        double bx0, by0, bx1, by1;
        EeBoard.Pad pad;

        void bounds()
        {
            if (kind == 2)
            {
                bx0 = by0 = Double.MAX_VALUE;
                bx1 = by1 = -Double.MAX_VALUE;
                for (int i = 0; i + 1 < polygon.length; i += 2)
                {
                    bx0 = Math.min(bx0, polygon[i]);
                    bx1 = Math.max(bx1, polygon[i]);
                    by0 = Math.min(by0, polygon[i + 1]);
                    by1 = Math.max(by1, polygon[i + 1]);
                }
            }
            else
            {
                bx0 = Math.min(x0, x1) - r;
                bx1 = Math.max(x0, x1) + r;
                by0 = Math.min(y0, y1) - r;
                by1 = Math.max(y0, y1) + r;
            }
        }
    }

    /**
     * @param p_board the board to check
     * @param p_plan  the plan of the same board or of the board before routing. It gives the nets and the plane areas.
     */
    EeCheck(EeBoard p_board, EePlan p_plan)
    {
        this(p_board, p_plan, false);
    }

    /**
     * @param p_locked_only true: tracks and vias that are not locked are left out. This is the board as the engine gets it
     *                      when the rules remove the existing copper.
     */
    EeCheck(EeBoard p_board, EePlan p_plan, boolean p_locked_only)
    {
        List<Copper> copper = p_plan.fill_areas.isEmpty() ? new ArrayList<Copper>() : EeNet.copper_of(p_board, p_plan, p_locked_only);
        for (String net : p_plan.nets.keySet())
        {
            EeNet graph = new EeNet(p_board, p_plan, net, copper, p_locked_only);
            Map<Integer, List<EeBoard.Pad>> groups = graph.pad_groups();
            NetState state = new NetState();
            state.net = net;
            state.groups = groups.size();
            List<EeBoard.Pad> largest = null;
            for (List<EeBoard.Pad> group : groups.values())
            {
                state.pads += group.size();
                if (largest == null || group.size() > largest.size())
                {
                    largest = group;
                }
            }
            for (List<EeBoard.Pad> group : groups.values())
            {
                if (group != largest)
                {
                    for (EeBoard.Pad pad : group)
                    {
                        state.loose_pads.add(pad.ref + "." + pad.number);
                    }
                }
            }
            nets.put(net, state);
            if (state.groups > 1)
            {
                open_connections += state.groups - 1;
                ++open_nets;
            }
            // the fill of each plane area of the net
            for (EePlane plane : graph.planes)
            {
                java.util.Set<Integer> used = new java.util.HashSet<Integer>();
                for (Copper item : graph.items)
                {
                    used.addAll(plane.islands_at(item));
                }
                PlaneState ps = new PlaneState();
                ps.net = net;
                ps.layer = plane.area.layer;
                ps.islands = plane.island_count;
                ps.islands_with_copper = used.size();
                planes.add(ps);
            }
        }
    }

    private static int find(int[] p_parent, int p_i)
    {
        while (p_parent[p_i] != p_i)
        {
            p_parent[p_i] = p_parent[p_parent[p_i]];
            p_i = p_parent[p_i];
        }
        return p_i;
    }

    private static boolean touch(Item p_a, Item p_b)
    {
        if (p_a.kind == 2 && p_b.kind == 2)
        {
            return polygons_touch(p_a.polygon, p_b.polygon);
        }
        if (p_a.kind == 2)
        {
            return polygon_touches(p_a.polygon, p_b);
        }
        if (p_b.kind == 2)
        {
            return polygon_touches(p_b.polygon, p_a);
        }
        return segment_distance(p_a.x0, p_a.y0, p_a.x1, p_a.y1, p_b.x0, p_b.y0, p_b.x1, p_b.y1) <= p_a.r + p_b.r + EPS;
    }

    /** A polygon against a disc or a track segment. */
    private static boolean polygon_touches(double[] p_polygon, Item p_item)
    {
        if (inside(p_polygon, p_item.x0, p_item.y0) || inside(p_polygon, p_item.x1, p_item.y1))
        {
            return true;
        }
        int n = p_polygon.length / 2;
        for (int i = 0; i < n; ++i)
        {
            int j = (i + 1) % n;
            if (segment_distance(p_polygon[2 * i], p_polygon[2 * i + 1], p_polygon[2 * j], p_polygon[2 * j + 1], p_item.x0, p_item.y0, p_item.x1,
                    p_item.y1) <= p_item.r + EPS)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean polygons_touch(double[] p_a, double[] p_b)
    {
        if (inside(p_a, p_b[0], p_b[1]) || inside(p_b, p_a[0], p_a[1]))
        {
            return true;
        }
        int na = p_a.length / 2;
        int nb = p_b.length / 2;
        for (int i = 0; i < na; ++i)
        {
            int i2 = (i + 1) % na;
            for (int j = 0; j < nb; ++j)
            {
                int j2 = (j + 1) % nb;
                if (segment_distance(p_a[2 * i], p_a[2 * i + 1], p_a[2 * i2], p_a[2 * i2 + 1], p_b[2 * j], p_b[2 * j + 1], p_b[2 * j2],
                        p_b[2 * j2 + 1]) <= EPS)
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static double[] centre(double[] p_polygon)
    {
        double x = 0, y = 0;
        int n = p_polygon.length / 2;
        for (int i = 0; i < n; ++i)
        {
            x += p_polygon[2 * i];
            y += p_polygon[2 * i + 1];
        }
        return new double[]
        {
            x / n, y / n
        };
    }

    /** Even-odd rule. */
    static boolean inside(double[] p_polygon, double p_x, double p_y)
    {
        boolean result = false;
        int n = p_polygon.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            double xi = p_polygon[2 * i], yi = p_polygon[2 * i + 1];
            double xj = p_polygon[2 * j], yj = p_polygon[2 * j + 1];
            if ((yi > p_y) != (yj > p_y) && p_x < (xj - xi) * (p_y - yi) / (yj - yi) + xi)
            {
                result = !result;
            }
        }
        return result;
    }

    private static double point_segment(double p_x, double p_y, double p_ax, double p_ay, double p_bx, double p_by)
    {
        double dx = p_bx - p_ax, dy = p_by - p_ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, ((p_x - p_ax) * dx + (p_y - p_ay) * dy) / len2));
        return Math.hypot(p_x - (p_ax + t * dx), p_y - (p_ay + t * dy));
    }

    private static double cross(double p_ax, double p_ay, double p_bx, double p_by, double p_cx, double p_cy)
    {
        return (p_bx - p_ax) * (p_cy - p_ay) - (p_by - p_ay) * (p_cx - p_ax);
    }

    /** Distance between two segments. A segment with equal ends is a point. */
    static double segment_distance(double p_ax, double p_ay, double p_bx, double p_by, double p_cx, double p_cy, double p_dx, double p_dy)
    {
        double d1 = cross(p_cx, p_cy, p_dx, p_dy, p_ax, p_ay);
        double d2 = cross(p_cx, p_cy, p_dx, p_dy, p_bx, p_by);
        double d3 = cross(p_ax, p_ay, p_bx, p_by, p_cx, p_cy);
        double d4 = cross(p_ax, p_ay, p_bx, p_by, p_dx, p_dy);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)))
        {
            return 0.0;
        }
        return Math.min(Math.min(point_segment(p_ax, p_ay, p_cx, p_cy, p_dx, p_dy), point_segment(p_bx, p_by, p_cx, p_cy, p_dx, p_dy)),
                Math.min(point_segment(p_cx, p_cy, p_ax, p_ay, p_bx, p_by), point_segment(p_dx, p_dy, p_ax, p_ay, p_bx, p_by)));
    }
}
