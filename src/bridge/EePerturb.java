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
import java.util.Set;

/**
 * Small moves of parts on a routed board (rules: parts.move).
 *
 * Case that is handled: a pad of a net with a copper area lies in the area, tracks of other nets cut it off from the
 * fill, and the repair found no place for a via (EeBridge). The pad can take a via in its centre only when the via
 * keeps the clearance on all layers. Tracks on the other layers below the pad prevent that. A shift of the part by a
 * part of a millimetre can bring the centre of the pad to a point where the via fits.
 *
 * A move is taken only when all of this is true:
 * - The rules permit a move of the part (parts.movable, parts.fixed, parts.max_pads), and the record can be moved.
 * - No pad of the part has a via, and the plan has no via and no short track for a pad of the part.
 * - Each track that ends in a pad of the part still ends in that pad after the move. So no track changes and the
 *   engine does not run again for the move.
 * - The pads keep the clearance to all copper of other nets, to keep-outs and holes, and the distance to the edge.
 * - The gap to the body of each other part stays at parts.gap_mm or more, or is not smaller than before.
 * - A via of the pad-via size fits in the centre of the pad after the move and stands in fill that is joined to the
 *   net on a different layer.
 * Of all shifts that pass, the shortest is taken. The caller repairs and counts the moved board again and keeps the
 * move only when the count of open connections is lower.
 */
final class EePerturb
{
    static final class Move
    {
        String ref;
        String pad;
        String net;
        double dx, dy;
    }

    private static final double STEP_MM = 0.025;
    /** Added to the clearance of a moved pad to copper of other nets. */
    private static final double PAD_MARGIN_MM = 0.002;
    /** The same value as the further passes use for a new via in a pad. */
    private static final double VIA_MARGIN_MM = 0.022;

    final List<Move> moves = new ArrayList<Move>();
    /** The board of the job with the moves, and the result file with the moves. */
    String board_text;
    String result_text;
    final List<String> lines = new ArrayList<String>();
    /** Pads that stay open and why no move was made, for the report. */
    final List<String> not_moved = new ArrayList<String>();

