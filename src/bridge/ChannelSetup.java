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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies config/rules.json to the board text that the EasyEDA editor sends.
 *
 * The editor sends pads, nets, widths, clearances and one via size. It does not send copper areas,
 * keep-outs or a second via size. So only these rules reach the engine on this channel:
 * - cost of each layer and cost of a via
 * - via on a pad of the same net
 * - planes that are named in the rules (planes.list)
 * - the lengths of the engine settings, corrected for the unit of the editor
 */
final class ChannelSetup
{
    /** The board text with the changes. */
    final String text;
    /** Engine arguments. */
    final List<String> args;
    /** One line per change, for the console. */
    final List<String> notes = new ArrayList<String>();
    /** Aliases of the nets that have a plane. A via into the plane layer connects them. */
    final List<String> plane_nets = new ArrayList<String>();

    private static final Pattern LAYER = Pattern.compile("\\(layer\\s+(\"[^\"]*\"|[^\\s()]+)\\s*\\(type\\s+signal\\s*\\)\\s*\\)");
    private static final Pattern BOUNDARY = Pattern.compile("\\(boundary\\s*\\(path\\s+\\S+\\s+\\S+\\s+([^)]*)\\)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    /**
     * @param p_dsn     the repaired board text
     * @param p_renamed alias in the board text to the net name of the editor
     */
    ChannelSetup(String p_dsn, Rules p_rules, Map<String, String> p_renamed)
    {
        // The editor writes its 10 mil unit as 1 mil. The engine reads the board 10 times too small.
        this.args = EngineSetup.args(p_rules, 0.1);
        String result = p_dsn;
        try
        {
            result = edit(p_dsn, p_rules, p_renamed);
        }
        catch (RuntimeException e)
        {
            notes.add("the rules were not written into the board text (" + e + "). The engine gets the text of the editor.");
            plane_nets.clear();
        }
        this.text = result;
    }

    private String edit(String p_dsn, Rules p_rules, Map<String, String> p_renamed)
    {
        int structure = p_dsn.indexOf("(structure");
        int placement = p_dsn.indexOf("(placement");
        if (structure < 0 || placement < structure)
        {
            notes.add("the board text has no structure part. The rules for layers, vias and planes are not used.");
            return p_dsn;
        }
        List<String> layers = new ArrayList<String>();
        int last_layer_end = -1;
        Matcher m = LAYER.matcher(p_dsn);
        m.region(structure, placement);
        while (m.find())
        {
            layers.add(m.group(1));
            last_layer_end = m.end();
        }
        if (layers.isEmpty())
        {
            notes.add("the board text has no layers. The rules for layers, vias and planes are not used.");
            return p_dsn;
        }
        // names of the editor: 1 top, 2 bottom, 21 and up inner layers
        Map<String, String> roles = new LinkedHashMap<String, String>();
        List<String> physical = new ArrayList<String>(layers);
        boolean editor_names = true;
        for (String layer : layers)
        {
            editor_names &= layer.matches("1|2|2[1-9]|[3-5]\\d");
        }
        if (editor_names)
        {
            Collections.sort(physical, (a, b) -> Integer.compare(order(a), order(b)));
            for (String layer : physical)
            {
                roles.put(layer, layer.equals("1") ? "top" : layer.equals("2") ? "bottom" : "inner" + (Integer.parseInt(layer) - 20));
            }
        }
        else
        {
            for (int i = 0; i < physical.size(); ++i)
            {
                roles.put(physical.get(i), i == 0 ? "top" : i == physical.size() - 1 ? "bottom" : "inner" + i);
            }
        }

        // board outline
        double[] outline = null;
        Matcher b = BOUNDARY.matcher(p_dsn);
        b.region(structure, placement);
        if (b.find())
        {
            List<Double> values = new ArrayList<Double>();
            Matcher n = NUMBER.matcher(b.group(1));
            while (n.find())
            {
                values.add(Double.parseDouble(n.group()));
            }
            if (values.size() >= 6)
            {
                outline = new double[values.size() / 2 * 2];
                for (int i = 0; i < outline.length; ++i)
                {
                    outline[i] = values.get(i);
                }
            }
        }
        double width = 1.0, height = 1.0;
        if (outline != null)
        {
            double min_x = Double.MAX_VALUE, max_x = -Double.MAX_VALUE, min_y = Double.MAX_VALUE, max_y = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < outline.length; i += 2)
            {
                min_x = Math.min(min_x, outline[i]);
                max_x = Math.max(max_x, outline[i]);
                min_y = Math.min(min_y, outline[i + 1]);
                max_y = Math.max(max_y, outline[i + 1]);
            }
            width = Math.max(max_x - min_x, 1e-6);
            height = Math.max(max_y - min_y, 1e-6);
        }

        // planes of the rules
        Map<String, String> alias_of = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : p_renamed.entrySet())
        {
            alias_of.put(entry.getValue(), entry.getKey());
        }
        Map<String, String> plane_net_of_layer = new LinkedHashMap<String, String>();
        StringBuilder planes = new StringBuilder();
        for (Rules.PlaneEntry entry : p_rules.planes)
        {
            String layer = null;
            for (Map.Entry<String, String> role : roles.entrySet())
            {
                if (role.getValue().equals(entry.layer))
                {
                    layer = role.getKey();
                }
            }
            String alias = alias_of.containsKey(entry.net) ? alias_of.get(entry.net) : entry.net;
            boolean net_exists = Pattern.compile("\\(net\\s+\"?" + Pattern.quote(alias) + "\"?\\s").matcher(p_dsn).find();
            if (layer == null || outline == null || !net_exists)
            {
                notes.add("plane of the rules not used: net " + entry.net + " on layer " + entry.layer + " ("
                        + (layer == null ? "the board has no such layer" : outline == null ? "the board outline was not read" : "the board has no such net to route")
                        + ").");
                continue;
            }
            planes.append("    (plane ").append(alias).append(" (polygon ").append(layer).append(" 0");
            for (double value : outline)
            {
                planes.append(' ').append(value);
            }
            planes.append("))\n");
            if (!plane_net_of_layer.containsKey(layer) || p_rules.ground_net.matcher(entry.net).matches())
            {
                plane_net_of_layer.put(layer, entry.net);
            }
            plane_nets.add(alias);
            notes.add("plane from the rules: net " + entry.net + " on layer " + entry.layer
                    + ". The engine connects this net with vias into that layer. The copper area must be in the editor.");
        }

        StringBuilder insert = new StringBuilder("\n");
        insert.append(EngineSetup.settings_scope(p_rules, physical, plane_net_of_layer, roles, width >= height, width / height));
        insert.append(planes);
        if (!p_rules.via_on_pads.equals("off"))
        {
            insert.append(Variant.PAD_VIA_LINE);
            notes.add("a via on a pad of the same net is allowed (rules: via.on_pads).");
        }
        if (EngineSetup.uses_layer_costs(p_rules, physical.size()))
        {
            StringBuilder costs = new StringBuilder();
            for (int i = 0; i < physical.size(); ++i)
            {
                String layer = physical.get(i);
                costs.append(costs.length() > 0 ? ", " : "").append(roles.get(layer)).append(' ')
                        .append(EngineSetup.cost(p_rules, roles.get(layer), i, physical.size(), plane_net_of_layer.get(layer)));
            }
            notes.add("track cost factor by layer: " + costs + ".");
        }
        return p_dsn.substring(0, last_layer_end) + insert + p_dsn.substring(last_layer_end);
    }

    private static int order(String p_layer)
    {
        int n = Integer.parseInt(p_layer);
        return n == 1 ? 0 : n == 2 ? 1000 : n;
    }
}
