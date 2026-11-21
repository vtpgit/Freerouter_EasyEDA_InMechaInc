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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Model of the copper that the editor fills into one copper area (a plane).
 *
 * The fill is the outline of the area minus all space that is nearer than the area clearance to copper
 * of a different net on that layer. Necks narrower than MIN_WIDTH_MM are removed. Each separate piece is an island.
 * A pad, a via or a track of the net is joined to an island when the island comes to 0.08 mm or less
 * from its copper. Tracks of other nets on the layer of a plane can cut the plane into several islands.
 *
 * The model works on a grid of 0.025 mm. It is an approximation of the fill of the editor.
 */
final class EePlane
{
    static final double STEP = 0.025;
    /**
     * The smallest width of the fill, as the value for this grid model.
     *
     * Measured on a fill that the editor stored in a board file (area stroke 0.254 mm, area clearance 0.15 mm): the fill
     * of the editor loses no area when a disc of 0.19 mm diameter is rolled inside it. A model with exact polygons gives
     * the same open pads as that fill (14 pads in two nets) with 0.19 mm. With 0.18 mm it joins one pad more. With
     * 0.20 mm it joins two pads less. This grid model gives the same pads with 0.16 mm to 0.17 mm, because the grid
     * points do not lie on the centre line of a neck. With 0.10 mm, the value of versions 1.2.1 to 1.2.4, the model
     * joined 5 pads that the fill of the editor does not join.
     */
    static final double MIN_WIDTH_MM = Double.parseDouble(System.getProperty("bridge.test.fill_min_width", "0.165"));
    private static final double NECK_R = MIN_WIDTH_MM / 2.0;
    /** The erosion steps for the outline of the area. */
    private static final int NECK_CELLS = (int) Math.ceil(NECK_R / STEP);
    /** An island joins copper of its net that is this near. */
    static final double REACH_MM = 0.08;
    /** Distances above this value are not stored. */
    private final float far;

    final EeBoard.Region area;
    final double clearance;
    final double x0, y0;
    final int nx, ny;
    /** Distance of each cell centre from copper of other nets on the layer, up to FAR. */
    final float[] other;
    /** true: the cell centre is inside the outline of the area. */
    final boolean[] inside;
    /** Island number of each cell of the fill after the removal of necks, -1: no fill. */
    final int[] island;
    int island_count = 0;
    /** Cells of each island. */
    int[] island_cells = new int[0];

    /**
     * @param p_copper all copper of the board (footprints, tracks, vias)
     */
    EePlane(EeBoard p_board, EeBoard.Region p_area, List<Copper> p_copper)
    {
        this.area = p_area;
        this.clearance = p_area.clearance > 0.0 ? p_area.clearance : p_board.clearance_mm;
        this.far = (float) (Math.max(clearance + NECK_R, p_board.track_mm / 2.0 + p_board.clearance_mm) + 0.1);
        double min_x = Double.MAX_VALUE, min_y = Double.MAX_VALUE, max_x = -Double.MAX_VALUE, max_y = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < p_area.points.length; i += 2)
        {
            min_x = Math.min(min_x, p_area.points[i]);
            max_x = Math.max(max_x, p_area.points[i]);
            min_y = Math.min(min_y, p_area.points[i + 1]);
            max_y = Math.max(max_y, p_area.points[i + 1]);
        }
        this.x0 = min_x - 4 * STEP;
        this.y0 = min_y - 4 * STEP;
        this.nx = Math.max(1, (int) Math.ceil((max_x - min_x) / STEP) + 8);
        this.ny = Math.max(1, (int) Math.ceil((max_y - min_y) / STEP) + 8);
        this.other = new float[nx * ny];
        this.inside = new boolean[nx * ny];
        this.island = new int[nx * ny];
        Arrays.fill(other, far);
        Arrays.fill(island, -1);

        // inside the outline: even-odd rule, one row at a time
        int n = p_area.points.length / 2;
        double[] crossings = new double[n];
        for (int iy = 0; iy < ny; ++iy)
        {
            double y = y0 + (iy + 0.5) * STEP;
            int count = 0;
            for (int i = 0, j = n - 1; i < n; j = i++)
            {
                double yi = p_area.points[2 * i + 1], yj = p_area.points[2 * j + 1];
                if ((yi > y) != (yj > y))
                {
                    crossings[count++] = (p_area.points[2 * j] - p_area.points[2 * i]) * (y - yi) / (yj - yi) + p_area.points[2 * i];
                }
            }
            Arrays.sort(crossings, 0, count);
            for (int k = 0; k + 1 < count; k += 2)
            {
                int from = Math.max(0, (int) Math.ceil((crossings[k] - x0) / STEP - 0.5));
                int to = Math.min(nx - 1, (int) Math.floor((crossings[k + 1] - x0) / STEP - 0.5));
                for (int ix = from; ix <= to; ++ix)
                {
                    inside[iy * nx + ix] = true;
                }
            }
        }

