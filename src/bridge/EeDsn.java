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
import java.util.Locale;
import java.util.Map;

/**
 * Writes the board text for the engine (Specctra DSN) from an EasyEDA board and its plan.
 *
 * Units: micrometres with a resolution of 0.1 micrometre. This is the true size of the board, so the
 * engine settings in millimetres have their real meaning. The y axis is turned (up is positive).
 *
 * Names in the text: layers L1..Ln from top to bottom, nets N1..Nn, pads 1..n of one component.
 * The maps of this object translate them back.
 */
final class EeDsn
{
    static final String VIA_STANDARD = "VIA_STD";
    static final String VIA_PAD = "VIA_PAD";

    final String text;
    /** Engine net name to real net name. */
    final Map<String, String> net_names = new LinkedHashMap<String, String>();
    /** Engine layer name to layer id of the editor. */
    final Map<String, String> layer_ids = new LinkedHashMap<String, String>();
    /** Via padstack name to {copper diameter mm, hole diameter mm}. */
    final Map<String, double[]> via_sizes = new LinkedHashMap<String, double[]>();
    /** Pour pad to the net name of its group. */
    private final Map<EeBoard.Pad, String> cluster_of = new HashMap<EeBoard.Pad, String>();
    /** Net name of a group of pour pads to the indexes of its pads. */
    final Map<String, List<Integer>> cluster_pins = new LinkedHashMap<String, List<Integer>>();
    /** Engine layer names of the layers that carry a plane, in board order. */
    final List<String> plane_layer_names = new ArrayList<String>();
    /** Short fixed tracks x0 y0 x1 y1 (mm) that join the end of an existing track to the centre of its pad. They do not go into the result. */
    final List<double[]> links = new ArrayList<double[]>();
    final int layer_count;

    private final EeBoard board;
    private final EePlan plan;
    private final Rules rules;
    private final Map<String, String> alias = new LinkedHashMap<String, String>();
    private final StringBuilder sb = new StringBuilder(1 << 18);

    EeDsn(EeBoard p_board, EePlan p_plan, Rules p_rules)
    {
        this.board = p_board;
        this.plan = p_plan;
        this.rules = p_rules;
        this.layer_count = p_board.layers.size();
        int n = 0;
        for (String net : p_plan.nets.keySet())
        {
            String name = "N" + (++n);
            alias.put(net, name);
            net_names.put(name, net);
        }
        for (int i = 0; i < p_board.layers.size(); ++i)
        {
            layer_ids.put("L" + (i + 1), p_board.layers.get(i));
        }
        via_sizes.put(VIA_STANDARD, new double[]
        {
            p_plan.via_diameter_mm, p_plan.via_hole_mm
        });
        via_sizes.put(VIA_PAD, new double[]
        {
            p_rules.pad_via_diameter_mm, p_rules.pad_via_hole_mm
        });
        write();
        this.text = sb.toString();
    }

    private String layer(String p_id)
    {
        return "L" + (board.layers.indexOf(p_id) + 1);
    }

    private static String um(double p_mm)
    {
        return String.format(Locale.ROOT, "%.1f", p_mm * 1000.0);
    }

    private void xy(double p_x, double p_y)
    {
        sb.append(' ').append(um(p_x)).append(' ').append(um(-p_y));
    }

    private void polygon(String p_layer, double[] p_points, double p_dx, double p_dy)
    {
        sb.append("(polygon ").append(p_layer).append(" 0");
        for (int i = 0; i + 1 < p_points.length; i += 2)
        {
            xy(p_points[i] - p_dx, p_points[i + 1] - p_dy);
        }
        xy(p_points[0] - p_dx, p_points[1] - p_dy);
        sb.append(')');
    }

