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
import java.util.Locale;
import java.util.Map;

/**
 * Converts the rules of config/rules.json to engine settings.
 *
 * The engine takes settings from two places:
 * - command line arguments, for single values
 * - an (autoroute_settings ...) scope in the board text, for the via costs and the cost of each layer
 */
final class EngineSetup
{
    private EngineSetup()
    {
    }

    /**
     * Engine arguments for the lengths of the rules.
     *
     * @param p_scale 1.0 when the board text has real lengths. 0.1 for the board text of the EasyEDA editor:
     *                the editor writes its 10 mil unit as 1 mil, so the engine sees the board 10 times smaller.
     */
    static List<String> args(Rules p_rules, double p_scale)
    {
        List<String> result = new ArrayList<String>();
        result.add("--router.copper_to_edge_clearance_um=" + number(p_rules.copper_to_edge_mm * 1000.0 * p_scale));
        result.add("--router.fanout.min_escape_length_mm=" + number(p_rules.first_stage_min_via_distance_mm * p_scale));
        result.add("--router.fanout.max_escape_length_mm=" + number(p_rules.first_stage_max_escape_mm * p_scale));
        if (p_rules.retry_width_mm > 0.0)
        {
            result.add("--router.neck_width_um=" + number(p_rules.retry_width_mm * 1000.0 * p_scale));
        }
        if (p_rules.narrow_tracks.equals("avoid"))
        {
            result.add("--router.automatic_neckdown=false");
            result.add("--router.fanout.enabled=false");
        }
        return result;
    }

    /**
     * The settings scope for the structure part of the board text. It must stand directly after the layer
     * definitions: the engine does not read it after a plane or a keep-out.
     *
     * @param p_layers      layer names of the board text in the order of the board, from top to bottom
     * @param p_plane_nets  layer name to the net of its plane. Layers without a plane are not in the map.
     * @param p_layer_roles layer name to its name in the rules (top, inner1, ..., bottom)
     * @param p_wide        true: the board is wider than high
     */
    static String settings_scope(Rules p_rules, List<String> p_layers, Map<String, String> p_plane_nets, Map<String, String> p_layer_roles,
            boolean p_wide, double p_aspect)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("    (autoroute_settings\n");
        sb.append("      (fanout on)\n      (autoroute on)\n      (postroute on)\n      (vias on)\n");
        sb.append("      (via_costs ").append(p_rules.via_cost).append(")\n");
        sb.append("      (plane_via_costs ").append(p_rules.plane_via_cost).append(")\n");
        sb.append("      (start_ripup_costs 100)\n");
        if (uses_layer_costs(p_rules, p_layers.size()))
        {
            // The engine adds this to the cost of a track that runs across the preferred direction of its layer.
            double across_horizontal = 0.1 * Math.round(10.0 * p_aspect);
            double across_vertical = 0.1 * Math.round(10.0 / p_aspect);
            boolean horizontal = p_wide;
            for (int i = 0; i < p_layers.size(); ++i)
            {
                String layer = p_layers.get(i);
                double factor = cost(p_rules, p_layer_roles.get(layer), i, p_layers.size(), p_plane_nets.get(layer));
                double across = factor * (1.0 + (horizontal ? across_horizontal : across_vertical));
                sb.append("      (layer_rule ").append(layer).append(" (active on) (preferred_direction ").append(horizontal ? "horizontal" : "vertical")
                        .append(") (preferred_direction_trace_costs ").append(number(factor)).append(") (against_preferred_direction_trace_costs ")
                        .append(number(across)).append("))\n");
                horizontal = !horizontal;
            }
        }
        sb.append("    )\n");
        return sb.toString();
    }

    /**
     * true: the cost factors of the rules go to the engine. On a board with 2 layers the engine keeps its own factors,
     * unless the rules name a layer: a test board with 2 layers got 3 times the vias with factors from the bridge,
     * also with factors equal to the ones of the engine.
     */
    static boolean uses_layer_costs(Rules p_rules, int p_layer_count)
    {
        return p_rules.layer_costs.equals("rules") && (p_layer_count > 2 || !p_rules.track_cost_by_layer.isEmpty());
    }

    /** Cost factor of one layer. */
    static double cost(Rules p_rules, String p_role, int p_index, int p_count, String p_plane_net)
    {
        Double by_name = p_role == null ? null : p_rules.track_cost_by_layer.get(p_role);
        if (by_name != null)
        {
            return by_name;
        }
        if (p_plane_net != null)
        {
            return p_rules.ground_net.matcher(p_plane_net).matches() ? p_rules.ground_plane_track_cost : p_rules.supply_plane_track_cost;
        }
        if (p_index == 0)
        {
            return p_rules.top_track_cost;
        }
        return p_index == p_count - 1 ? p_rules.bottom_track_cost : p_rules.inner_track_cost;
    }

    private static String number(double p_value)
    {
        String s = String.format(Locale.ROOT, "%.4f", p_value);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }
}
