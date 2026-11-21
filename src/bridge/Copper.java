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
import java.util.List;

/**
 * One piece of copper of a board with exact distance functions. Lengths are mm.
 * Kinds: a disc (via, round pad), a track segment with round ends, a polygon (pad, copper region).
 */
final class Copper
{
    static final int DISC = 0, SEGMENT = 1, POLYGON = 2;

    int kind;
    /** Layer id of the editor. null: all copper layers. */
    String layer;
    String net = "";
    /** Part of a pad or of a copper region of a footprint. Empty for tracks and vias of the board. */
    String ref = "";
    /** What it is, for a message: pad U1.5, track, via, copper region. */
    String label = "";
    /** true: a track or a via of the board (not of a footprint). */
    boolean routed;
    /**
     * true: a keep-out rectangle of the plan (the box around copper without net, or a box of the rules file). It closes an
     * area for new tracks and vias. It is no copper: the fill of a copper area in the editor does not stop at it.
     */
    boolean keepout_box;
    double x0, y0, x1, y1, r;
    double[] polygon;
    double bx0, by0, bx1, by1;

    static Copper disc(double p_x, double p_y, double p_r, String p_layer, String p_net)
    {
        Copper c = new Copper();
        c.kind = DISC;
        c.x0 = c.x1 = p_x;
        c.y0 = c.y1 = p_y;
        c.r = p_r;
        c.layer = p_layer;
        c.net = p_net == null ? "" : p_net;
        c.bounds();
        return c;
    }

    static Copper segment(double p_x0, double p_y0, double p_x1, double p_y1, double p_r, String p_layer, String p_net)
    {
        Copper c = new Copper();
        c.kind = SEGMENT;
        c.x0 = p_x0;
        c.y0 = p_y0;
        c.x1 = p_x1;
        c.y1 = p_y1;
        c.r = p_r;
        c.layer = p_layer;
        c.net = p_net == null ? "" : p_net;
        c.bounds();
        return c;
    }

    static Copper polygon(double[] p_points, String p_layer, String p_net)
    {
        Copper c = new Copper();
        c.kind = POLYGON;
        c.polygon = p_points;
        c.layer = p_layer;
        c.net = p_net == null ? "" : p_net;
        c.bounds();
        return c;
    }

    void bounds()
    {
        if (kind == POLYGON)
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

    boolean shares_layer(Copper p_other)
    {
        return layer == null || p_other.layer == null || layer.equals(p_other.layer);
    }

    /** true: the boxes of the two pieces are farther apart than p_gap, so the pieces are too. */
    boolean far_from(Copper p_other, double p_gap)
    {
        return bx1 + p_gap < p_other.bx0 || p_other.bx1 + p_gap < bx0 || by1 + p_gap < p_other.by0 || p_other.by1 + p_gap < by0;
    }

    /** Distance between the copper of the two pieces. 0 when they touch or overlap. */
    double distance(Copper p_other)
    {
        return Math.max(0.0, centre_distance(p_other) - (kind == POLYGON ? 0.0 : r) - (p_other.kind == POLYGON ? 0.0 : p_other.r));
    }

    /**
     * Distance between the centre lines (disc: the centre, segment: its line, polygon: its area).
     * The copper distance is this value minus the radius of each piece that is not a polygon.
     */
    double centre_distance(Copper p_other)
    {
        if (kind == POLYGON && p_other.kind == POLYGON)
        {
            return polygon_polygon(polygon, p_other.polygon);
        }
        if (kind == POLYGON)
        {
            return polygon_segment(polygon, p_other.x0, p_other.y0, p_other.x1, p_other.y1);
        }
        if (p_other.kind == POLYGON)
        {
            return polygon_segment(p_other.polygon, x0, y0, x1, y1);
        }
        return EeCheck.segment_distance(x0, y0, x1, y1, p_other.x0, p_other.y0, p_other.x1, p_other.y1);
    }

    private static double polygon_segment(double[] p_polygon, double p_x0, double p_y0, double p_x1, double p_y1)
    {
        if (EeCheck.inside(p_polygon, p_x0, p_y0) || EeCheck.inside(p_polygon, p_x1, p_y1))
        {
            return 0.0;
        }
        double best = Double.MAX_VALUE;
        int n = p_polygon.length / 2;
        for (int i = 0; i < n; ++i)
        {
            int j = (i + 1) % n;
            best = Math.min(best, EeCheck.segment_distance(p_polygon[2 * i], p_polygon[2 * i + 1], p_polygon[2 * j], p_polygon[2 * j + 1], p_x0, p_y0, p_x1, p_y1));
        }
        return best;
    }

    private static double polygon_polygon(double[] p_a, double[] p_b)
    {
        if (EeCheck.inside(p_a, p_b[0], p_b[1]) || EeCheck.inside(p_b, p_a[0], p_a[1]))
        {
            return 0.0;
        }
        double best = Double.MAX_VALUE;
        int na = p_a.length / 2;
        int nb = p_b.length / 2;
        for (int i = 0; i < na; ++i)
        {
            int i2 = (i + 1) % na;
            for (int j = 0; j < nb; ++j)
            {
                int j2 = (j + 1) % nb;
                best = Math.min(best, EeCheck.segment_distance(p_a[2 * i], p_a[2 * i + 1], p_a[2 * i2], p_a[2 * i2 + 1], p_b[2 * j], p_b[2 * j + 1],
                        p_b[2 * j2], p_b[2 * j2 + 1]));
            }
        }
        return best;
    }

    /** Pads and copper regions of the footprints, and the tracks of the footprints. They do not change during routing. */
    static List<Copper> of_footprints(EeBoard p_board)
    {
        List<Copper> list = new ArrayList<Copper>();
        for (EeBoard.Pad pad : p_board.pads)
        {
            Copper c = pad.polygon != null ? polygon(pad.polygon, pad.through ? null : pad.layer, pad.net)
                    : disc(pad.cx, pad.cy, pad.radius, pad.through ? null : pad.layer, pad.net);
            c.ref = pad.ref;
            c.label = "pad " + pad.ref + "." + pad.number;
            list.add(c);
        }
        for (EeBoard.Region solid : p_board.solids)
        {
            if (solid.points.length >= 6 && p_board.layers.contains(solid.layer))
            {
                Copper c = polygon(solid.points, solid.layer, solid.net);
                c.ref = solid.ref == null ? "" : solid.ref;
                c.label = "copper region of " + c.ref;
                list.add(c);
            }
        }
        for (EeBoard.Track track : p_board.footprint_tracks)
        {
            if (!p_board.layers.contains(track.layer))
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                Copper c = segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer, track.net);
                c.ref = track.ref;
                c.label = "track of footprint " + track.ref;
                list.add(c);
            }
        }
        return list;
    }

    /** The tracks and vias of the board. */
    static List<Copper> of_routes(EeBoard p_board)
    {
        List<Copper> list = new ArrayList<Copper>();
        for (EeBoard.Track track : p_board.tracks)
        {
            if (!p_board.layers.contains(track.layer))
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                Copper c = segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer, track.net);
                c.routed = true;
                c.label = "track of " + track.net + " on " + p_board.layer_name(track.layer);
                list.add(c);
            }
        }
        for (EeBoard.Via via : p_board.vias)
        {
            Copper c = disc(via.x, via.y, via.diameter / 2.0, null, via.net);
            c.routed = true;
            c.label = "via of " + via.net;
            list.add(c);
        }
        return list;
    }
}