    /**
     * @param p_board  the board that the result was made from
     * @param p_result the result file of p_board, after the repair
     * @param p_moved  references of parts that an earlier step moved. They do not move again.
     * @return null: no move passes the tests
     */
    static EePerturb via_room(EeBoard p_board, EePlan p_plan, Rules p_rules, String p_result, EeCheck p_check, Set<String> p_moved)
    {
        if (!p_rules.part_moves.equals("on") || p_plan.fill_areas.isEmpty() || p_rules.via_on_pads.equals("off"))
        {
            return null;
        }
        EePerturb result = new EePerturb();
        EeBoard routed = null;
        List<Copper> copper = null;
        Map<String, EeMove.Part> parts = null;
        Map<String, EeMove.Part> job_parts = null;
        Map<Integer, String> result_records = new LinkedHashMap<Integer, String>();
        Map<Integer, String> board_records = new LinkedHashMap<Integer, String>();
        double via_r = p_rules.pad_via_diameter_mm / 2.0;
        for (String net : p_plan.fill_areas.keySet())
        {
            EeCheck.NetState state = p_check.nets.get(net);
            if (state == null || state.groups <= 1)
            {
                continue;
            }
            if (routed == null)
            {
                routed = new EeBoard(p_result, p_board.design_rules_first);
                copper = EeNet.copper_of(routed, p_plan, false);
                parts = EeMove.parts(routed);
                job_parts = EeMove.parts(p_board);
            }
            EeNet graph = new EeNet(routed, p_plan, net, copper, false);
            int main_root = graph.find(graph.main_group());
            for (String name : state.loose_pads)
            {
                int dot = name.indexOf('.');
                String ref = dot < 0 ? name : name.substring(0, dot);
                EeMove.Part part = parts.get(ref);
                EeMove.Part job_part = job_parts.get(ref);
                String why = fixed_reason(p_rules, p_plan, p_board, routed, part, job_part, p_moved, result.moves);
                if (why != null)
                {
                    result.not_moved.add(name + ": " + why);
                    continue;
                }
                EeBoard.Pad pad = null;
                for (EeBoard.Pad q : part.pads)
                {
                    if ((q.ref + "." + q.number).equals(name))
                    {
                        pad = q;
                    }
                }
                if (pad == null || pad.through)
                {
                    continue;
                }
                double[] best = null;
                int tried = 0, legal = 0;
                int n = (int) Math.floor(p_rules.part_shift_mm / STEP_MM + 1e-9);
                for (int j = -n; j <= n; ++j)
                {
                    for (int i = -n; i <= n; ++i)
                    {
                        double dx = i * STEP_MM, dy = j * STEP_MM;
                        double len = Math.hypot(dx, dy);
                        if ((i == 0 && j == 0) || len > p_rules.part_shift_mm + 1e-9 || (best != null && len >= best[2]))
                        {
                            continue;
                        }
                        ++tried;
                        if (!legal(p_rules, p_plan, routed, copper, parts, part, dx, dy))
                        {
                            continue;
                        }
                        ++legal;
                        // the via in the centre of the pad after the move
                        double x = pad.cx + dx, y = pad.cy + dy;
                        List<Copper> others = new ArrayList<Copper>();
                        for (Copper c : copper)
                        {
                            if (!c.ref.equals(part.ref) || c.routed)
                            {
                                others.add(c);
                            }
                        }
                        Copper own = null;
                        for (EeBoard.Pad q : part.pads)
                        {
                            Copper moved = shifted(q, dx, dy);
                            moved.ref = part.ref;
                            if (q == pad)
                            {
                                own = moved;
                            }
                            else
                            {
                                others.add(moved);
                            }
                        }
                        if (EeBridge.reaches_main(graph, -1, x, y, main_root) && EeBridge.via_fits(p_plan, routed, others, net, x, y, via_r, own, 0.0, VIA_MARGIN_MM))
                        {
                            best = new double[]
                            {
                                dx, dy, len
                            };
                        }
                    }
                }
                if (best == null)
                {
                    result.not_moved.add(name + ": no shift of " + String.format(Locale.ROOT, "%.2f", p_rules.part_shift_mm) + " mm or less makes room for a via ("
                            + legal + " of " + tried + " shifts keep all rules)");
                    continue;
                }
                try
                {
                    String record = EeMove.moved_record(routed, routed.records.get(part.record), best[0], best[1], false, 0, 0);
                    String job_record = EeMove.moved_record(p_board, p_board.records.get(job_part.record), best[0], best[1], false, 0, 0);
                    result_records.put(part.record, record);
                    board_records.put(job_part.record, job_record);
                }
                catch (EeMove.Unsupported e)
                {
                    result.not_moved.add(name + ": the part has " + e.getMessage());
                    continue;
                }
                Move move = new Move();
                move.ref = part.ref;
                move.pad = name;
                move.net = net;
                move.dx = best[0];
                move.dy = best[1];
                result.moves.add(move);
                // later tests see the pads of this part in their new position
                for (Copper c : copper)
                {
                    if (c.ref.equals(part.ref) && !c.routed)
                    {
                        shift(c, best[0], best[1]);
                    }
                }
                for (EeBoard.Pad q : part.pads)
                {
                    q.cx += best[0];
                    q.cy += best[1];
                    if (q.polygon != null)
                    {
                        for (int k = 0; k + 1 < q.polygon.length; k += 2)
                        {
                            q.polygon[k] += best[0];
                            q.polygon[k + 1] += best[1];
                        }
                    }
                }
                part.x0 += best[0];
                part.x1 += best[0];
                part.y0 += best[1];
                part.y1 += best[1];
                result.lines.add(String.format(Locale.ROOT, "Part %s moved by %.3f mm in x and %.3f mm in y (%.3f mm). Pad %s of net %s can take a via in the pad at the new position.",
                        part.ref, best[0], best[1], best[2], name, net));
            }
        }
        if (result.moves.isEmpty())
        {
            return result.not_moved.isEmpty() ? null : result;
        }
        result.result_text = EeMove.with_records(routed, result_records);
        result.board_text = EeMove.with_records(p_board, board_records);
        return result;
    }