    private void write()
    {
        sb.append("(pcb board\n");
        sb.append("  (parser (string_quote \") (space_in_quoted_tokens on) (host_cad \"easyeda-freerouting-bridge\") (host_version \"")
                .append(Main.VERSION).append("\"))\n");
        sb.append("  (resolution um 10)\n  (unit um)\n");
        write_structure();
        sb.append("  (placement (component BOARD (place B 0 0 front 0)))\n");
        write_library();
        write_network();
        write_wiring();
        sb.append(")\n");
    }

    private void write_structure()
    {
        sb.append("  (structure\n");
        for (int i = 0; i < board.layers.size(); ++i)
        {
            sb.append("    (layer L").append(i + 1).append(" (type signal))\n");
        }
        // engine settings: directly after the layers
        List<String> names = new ArrayList<String>();
        Map<String, String> plane_nets = new LinkedHashMap<String, String>();
        Map<String, String> roles = new LinkedHashMap<String, String>();
        for (String id : board.layers)
        {
            names.add(layer(id));
            roles.put(layer(id), board.layer_name(id));
        }
        for (Map.Entry<String, List<EeBoard.Region>> entry : plan.planes.entrySet())
        {
            for (EeBoard.Region area : entry.getValue())
            {
                String name = layer(area.layer);
                // The plane cost is for inner layers. A pour on an outer layer shares its layer with the signal tracks.
                if (plan.is_outer(area.layer))
                {
                    continue;
                }
                // a ground plane wins when a layer has planes of several nets
                if (!plane_nets.containsKey(name) || rules.ground_net.matcher(entry.getKey()).matches())
                {
                    plane_nets.put(name, entry.getKey());
                }
            }
        }
        double min_x = Double.MAX_VALUE, max_x = -Double.MAX_VALUE, min_y = Double.MAX_VALUE, max_y = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < board.outline.length; i += 2)
        {
            min_x = Math.min(min_x, board.outline[i]);
            max_x = Math.max(max_x, board.outline[i]);
            min_y = Math.min(min_y, board.outline[i + 1]);
            max_y = Math.max(max_y, board.outline[i + 1]);
        }
        double width = Math.max(max_x - min_x, 0.001);
        double height = Math.max(max_y - min_y, 0.001);
        sb.append(EngineSetup.settings_scope(rules, names, plane_nets, roles, width >= height, width / height));
        sb.append("    (boundary (path pcb 0");
        for (int i = 0; i + 1 < board.outline.length; i += 2)
        {
            xy(board.outline[i], board.outline[i + 1]);
        }
        xy(board.outline[0], board.outline[1]);
        sb.append("))\n");
        for (Map.Entry<String, List<EeBoard.Region>> entry : plan.planes.entrySet())
        {
            for (EeBoard.Region area : entry.getValue())
            {
                sb.append("    (plane ").append(alias.get(entry.getKey())).append(' ');
                polygon(layer(area.layer), area.points, 0, 0);
                sb.append(")\n");
                String name = layer(area.layer);
                if (!plane_layer_names.contains(name))
                {
                    plane_layer_names.add(name);
                }
            }
        }
        // copper without net: closed for new copper on its layer
        for (EeBoard.Region solid : board.solids)
        {
            // The rectangles of the plan cover the copper of a boxed part. The copper itself is not written,
            // because a pad that lies on it could then not be reached.
            if (solid.net.isEmpty() && !plan.boxed_parts.contains(solid.ref))
            {
                sb.append("    (keepout \"\" ");
                polygon(layer(solid.layer), solid.points, 0, 0);
                sb.append(")\n");
            }
        }
        for (EePlan.Keepout k : plan.keepouts)
        {
            sb.append("    (keepout \"\" ");
            polygon(layer(k.layer), new double[]
            {
                k.x0, k.y0, k.x1, k.y0, k.x1, k.y1, k.x0, k.y1
            }, 0, 0);
            sb.append(")\n");
        }
        for (EePlan.KeepoutCircle c : plan.hole_keepouts)
        {
            for (int i = 0; i < board.layers.size(); ++i)
            {
                sb.append("    (keepout \"\" (circle L").append(i + 1).append(' ').append(um(2.0 * c.radius));
                xy(c.x, c.y);
                sb.append("))\n");
            }
        }
        sb.append("    (via ").append(VIA_STANDARD);
        if (rules.via_on_pads.equals("all"))
        {
            sb.append(' ').append(VIA_PAD);
        }
        sb.append(")\n");
        String w = um(board.track_mm);
        String c = um(board.clearance_mm);
        sb.append("    (rule (width ").append(w).append(") (clearance ").append(c).append(") (clearance ").append(c)
                .append(" (type default_smd)) (clearance ").append(c).append(" (type smd_smd)))\n");
        if (!rules.via_on_pads.equals("off"))
        {
            sb.append("    (control (via_at_smd on))\n");
        }
        sb.append("  )\n");
    }

    private void write_library()
    {
        sb.append("  (library\n    (image BOARD\n");
        for (EeBoard.Pad pad : board.pads)
        {
            sb.append("      (pin P").append(pad.index + 1).append(' ').append(pad.index + 1);
            xy(pad.cx, pad.cy);
            sb.append(")\n");
        }
        sb.append("    )\n");
        for (EeBoard.Pad pad : board.pads)
        {
            sb.append("    (padstack P").append(pad.index + 1);
            List<String> on = pad.through ? board.layers : java.util.Collections.singletonList(pad.layer);
            for (String id : on)
            {
                sb.append(" (shape ");
                if (pad.polygon == null)
                {
                    sb.append("(circle ").append(layer(id)).append(' ').append(um(2.0 * pad.radius)).append(')');
                }
                else
                {
                    polygon(layer(id), pad.polygon, pad.cx, pad.cy);
                }
                sb.append(')');
            }
            sb.append(" (attach off))\n");
        }
        write_via_padstack(VIA_STANDARD, plan.via_diameter_mm, false);
        write_via_padstack(VIA_PAD, rules.pad_via_diameter_mm, true);
        // one padstack for each size of the vias that are already on the board
        for (EeBoard.Via via : board.vias)
        {
            String name = existing_via_name(via.diameter);
            if (!via_sizes.containsKey(name))
            {
                via_sizes.put(name, new double[]
                {
                    via.diameter, via.hole
                });
                write_via_padstack(name, via.diameter, true);
            }
        }
        sb.append("  )\n");
    }

    private static String existing_via_name(double p_diameter)
    {
        return "VIA_" + Math.round(p_diameter * 1000.0);
    }

    private void write_via_padstack(String p_name, double p_diameter, boolean p_attach)
    {
        sb.append("    (padstack ").append(p_name);
        for (int i = 0; i < board.layers.size(); ++i)
        {
            sb.append(" (shape (circle L").append(i + 1).append(' ').append(um(p_diameter)).append("))");
        }
        sb.append(p_attach ? " (attach on))\n" : " (attach off))\n");
    }

    private void write_network()
    {
        sb.append("  (network\n");
        for (Map.Entry<String, List<EeBoard.Pad>> entry : plan.nets.entrySet())
        {
            // A pad that the pour of its net joins is a pad without net for the engine.
            sb.append("    (net ").append(alias.get(entry.getKey())).append(" (pins");
            for (EeBoard.Pad pad : entry.getValue())
            {
                if (!plan.pour_pads.contains(pad))
                {
                    sb.append(" B-").append(pad.index + 1);
                }
            }
            sb.append("))\n");
        }
        // groups of pour pads that lie nearer to each other than the clearance: one net for each group
        Map<String, List<String>> cluster_aliases = new LinkedHashMap<String, List<String>>();
        int cluster_number = 0;
        for (List<EeBoard.Pad> cluster : plan.pour_clusters)
        {
            String name = "Q" + (++cluster_number);
            String net = cluster.get(0).net;
            net_names.put(name, net);
            cluster_aliases.computeIfAbsent(net, k -> new ArrayList<String>()).add(name);
            sb.append("    (net ").append(name).append(" (pins");
            List<Integer> indexes = new ArrayList<Integer>();
            for (EeBoard.Pad pad : cluster)
            {
                sb.append(" B-").append(pad.index + 1);
                indexes.add(pad.index);
                cluster_of.put(pad, name);
            }
            cluster_pins.put(name, indexes);
            sb.append("))\n");
        }
        // classes: nets with the same width, clearance and layers share one class
        Map<String, List<String>> classes = new LinkedHashMap<String, List<String>>();
        for (String net : plan.nets.keySet())
        {
            double width = board.track_mm;
            double clearance = board.clearance_mm;
            double[] special = board.special_nets.get(net);
            if (special != null)
            {
                if (special[0] > 0)
                {
                    width = special[0];
                }
                if (special[1] > 0)
                {
                    clearance = special[1];
                }
            }
            StringBuilder layers = new StringBuilder();
            Rules.NetEntry rule = rules.net_entry(net);
            if (rule != null)
            {
                if (rule.width_mm > 0)
                {
                    width = rule.width_mm;
                }
                if (rule.clearance_mm > 0)
                {
                    clearance = rule.clearance_mm;
                }
                for (String name : rule.layers)
                {
                    String id = board.layer_id(name);
                    if (id != null)
                    {
                        layers.append(' ').append(layer(id));
                    }
                }
            }
            if (layers.length() == 0 && plan.supply_areas.containsKey(net))
            {
                // a supply net that is routed with tracks stays off the layers of ground planes
                for (String id : board.layers)
                {
                    boolean ground = false;
                    for (Map.Entry<String, List<EeBoard.Region>> plane : plan.planes.entrySet())
                    {
                        for (EeBoard.Region area : plane.getValue())
                        {
                            ground |= area.layer.equals(id) && rules.ground_net.matcher(plane.getKey()).matches();
                        }
                    }
                    if (!ground)
                    {
                        layers.append(' ').append(layer(id));
                    }
                }
            }
            String key = um(width) + "|" + um(clearance) + "|" + layers;
            classes.computeIfAbsent(key, k -> new ArrayList<String>()).add(alias.get(net));
            if (cluster_aliases.containsKey(net))
            {
                classes.get(key).addAll(cluster_aliases.get(net));
            }
        }
        int n = 0;
        for (Map.Entry<String, List<String>> entry : classes.entrySet())
        {
            String[] key = entry.getKey().split("\\|", -1);
            sb.append("    (class C").append(++n);
            for (String net : entry.getValue())
            {
                sb.append(' ').append(net);
            }
            sb.append("\n      (circuit (use_via ").append(VIA_STANDARD);
            if (rules.via_on_pads.equals("all"))
            {
                sb.append(' ').append(VIA_PAD);
            }
            sb.append(')');
            if (!key[2].isEmpty())
            {
                sb.append(" (use_layer").append(key[2]).append(')');
            }
            sb.append(")\n      (rule (width ").append(key[0]).append(") (clearance ").append(key[1]).append("))\n    )\n");
        }
        sb.append("  )\n");
    }

    private void write_wiring()
    {
        sb.append("  (wiring\n");
        if (rules.existing.equals("keep"))
        {
            for (EeBoard.Track track : board.tracks)
            {
                write_wire(track);
            }
            for (EeBoard.Via via : board.vias)
            {
                String net = alias.get(via.net);
                sb.append("    (via ").append(existing_via_name(via.diameter));
                xy(via.x, via.y);
                if (net != null)
                {
                    sb.append(" (net ").append(net).append(')');
                }
                sb.append(" (type protect))\n");
            }
        }
        else
        {
            for (EeBoard.Track track : board.tracks)
            {
                if (track.locked)
                {
                    write_wire(track);
                }
            }
            for (EeBoard.Via via : board.vias)
            {
                if (via.locked)
                {
                    String net = alias.get(via.net);
                    sb.append("    (via ").append(existing_via_name(via.diameter));
                    xy(via.x, via.y);
                    if (net != null)
                    {
                        sb.append(" (net ").append(net).append(')');
                    }
                    sb.append(" (type protect))\n");
                }
            }
        }
        for (EeBoard.Track track : board.footprint_tracks)
        {
            if (track.width >= 0.02 && alias.containsKey(track.net))
            {
                write_wire(track);
            }
        }
        for (EePlan.PadVia via : plan.pad_vias)
        {
            sb.append("    (via ").append(VIA_PAD);
            xy(via.x, via.y);
            String via_net = cluster_of.containsKey(via.pad) ? cluster_of.get(via.pad) : alias.get(via.pad.net);
            sb.append(" (net ").append(via_net).append(") (type protect))\n");
        }
        if (plan.rework != null)
        {
            for (int i = 0; i < plan.rework.fixed_vias.size(); ++i)
            {
                String net = alias.get(plan.rework.fixed_via_nets.get(i));
                if (net != null)
                {
                    sb.append("    (via ").append(VIA_STANDARD);
                    xy(plan.rework.fixed_vias.get(i)[0], plan.rework.fixed_vias.get(i)[1]);
                    sb.append(" (net ").append(net).append(") (type protect))\n");
                }
            }
        }
        // Stubs of plane nets. The engine reads the type "route" as fixed, so the stubs stay.
        for (EePlan.Stub stub : plan.stubs)
        {
            String net = alias.get(stub.pad.net);
            sb.append("    (wire (path ").append(layer(stub.pad.layer)).append(' ').append(um(board.track_mm));
            xy(stub.pad.cx, stub.pad.cy);
            xy(stub.x, stub.y);
            sb.append(") (net ").append(net).append(") (type route))\n");
            if (stub.new_via)
            {
                sb.append("    (via ").append(VIA_STANDARD);
                xy(stub.x, stub.y);
                sb.append(" (net ").append(net).append(") (type route))\n");
            }
        }
        sb.append("  )\n");
    }

    /**
     * The engine sees a track as joined to a pad only when the track ends on the centre of the pad. The editor joins
     * them when the copper touches. For each end of an existing track that touches a pad of its net at a different
     * point, a short fixed track from that end to the centre of the pad is added. It lies on the copper of the pad.
     */
    private void write_links(EeBoard.Track p_track)
    {
        String net = alias.get(p_track.net);
        if (net == null || p_track.points.length < 4)
        {
            return;
        }
        int last = p_track.points.length - 2;
        for (int end : new int[]
        {
            0, last
        })
        {
            double x = p_track.points[end], y = p_track.points[end + 1];
            for (EeBoard.Pad pad : plan.nets.get(p_track.net))
            {
                if (!(pad.through || pad.layer.equals(p_track.layer)))
                {
                    continue;
                }
                double to_centre = Math.hypot(pad.cx - x, pad.cy - y);
                if (to_centre < 0.001 || EePlan.distance(pad, x, y) > p_track.width / 2.0)
                {
                    continue;
                }
                sb.append("    (wire (path ").append(layer(p_track.layer)).append(' ').append(um(Math.min(p_track.width, board.track_mm)));
                xy(x, y);
                xy(pad.cx, pad.cy);
                sb.append(") (net ").append(net).append(") (type protect))\n");
                links.add(new double[]
                {
                    x, y, pad.cx, pad.cy
                });
                break;
            }
        }
    }

    private void write_wire(EeBoard.Track p_track)
    {
        write_links(p_track);
        String net = alias.get(p_track.net);
        sb.append("    (wire (path ").append(layer(p_track.layer)).append(' ').append(um(p_track.width));
        for (int i = 0; i + 1 < p_track.points.length; i += 2)
        {
            xy(p_track.points[i], p_track.points[i + 1]);
        }
        sb.append(')');
        if (net != null)
        {
            sb.append(" (net ").append(net).append(')');
        }
        sb.append(" (type protect))\n");
    }
}