        // distance from copper of other nets on this layer
        // A keep-out rectangle of the plan is no copper. Up to version 1.2.6 the model stopped the fill at these rectangles.
        // Measured on a board with the fill of the editor: the editor joined three pads near the antenna that the model cut off.
        for (Copper c : p_copper)
        {
            if ((c.net.equals(p_area.net) && !c.net.isEmpty()) || (c.layer != null && !c.layer.equals(p_area.layer)) || c.keepout_box)
            {
                continue;
            }
            stamp(c);
        }

        // fill, removal of necks, islands
        // The core of the fill: the positions of the centre of a disc of MIN_WIDTH_MM that stays inside the outline and
        // keeps the clearance to the other nets. The fill is the area that the disc covers. A neck narrower than the disc
        // has no core, so it separates two pieces.
        boolean[] core = inside;
        for (int pass = 0; pass < NECK_CELLS; ++pass)
        {
            boolean[] next = new boolean[nx * ny];
            boolean diagonal = pass % 2 == 1;
            for (int iy = 1; iy < ny - 1; ++iy)
            {
                for (int ix = 1; ix < nx - 1; ++ix)
                {
                    int i = iy * nx + ix;
                    boolean keep = core[i] && core[i - 1] && core[i + 1] && core[i - nx] && core[i + nx];
                    if (keep && diagonal)
                    {
                        keep = core[i - nx - 1] && core[i - nx + 1] && core[i + nx - 1] && core[i + nx + 1];
                    }
                    next[i] = keep;
                }
            }
            core = next;
        }
        if (core == inside)
        {
            core = inside.clone();
        }
        for (int i = 0; i < core.length; ++i)
        {
            core[i] = core[i] && other[i] >= clearance + NECK_R;
        }
        List<Integer> sizes = new ArrayList<Integer>();
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        for (int start = 0; start < core.length; ++start)
        {
            if (!core[start] || island[start] >= 0)
            {
                continue;
            }
            int id = island_count++;
            int size = 0;
            island[start] = id;
            queue.add(start);
            while (!queue.isEmpty())
            {
                int i = queue.poll();
                ++size;
                int ix = i % nx;
                if (ix > 0 && core[i - 1] && island[i - 1] < 0)
                {
                    island[i - 1] = id;
                    queue.add(i - 1);
                }
                if (ix < nx - 1 && core[i + 1] && island[i + 1] < 0)
                {
                    island[i + 1] = id;
                    queue.add(i + 1);
                }
                if (i >= nx && core[i - nx] && island[i - nx] < 0)
                {
                    island[i - nx] = id;
                    queue.add(i - nx);
                }
                if (i + nx < core.length && core[i + nx] && island[i + nx] < 0)
                {
                    island[i + nx] = id;
                    queue.add(i + nx);
                }
            }
            sizes.add(size);
        }
        island_cells = new int[sizes.size()];
        for (int i = 0; i < island_cells.length; ++i)
        {
            island_cells[i] = sizes.get(i);
        }
    }

    /**
     * The part of the outline of a pad that has fill directly beside it, 0 to 1. A pad in open fill has 0.5 to 1.
     * A pad that the fill reaches at one corner only has less than 0.3.
     */
    double contact(EeBoard.Pad p_pad)
    {
        List<double[]> points = new ArrayList<double[]>();
        if (p_pad.polygon != null)
        {
            int n = p_pad.polygon.length / 2;
            for (int v = 0; v < n; ++v)
            {
                double ax = p_pad.polygon[2 * v], ay = p_pad.polygon[2 * v + 1];
                double bx = p_pad.polygon[2 * ((v + 1) % n)], by = p_pad.polygon[2 * ((v + 1) % n) + 1];
                int steps = Math.max(1, (int) (Math.hypot(bx - ax, by - ay) / STEP));
                for (int s = 0; s < steps; ++s)
                {
                    points.add(new double[]
                    {
                        ax + (bx - ax) * (s + 0.5) / steps, ay + (by - ay) * (s + 0.5) / steps
                    });
                }
            }
        }
        else
        {
            int steps = Math.max(16, (int) (2.0 * Math.PI * p_pad.radius / STEP));
            for (int s = 0; s < steps; ++s)
            {
                points.add(new double[]
                {
                    p_pad.cx + p_pad.radius * Math.cos(2.0 * Math.PI * s / steps), p_pad.cy + p_pad.radius * Math.sin(2.0 * Math.PI * s / steps)
                });
            }
        }
        int hit = 0;
        for (double[] q : points)
        {
            double dx = q[0] - p_pad.cx, dy = q[1] - p_pad.cy, d = Math.hypot(dx, dy);
            if (d < 1e-9)
            {
                continue;
            }
            if (filled(q[0] + dx / d * 0.04, q[1] + dy / d * 0.04))
            {
                ++hit;
            }
        }
        return points.isEmpty() ? 0.0 : (double) hit / points.size();
    }

    /** true: the fill of the model covers the point. The fill reaches half of MIN_WIDTH_MM beyond its core. */
    boolean filled(double p_x, double p_y)
    {
        double reach = NECK_R + STEP * 0.5;
        int ix0 = Math.max(0, (int) Math.floor((p_x - reach - x0) / STEP));
        int ix1 = Math.min(nx - 1, (int) Math.ceil((p_x + reach - x0) / STEP));
        int iy0 = Math.max(0, (int) Math.floor((p_y - reach - y0) / STEP));
        int iy1 = Math.min(ny - 1, (int) Math.ceil((p_y + reach - y0) / STEP));
        for (int iy = iy0; iy <= iy1; ++iy)
        {
            for (int ix = ix0; ix <= ix1; ++ix)
            {
                if (island[iy * nx + ix] >= 0 && Math.hypot(cell_x(ix) - p_x, cell_y(iy) - p_y) <= reach)
                {
                    return true;
                }
            }
        }
        return false;
    }

    int cell_of_x(double p_x)
    {
        return (int) Math.floor((p_x - x0) / STEP);
    }

    int cell_of_y(double p_y)
    {
        return (int) Math.floor((p_y - y0) / STEP);
    }

    double cell_x(int p_ix)
    {
        return x0 + (p_ix + 0.5) * STEP;
    }

    double cell_y(int p_iy)
    {
        return y0 + (p_iy + 0.5) * STEP;
    }

    int cell_of(double p_x, double p_y)
    {
        int ix = (int) Math.floor((p_x - x0) / STEP);
        int iy = (int) Math.floor((p_y - y0) / STEP);
        return ix < 0 || iy < 0 || ix >= nx || iy >= ny ? -1 : iy * nx + ix;
    }

    /** Area of an island in square mm. */
    double island_area(int p_island)
    {
        return island_cells[p_island] * STEP * STEP;
    }

    /** Distance from a point to the copper of a piece. 0 inside. */
    static double distance(Copper p_c, double p_x, double p_y)
    {
        if (p_c.kind == Copper.POLYGON)
        {
            if (EeCheck.inside(p_c.polygon, p_x, p_y))
            {
                return 0.0;
            }
            double best = Double.MAX_VALUE;
            int n = p_c.polygon.length / 2;
            for (int i = 0; i < n; ++i)
            {
                int j = (i + 1) % n;
                best = Math.min(best, EeCheck.segment_distance(p_c.polygon[2 * i], p_c.polygon[2 * i + 1], p_c.polygon[2 * j], p_c.polygon[2 * j + 1], p_x,
                        p_y, p_x, p_y));
            }
            return best;
        }
        return Math.max(0.0, EeCheck.segment_distance(p_c.x0, p_c.y0, p_c.x1, p_c.y1, p_x, p_y, p_x, p_y) - p_c.r);
    }

    /** Writes the distance from the piece into the cells around it. */
    private void stamp(Copper p_c)
    {
        double reach = far;
        int ix0 = Math.max(0, (int) Math.floor((p_c.bx0 - reach - x0) / STEP));
        int ix1 = Math.min(nx - 1, (int) Math.ceil((p_c.bx1 + reach - x0) / STEP));
        int iy0 = Math.max(0, (int) Math.floor((p_c.by0 - reach - y0) / STEP));
        int iy1 = Math.min(ny - 1, (int) Math.ceil((p_c.by1 + reach - y0) / STEP));
        for (int iy = iy0; iy <= iy1; ++iy)
        {
            double y = cell_y(iy);
            for (int ix = ix0; ix <= ix1; ++ix)
            {
                int i = iy * nx + ix;
                double d = distance(p_c, cell_x(ix), y);
                if (d < other[i])
                {
                    other[i] = (float) d;
                }
            }
        }
    }

    /**
     * The islands that join a piece of copper of the net of the area: islands that come to REACH_MM or less from the piece.
     * The fill of the model ends half of MIN_WIDTH_MM outside the island cells.
     */
    List<Integer> islands_at(Copper p_c)
    {
        List<Integer> result = new ArrayList<Integer>();
        if (p_c.layer != null && !p_c.layer.equals(area.layer))
        {
            return result;
        }
        double reach = REACH_MM + NECK_R + STEP * 0.5;
        int ix0 = Math.max(0, (int) Math.floor((p_c.bx0 - reach - x0) / STEP));
        int ix1 = Math.min(nx - 1, (int) Math.ceil((p_c.bx1 + reach - x0) / STEP));
        int iy0 = Math.max(0, (int) Math.floor((p_c.by0 - reach - y0) / STEP));
        int iy1 = Math.min(ny - 1, (int) Math.ceil((p_c.by1 + reach - y0) / STEP));
        for (int iy = iy0; iy <= iy1; ++iy)
        {
            for (int ix = ix0; ix <= ix1; ++ix)
            {
                int id = island[iy * nx + ix];
                if (id >= 0 && !result.contains(id) && distance(p_c, cell_x(ix), cell_y(iy)) <= reach)
                {
                    result.add(id);
                }
            }
        }
        return result;
    }
}
