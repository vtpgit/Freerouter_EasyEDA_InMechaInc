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
import java.util.List;
import java.util.Map;

/**
 * Counts the track segments of an engine result that are narrower than the width rule of their net.
 * The engine narrows a track where the full width does not fit between pads.
 */
final class NarrowCount
{
    int segments = 0;
    /** Smallest width in the unit of the board text. */
    double min_width = Double.MAX_VALUE;
    /** Length of the narrow segments in the unit of the board text. */
    double length = 0.0;

    /**
     * @param p_dsn the board text that the engine got
     * @param p_ses the session file of the engine
     */
    NarrowCount(String p_dsn, String p_ses)
    {
        try
        {
            List<Object> board = SesConverter.parse(p_dsn);
            double default_width = -1.0;
            Map<String, Double> width_of_net = new HashMap<String, Double>();
            List<List<Object>> structures = new ArrayList<List<Object>>();
            SesConverter.find_all(board, "structure", structures);
            if (!structures.isEmpty())
            {
                for (Object child : structures.get(0))
                {
                    if (child instanceof List && !((List<?>) child).isEmpty() && "rule".equals(((List<?>) child).get(0)))
                    {
                        double w = width_in(child);
                        if (w > 0)
                        {
                            default_width = w;
                        }
                    }
                }
            }
            List<List<Object>> classes = new ArrayList<List<Object>>();
            SesConverter.find_all(board, "class", classes);
            for (List<Object> c : classes)
            {
                double w = -1.0;
                for (Object child : c)
                {
                    if (child instanceof List && !((List<?>) child).isEmpty() && "rule".equals(((List<?>) child).get(0)))
                    {
                        w = width_in(child);
                    }
                }
                if (w <= 0)
                {
                    continue;
                }
                for (int i = 2; i < c.size(); ++i)
                {
                    if (c.get(i) instanceof String)
                    {
                        width_of_net.put((String) c.get(i), w);
                    }
                }
            }
            List<Object> session = SesConverter.parse(p_ses);
            double scale = session_scale(session);
            List<List<Object>> network = new ArrayList<List<Object>>();
            SesConverter.find_all(session, "network_out", network);
            for (List<Object> network_out : network)
            {
                for (Object child : network_out)
                {
                    if (!(child instanceof List) || ((List<?>) child).size() < 2 || !"net".equals(((List<?>) child).get(0)))
                    {
                        continue;
                    }
                    List<?> net = (List<?>) child;
                    Double rule = width_of_net.get(String.valueOf(net.get(1)));
                    double width = rule != null ? rule : default_width;
                    if (width <= 0)
                    {
                        continue;
                    }
                    List<List<Object>> paths = new ArrayList<List<Object>>();
                    SesConverter.find_all(child, "path", paths);
                    for (List<Object> path : paths)
                    {
                        if (path.size() < 7)
                        {
                            continue;
                        }
                        double w = SesConverter.number(path.get(2)) * scale;
                        // The session file holds whole steps. A width that is up to 2 steps below the rule is the rule width.
                        if (w < width - 2.0 * scale - width * 0.002)
                        {
                            ++segments;
                            min_width = Math.min(min_width, w);
                            for (int i = 3; i + 3 < path.size(); i += 2)
                            {
                                length += Math.hypot(SesConverter.number(path.get(i + 2)) - SesConverter.number(path.get(i)),
                                        SesConverter.number(path.get(i + 3)) - SesConverter.number(path.get(i + 1))) * scale;
                            }
                        }
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            segments = -1;
        }
    }

    private static double width_in(Object p_rule)
    {
        List<List<Object>> widths = new ArrayList<List<Object>>();
        SesConverter.find_all(p_rule, "width", widths);
        return widths.isEmpty() || widths.get(0).size() < 2 ? -1.0 : SesConverter.number(widths.get(0).get(1));
    }

    /**
     * Factor from a number of the session file to the unit of the board text.
     * The session file has (resolution unit n) inside (routes ...): n steps for one unit, and its numbers are steps.
     * The board text has its numbers in the unit. The engine writes the session file in the unit of the board text.
     */
    private static double session_scale(List<Object> p_session)
    {
        List<List<Object>> routes = new ArrayList<List<Object>>();
        SesConverter.find_all(p_session, "routes", routes);
        if (routes.isEmpty())
        {
            return 1.0;
        }
        List<List<Object>> resolutions = new ArrayList<List<Object>>();
        SesConverter.find_all(routes.get(0), "resolution", resolutions);
        if (resolutions.isEmpty() || resolutions.get(0).size() < 3)
        {
            return 1.0;
        }
        double steps = SesConverter.number(resolutions.get(0).get(2));
        return steps > 0 ? 1.0 / steps : 1.0;
    }
}