    /** The text of a board (the job board or a result file of it) with the moves of this step. */
    String apply_to(EeBoard p_board) throws EeMove.Unsupported
    {
        Map<String, EeMove.Part> parts = EeMove.parts(p_board);
        Map<Integer, String> records = new LinkedHashMap<Integer, String>();
        for (Move move : moves)
        {
            EeMove.Part part = parts.get(move.ref);
            if (part == null)
            {
                throw new EeMove.Unsupported("no part " + move.ref);
            }
            records.put(part.record, EeMove.moved_record(p_board, p_board.records.get(part.record), move.dx, move.dy, false, 0, 0));
        }
        return EeMove.with_records(p_board, records);
    }

    /** null: the part can be a candidate. Else the reason why it stays. */
    private static String fixed_reason(Rules p_rules, EePlan p_plan, EeBoard p_board, EeBoard p_routed, EeMove.Part p_part, EeMove.Part p_job_part,
            Set<String> p_moved, List<Move> p_moves)
    {
        if (p_part == null || p_job_part == null)
        {
            return "the part was not found";
        }
        if (!p_rules.part_movable(p_part.ref))
        {
            return "the rules do not permit a move of " + p_part.ref + " (parts.movable, parts.fixed)";
        }
        if (p_part.pads.size() > p_rules.movable_max_pads)
        {
            return p_part.ref + " has " + p_part.pads.size() + " pads (parts.max_pads)";
        }
        if (p_part.fixed_reason != null)
        {
            return p_part.fixed_reason;
        }
        if (p_moved != null && p_moved.contains(p_part.ref))
        {
            return p_part.ref + " was moved in an earlier step";
        }
        for (Move m : p_moves)
        {
            if (m.ref.equals(p_part.ref))
            {
                return p_part.ref + " moves for another pad";
            }
        }
        for (EeBoard.Pad pad : p_job_part.pads)
        {
            for (EePlan.PadVia via : p_plan.pad_vias)
            {
                if (via.pad == pad)
                {
                    return "pad " + pad.ref + "." + pad.number + " has a via in the pad";
                }
            }
            for (EePlan.Stub stub : p_plan.stubs)
            {
                if (stub.pad == pad)
                {
                    return "pad " + pad.ref + "." + pad.number + " has a short track to a via from the plan";
                }
            }
        }
        for (EeBoard.Pad pad : p_part.pads)
        {
            if (pad.through)
            {
                return p_part.ref + " has a pad with a hole";
            }
            Copper shape = shifted(pad, 0, 0);
            for (EeBoard.Via via : p_routed.vias)
            {
                if (EePlane.distance(shape, via.x, via.y) <= via.diameter / 2.0)
                {
                    return "pad " + pad.ref + "." + pad.number + " has a via";
                }
            }
        }
        return null;
    }

    private static Copper shifted(EeBoard.Pad p_pad, double p_dx, double p_dy)
    {
        String layer = p_pad.through ? null : p_pad.layer;
        if (p_pad.polygon == null)
        {
            return Copper.disc(p_pad.cx + p_dx, p_pad.cy + p_dy, p_pad.radius, layer, p_pad.net);
        }
        double[] points = new double[p_pad.polygon.length];
        for (int i = 0; i + 1 < points.length; i += 2)
        {
            points[i] = p_pad.polygon[i] + p_dx;
            points[i + 1] = p_pad.polygon[i + 1] + p_dy;
        }
        return Copper.polygon(points, layer, p_pad.net);
    }

