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
import java.util.Map;

/**
 * Converts a Specctra session file (the routing result of the engine) into the JSON structure
 * that the EasyEDA editor expects from its local auto router:
 *
 *   {"1": {"wires": [{"layerid": 1, "width": 1.028, "points": [x1, y1, x2, y2, ...]}, ...],
 *          "vias": [{"x": ..., "y": ...}, ...],
 *          "net": "NAME"},
 *    "2": {...}, ...}
 *
 * Coordinates are session coordinates divided by the session resolution. That gives the units
 * of the DSN text that the editor sent.
 */
final class SesConverter
{
    static final class Result
    {
        String json;
        int net_count;
        int wire_count;
        int via_count;
        /** Sum of the wire lengths in the units of the DSN text. */
        double wire_length;
    }

    private SesConverter()
    {
    }

    /** One node of the parsed text: a list of children. A leaf is a String. */
    static List<Object> parse(String p_text)
    {
        List<List<Object>> stack = new ArrayList<List<Object>>();
        List<Object> root = new ArrayList<Object>();
        stack.add(root);
        int n = p_text.length();
        int i = 0;
        while (i < n)
        {
            char c = p_text.charAt(i);
            if (c == '(')
            {
                List<Object> child = new ArrayList<Object>();
                stack.get(stack.size() - 1).add(child);
                stack.add(child);
                ++i;
            }
            else if (c == ')')
            {
                if (stack.size() > 1)
                {
                    stack.remove(stack.size() - 1);
                }
                ++i;
            }
            else if (c == '"')
            {
                int end = p_text.indexOf('"', i + 1);
                if (end < 0)
                {
                    end = n;
                }
                stack.get(stack.size() - 1).add(p_text.substring(i + 1, end));
                i = end + 1;
            }
            else if (Character.isWhitespace(c))
            {
                ++i;
            }
            else
            {
                int end = i;
                while (end < n)
                {
                    char e = p_text.charAt(end);
                    if (e == '(' || e == ')' || e == '"' || Character.isWhitespace(e))
                    {
                        break;
                    }
                    ++end;
                }
                stack.get(stack.size() - 1).add(p_text.substring(i, end));
                i = end;
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    static void find_all(Object p_node, String p_name, List<List<Object>> p_out)
    {
        if (!(p_node instanceof List))
        {
            return;
        }
        List<Object> list = (List<Object>) p_node;
        if (!list.isEmpty() && p_name.equals(list.get(0)))
        {
            p_out.add(list);
        }
        for (Object child : list)
        {
            find_all(child, p_name, p_out);
        }
    }

    static double number(Object p_token)
    {
        if (p_token instanceof String)
        {
            try
            {
                return Double.parseDouble((String) p_token);
            }
            catch (NumberFormatException e)
            {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static int layer_id(Object p_token)
    {
        if (p_token instanceof String)
        {
            try
            {
                return Integer.parseInt(((String) p_token).trim());
            }
            catch (NumberFormatException e)
            {
                return 0;
            }
        }
        return 0;
    }

    /**
     * @param p_ses           text of the session file
     * @param p_renamed_nets  alias in the session file, original net name of the editor
     */
    static Result convert(String p_ses, Map<String, String> p_renamed_nets)
    {
        List<Object> root = parse(p_ses);
        List<List<Object>> routes = new ArrayList<List<Object>>();
        find_all(root, "routes", routes);
        Object scope = routes.isEmpty() ? root : routes.get(0);

        double divisor = 1.0;
        List<List<Object>> resolutions = new ArrayList<List<Object>>();
        find_all(scope, "resolution", resolutions);
        if (!resolutions.isEmpty() && resolutions.get(0).size() >= 3)
        {
            double value = number(resolutions.get(0).get(2));
            if (value > 0)
            {
                divisor = value;
            }
        }

        List<List<Object>> network = new ArrayList<List<Object>>();
        find_all(scope, "network_out", network);

        Result result = new Result();
        StringBuilder sb = new StringBuilder(Math.max(1024, p_ses.length() / 2));
        sb.append('{');
        int index = 0;
        for (List<Object> network_out : network)
        {
            for (Object child : network_out)
            {
                if (!(child instanceof List))
                {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Object> net = (List<Object>) child;
                if (net.size() < 2 || !"net".equals(net.get(0)) || !(net.get(1) instanceof String))
                {
                    continue;
                }
                String name = (String) net.get(1);
                String original = p_renamed_nets == null ? null : p_renamed_nets.get(name);
                if (original != null)
                {
                    name = original;
                }
                ++index;
                if (index > 1)
                {
                    sb.append(", ");
                }
                sb.append('"').append(index).append("\": {\"wires\": [");

                List<List<Object>> paths = new ArrayList<List<Object>>();
                find_all(net, "path", paths);
                boolean first_wire = true;
                for (List<Object> path : paths)
                {
                    // (path <layer> <width> x y x y ...)
                    if (path.size() < 7)
                    {
                        continue;
                    }
                    if (!first_wire)
                    {
                        sb.append(", ");
                    }
                    first_wire = false;
                    sb.append("{\"layerid\": ").append(layer_id(path.get(1))).append(", \"width\": ");
                    Json.append_number(sb, number(path.get(2)) / divisor);
                    sb.append(", \"points\": [");
                    boolean first_point = true;
                    double last_x = 0.0;
                    double last_y = 0.0;
                    for (int i = 3; i + 1 < path.size(); i += 2)
                    {
                        double x = number(path.get(i));
                        double y = number(path.get(i + 1));
                        if (Double.isNaN(x) || Double.isNaN(y))
                        {
                            continue;
                        }
                        if (!first_point)
                        {
                            result.wire_length += Math.hypot(x - last_x, y - last_y) / divisor;
                        }
                        last_x = x;
                        last_y = y;
                        if (!first_point)
                        {
                            sb.append(", ");
                        }
                        first_point = false;
                        Json.append_number(sb, x / divisor);
                        sb.append(", ");
                        Json.append_number(sb, y / divisor);
                    }
                    sb.append("]}");
                    ++result.wire_count;
                }
                sb.append("], \"vias\": [");

                List<List<Object>> vias = new ArrayList<List<Object>>();
                find_all(net, "via", vias);
                boolean first_via = true;
                for (List<Object> via : vias)
                {
                    // (via <padstack> x y)
                    if (via.size() < 4)
                    {
                        continue;
                    }
                    double x = number(via.get(2));
                    double y = number(via.get(3));
                    if (Double.isNaN(x) || Double.isNaN(y))
                    {
                        continue;
                    }
                    if (!first_via)
                    {
                        sb.append(", ");
                    }
                    first_via = false;
                    sb.append("{\"x\": ");
                    Json.append_number(sb, x / divisor);
                    sb.append(", \"y\": ");
                    Json.append_number(sb, y / divisor);
                    sb.append('}');
                    ++result.via_count;
                }
                sb.append("], \"net\": ");
                Json.append_string(sb, name);
                sb.append('}');
            }
        }
        sb.append('}');
        result.json = sb.toString();
        result.net_count = index;
        return result;
    }
}