    private static void shift(Copper p_c, double p_dx, double p_dy)
    {
        p_c.x0 += p_dx;
        p_c.x1 += p_dx;
        p_c.y0 += p_dy;
        p_c.y1 += p_dy;
        if (p_c.polygon != null)
        {
            for (int i = 0; i + 1 < p_c.polygon.length; i += 2)
            {
                p_c.polygon[i] += p_dx;
                p_c.polygon[i + 1] += p_dy;
            }
        }
        p_c.bounds();
    }

    /** true: the part can shift by dx, dy with all tracks as they are. */
    private static boolean legal(Rules p_rules, EePlan p_plan, EeBoard p_routed, List<Copper> p_copper, Map<String, EeMove.Part> p_parts, EeMove.Part p_part,
            double p_dx, double p_dy)
    {
        double edge = p_plan.rules.copper_to_edge_mm;
        for (EeBoard.Pad pad : p_part.pads)
        {
            Copper before = shifted(pad, 0, 0);
            Copper after = shifted(pad, p_dx, p_dy);
            // tracks that end in the pad still end in the pad
            for (EeBoard.Track track : p_routed.tracks)
            {
                if (!track.net.equals(pad.net) || !(pad.through || track.layer.equals(pad.layer)) || track.points.length < 4)
                {
                    continue;
                }
                int last = track.points.length - 2;
                for (int end : new int[]
                {
                    0, last
                })
                {
                    double x = track.points[end], y = track.points[end + 1];
                    if (EePlane.distance(before, x, y) <= 0.0 && EePlane.distance(after, x, y) > 0.0)
                    {
                        return false;
                    }
                }
            }
            // the board edge
            double[] box = pad.box();
            double[][] corners =
            {
                {
                    box[0] + p_dx, box[1] + p_dy
                },
                {
                    box[2] + p_dx, box[1] + p_dy
                },
                {
                    box[2] + p_dx, box[3] + p_dy
                },
                {
                    box[0] + p_dx, box[3] + p_dy
                }
            };
            for (double[] corner : corners)
            {
                double now = EePlan.distance_to_edge(p_routed.outline, corner[0], corner[1]);
                if (now < edge && now < EePlan.distance_to_edge(p_routed.outline, corner[0] - p_dx, corner[1] - p_dy) - 1e-9)
                {
                    return false;
                }
            }
            double need = p_routed.clearance_mm + PAD_MARGIN_MM;
            for (Copper c : p_copper)
            {
                if (c.ref.equals(p_part.ref) && !c.routed)
                {
                    continue;
                }
                if (!after.shares_layer(c) || after.far_from(c, need + 0.01))
                {
                    continue;
                }
                boolean other_net = c.net.isEmpty() || !c.net.equals(pad.net);
                if (!other_net)
                {
                    continue;
                }
                double d = after.distance(c);
                // A keep-out or a hole: the pad does not go nearer than it is. Copper of another net: the clearance.
                if (d < need && d < before.distance(c) - 1e-9)
                {
                    return false;
                }
            }
        }
        // the bodies of the other parts
        for (EeMove.Part other : p_parts.values())
        {
            if (other == p_part)
            {
                continue;
            }
            double now = gap(p_part.x0 + p_dx, p_part.y0 + p_dy, p_part.x1 + p_dx, p_part.y1 + p_dy, other);
            if (now < p_rules.part_gap_mm && now < gap(p_part.x0, p_part.y0, p_part.x1, p_part.y1, other) - 1e-9)
            {
                return false;
            }
        }
        return true;
    }

    /** Gap between a box and the body box of a part. 0: they touch or overlap. */
    private static double gap(double p_x0, double p_y0, double p_x1, double p_y1, EeMove.Part p_other)
    {
        double dx = Math.max(0.0, Math.max(p_other.x0 - p_x1, p_x0 - p_other.x1));
        double dy = Math.max(0.0, Math.max(p_other.y0 - p_y1, p_y0 - p_other.y1));
        return Math.hypot(dx, dy);
    }
}
