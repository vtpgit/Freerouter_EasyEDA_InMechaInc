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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs defects in the Specctra DSN text that the EasyEDA editor sends, before the routing
 * engine reads it. Each repair was found on real boards. See README.md, section "Input repair".
 */
final class DsnRepair
{
    static final class Options
    {
        boolean join_outline = true;
        boolean fix_zero_size_pads = true;
        boolean rename_nets = true;
        boolean drop_protected_nets = true;
        /** No effect was measured with Freerouting 2.5.0. Off by default. */
        boolean reorder_layers = false;
    }

    static final class Result
    {
        String text;
        /** alias used in the repaired text, original net name */
        final Map<String, String> renamed_nets = new LinkedHashMap<String, String>();
        /** original names of the nets that are not to be routed */
        final List<String> protected_nets = new ArrayList<String>();
        /** one line per repair, for the console */
        final List<String> notes = new ArrayList<String>();
    }

    private static final Pattern BOUNDARY =
            Pattern.compile("\\(boundary\\s*\\(path\\s+(\\S+)\\s+(\\S+)\\s+([^)]*)\\)\\s*\\)");
    private static final Pattern ANY_BOUNDARY = Pattern.compile("\\(boundary\\b");
    private static final Pattern POLYGON_SHAPE =
            Pattern.compile("\\(shape\\s*\\(polygon\\s+(\\S+)\\s+(\\S+)\\s+([^)]*)\\)\\s*\\)");
    private static final Pattern NET_DECLARATION = Pattern.compile("(\\(net\\s+)(\\S+)(\\s+\\(pins)");
    private static final Pattern PROTECTED_NET =
            Pattern.compile("\\(net\\s+(\\S+)\\s+\\(pins[^)]*\\)\\s*\\(type\\s+protect\\)\\s*\\)[ \\t]*\\n?");
    private static final Pattern SAFE_NET_NAME = Pattern.compile("[A-Za-z0-9_+.\\-]+");
    private static final Pattern SIGNAL_LAYER =
            Pattern.compile("\\(layer\\s+(\\S+)\\s*\\(type\\s+signal\\)\\s*\\)[ \\t]*\\n?");

    /** End points closer than this, in DSN units (10 mil for EasyEDA), are treated as one point. */
    private static final double JOIN_TOLERANCE = 1.0;
    /** Diameter of the circle that replaces a pad without area, in DSN units. */
    private static final String ZERO_PAD_DIAMETER = "0.2";

    private DsnRepair()
    {
    }

    static Result repair(String p_dsn, Options p_options)
    {
        Result result = new Result();
        String text = p_dsn.replace("\r\n", "\n");
        if (p_options.join_outline)
        {
            text = join_outline(text, result);
        }
        if (p_options.rename_nets)
        {
            text = rename_nets(text, result);
        }
        if (p_options.fix_zero_size_pads)
        {
            text = fix_zero_size_pads(text, result);
        }
        if (p_options.drop_protected_nets)
        {
            text = drop_protected_nets(text, result);
        }
        if (p_options.reorder_layers)
        {
            text = reorder_layers(text, result);
        }
        result.text = text;
        return result;
    }

    // ---------------------------------------------------------------------------------- outline

    private static final class Corner
    {
        final String x_token;
        final String y_token;
        final double x;
        final double y;

        Corner(String p_x, String p_y)
        {
            x_token = p_x;
            y_token = p_y;
            x = Double.parseDouble(p_x);
            y = Double.parseDouble(p_y);
        }

        boolean near(Corner p_other)
        {
            return Math.abs(x - p_other.x) <= JOIN_TOLERANCE && Math.abs(y - p_other.y) <= JOIN_TOLERANCE;
        }
    }

    /**
     * EasyEDA writes one (boundary(path ...)) per outline track when the outline was drawn as
     * separate segments. The engine makes one outline shape of each piece, and a shape inside
     * another becomes a keepout on all layers. This joins pieces by their end points. The text is
     * changed only when every resulting chain is a closed loop with area: then one path is written
     * per loop, the largest first (the outline, then cutouts).
     */
    static String join_outline(String p_text, Result p_result)
    {
        List<int[]> spans = new ArrayList<int[]>();
        List<List<Corner>> pieces = new ArrayList<List<Corner>>();
        String layer = null;
        String width = null;
        Matcher m = BOUNDARY.matcher(p_text);
        while (m.find())
        {
            String[] tokens = m.group(3).trim().split("\\s+");
            if (tokens.length < 4 || tokens.length % 2 != 0)
            {
                return p_text; // not the expected form
            }
            List<Corner> piece = new ArrayList<Corner>();
            try
            {
                for (int i = 0; i < tokens.length; i += 2)
                {
                    piece.add(new Corner(tokens[i], tokens[i + 1]));
                }
            }
            catch (NumberFormatException e)
            {
                return p_text;
            }
            if (layer == null)
            {
                layer = m.group(1);
                width = m.group(2);
            }
            spans.add(new int[]
                    {
                        m.start(), m.end()
                    });
            pieces.add(piece);
        }
        if (pieces.size() < 2)
        {
            return p_text;
        }
        int boundary_count = 0;
        Matcher any = ANY_BOUNDARY.matcher(p_text);
        while (any.find())
        {
            ++boundary_count;
        }
        if (boundary_count != pieces.size())
        {
            return p_text; // other boundary forms are present. Do not touch.
        }

        List<List<Corner>> remaining = new ArrayList<List<Corner>>(pieces);
        List<List<Corner>> chains = new ArrayList<List<Corner>>();
        while (!remaining.isEmpty())
        {
            List<Corner> chain = new ArrayList<Corner>(remaining.remove(0));
            boolean progress = true;
            while (progress && !is_closed(chain))
            {
                progress = false;
                for (int i = 0; i < remaining.size(); ++i)
                {
                    List<Corner> piece = remaining.get(i);
                    Corner chain_first = chain.get(0);
                    Corner chain_last = chain.get(chain.size() - 1);
                    Corner piece_first = piece.get(0);
                    Corner piece_last = piece.get(piece.size() - 1);
                    if (chain_last.near(piece_first))
                    {
                        chain.addAll(piece.subList(1, piece.size()));
                    }
                    else if (chain_last.near(piece_last))
                    {
                        List<Corner> reversed = new ArrayList<Corner>(piece.subList(0, piece.size() - 1));
                        Collections.reverse(reversed);
                        chain.addAll(reversed);
                    }
                    else if (chain_first.near(piece_last))
                    {
                        List<Corner> joined = new ArrayList<Corner>(piece.subList(0, piece.size() - 1));
                        joined.addAll(chain);
                        chain = joined;
                    }
                    else if (chain_first.near(piece_first))
                    {
                        List<Corner> reversed = new ArrayList<Corner>(piece.subList(1, piece.size()));
                        Collections.reverse(reversed);
                        reversed.addAll(chain);
                        chain = reversed;
                    }
                    else
                    {
                        continue;
                    }
                    remaining.remove(i);
                    progress = true;
                    break;
                }
            }
            chains.add(chain);
        }

        for (List<Corner> chain : chains)
        {
            if (!is_closed(chain) || area(chain) < 1e-6)
            {
                p_result.notes.add("outline: " + pieces.size() + " pieces, not all can be joined to closed loops. Left as sent."
                        + " Draw the board outline as closed shapes to avoid routing failures.");
                return p_text;
            }
        }
        if (chains.size() == pieces.size())
        {
            return p_text; // every piece was closed already
        }
        // largest loop first
        chains.sort((a, b) -> Double.compare(bounding_area(b), bounding_area(a)));

        StringBuilder replacement = new StringBuilder();
        for (int c = 0; c < chains.size(); ++c)
        {
            List<Corner> chain = chains.get(c);
            replacement.append(c == 0 ? "" : "\n    ").append("(boundary(path ").append(layer).append(' ').append(width);
            Corner previous = null;
            for (int i = 0; i < chain.size(); ++i)
            {
                // the last corner repeats the first one exactly
                Corner corner = i == chain.size() - 1 ? chain.get(0) : chain.get(i);
                if (previous != null && previous.x == corner.x && previous.y == corner.y)
                {
                    continue;
                }
                replacement.append(' ').append(corner.x_token).append(' ').append(corner.y_token);
                previous = corner;
            }
            replacement.append(" )\n    )");
        }

        StringBuilder out = new StringBuilder(p_text.length());
        int cursor = 0;
        for (int i = 0; i < spans.size(); ++i)
        {
            int[] span = spans.get(i);
            String between = p_text.substring(cursor, span[0]);
            if (i == 0)
            {
                out.append(between).append(replacement);
            }
            else if (between.trim().length() > 0)
            {
                out.append(between); // unexpected text between the pieces. Keep it.
            }
            cursor = span[1];
        }
        out.append(p_text, cursor, p_text.length());
        p_result.notes.add("outline: joined " + pieces.size() + " pieces into " + chains.size()
                + (chains.size() == 1 ? " closed loop" : " closed loops"));
        return out.toString();
    }

    private static boolean is_closed(List<Corner> p_chain)
    {
        return p_chain.size() > 2 && p_chain.get(0).near(p_chain.get(p_chain.size() - 1));
    }

    private static double area(List<Corner> p_chain)
    {
        double sum = 0;
        for (int i = 0; i + 1 < p_chain.size(); ++i)
        {
            sum += p_chain.get(i).x * p_chain.get(i + 1).y - p_chain.get(i + 1).x * p_chain.get(i).y;
        }
        return Math.abs(sum) / 2;
    }

    private static double bounding_area(List<Corner> p_chain)
    {
        double min_x = Double.MAX_VALUE;
        double min_y = Double.MAX_VALUE;
        double max_x = -Double.MAX_VALUE;
        double max_y = -Double.MAX_VALUE;
        for (Corner c : p_chain)
        {
            min_x = Math.min(min_x, c.x);
            max_x = Math.max(max_x, c.x);
            min_y = Math.min(min_y, c.y);
            max_y = Math.max(max_y, c.y);
        }
        return (max_x - min_x) * (max_y - min_y);
    }

    // ------------------------------------------------------------------------------------- pads

    /**
     * A pad polygon whose corners are all the same point has no area. The original EasyEDA router
     * stops with an exception on it. It is replaced with a small circle at that point.
     */
    static String fix_zero_size_pads(String p_text, Result p_result)
    {
        Matcher m = POLYGON_SHAPE.matcher(p_text);
        StringBuilder out = null;
        int cursor = 0;
        int count = 0;
        while (m.find())
        {
            String[] tokens = m.group(3).trim().split("\\s+");
            if (tokens.length < 2)
            {
                continue;
            }
            Set<String> distinct = new HashSet<String>();
            boolean numeric = true;
            for (int i = 0; i + 1 < tokens.length; i += 2)
            {
                try
                {
                    distinct.add(Double.parseDouble(tokens[i]) + "/" + Double.parseDouble(tokens[i + 1]));
                }
                catch (NumberFormatException e)
                {
                    numeric = false;
                    break;
                }
            }
            if (!numeric || distinct.size() >= 3)
            {
                continue;
            }
            if (out == null)
            {
                out = new StringBuilder(p_text.length());
            }
            out.append(p_text, cursor, m.start());
            out.append("(shape(circle ").append(m.group(1)).append(' ').append(ZERO_PAD_DIAMETER).append(' ')
                    .append(tokens[0]).append(' ').append(tokens[1]).append("))");
            cursor = m.end();
            ++count;
        }
        if (out == null)
        {
            return p_text;
        }
        out.append(p_text, cursor, p_text.length());
        p_result.notes.add("pads: replaced " + count + " pad shapes without area by a small circle");
        return out.toString();
    }

    // ------------------------------------------------------------------------------------- nets

    private static int network_start(String p_text)
    {
        int index = p_text.indexOf("(network");
        return index < 0 ? p_text.length() : index;
    }

    /**
     * Net names with characters such as = ~ [ ] \ ' ; , / stop the DSN reader. Such nets get the
     * alias NETX1, NETX2, ... The result is mapped back to the original names.
     */
    static String rename_nets(String p_text, Result p_result)
    {
        int start = network_start(p_text);
        String head = p_text.substring(0, start);
        String body = p_text.substring(start);
        String prefix = "NETX";
        while (body.contains("(net " + prefix))
        {
            prefix = prefix + "X";
        }
        Map<String, String> alias_of = new LinkedHashMap<String, String>();
        Matcher m = NET_DECLARATION.matcher(body);
        StringBuilder out = new StringBuilder(body.length());
        int cursor = 0;
        while (m.find())
        {
            String name = m.group(2);
            if (SAFE_NET_NAME.matcher(name).matches())
            {
                continue;
            }
            String alias = alias_of.get(name);
            if (alias == null)
            {
                alias = prefix + (alias_of.size() + 1);
                alias_of.put(name, alias);
                p_result.renamed_nets.put(alias, name);
            }
            out.append(body, cursor, m.start(2)).append(alias);
            cursor = m.end(2);
        }
        if (alias_of.isEmpty())
        {
            return p_text;
        }
        out.append(body, cursor, body.length());
        p_result.notes.add("nets: " + alias_of.size() + " net names with special characters renamed for the engine");
        return head + out;
    }

    /**
     * EasyEDA marks a net that must not be routed with (type protect). The engine does not know
     * this. The net is removed from the netlist. Its pads stay on the board without a net, so
     * other nets keep clear of them.
     */
    static String drop_protected_nets(String p_text, Result p_result)
    {
        int start = network_start(p_text);
        String head = p_text.substring(0, start);
        String body = p_text.substring(start);
        Matcher m = PROTECTED_NET.matcher(body);
        StringBuilder out = null;
        int cursor = 0;
        while (m.find())
        {
            if (out == null)
            {
                out = new StringBuilder(body.length());
            }
            String name = m.group(1);
            String original = p_result.renamed_nets.get(name);
            p_result.protected_nets.add(original != null ? original : name);
            out.append(body, cursor, m.start());
            cursor = m.end();
        }
        if (out == null)
        {
            return p_text;
        }
        out.append(body, cursor, body.length());
        p_result.notes.add("nets: " + p_result.protected_nets.size() + " nets marked as not to route: " + shorten(p_result.protected_nets));
        return head + out;
    }

    private static String shorten(List<String> p_names)
    {
        if (p_names.size() <= 6)
        {
            return String.join(", ", p_names);
        }
        return String.join(", ", p_names.subList(0, 6)) + ", ...";
    }

    // ----------------------------------------------------------------------------------- layers

    /**
     * EasyEDA lists the signal layers as top (1), bottom (2), inner (21, 22, ...). The engine takes
     * the list order as the physical order. This moves layer 2 to the end of the list. Layer names
     * do not change, so pads, vias and the result keep their layer ids.
     */
    static String reorder_layers(String p_text, Result p_result)
    {
        int structure_end = p_text.indexOf("(placement");
        if (structure_end < 0)
        {
            structure_end = p_text.length();
        }
        Matcher m = SIGNAL_LAYER.matcher(p_text);
        List<int[]> spans = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        while (m.find() && m.start() < structure_end)
        {
            spans.add(new int[]
                    {
                        m.start(), m.end()
                    });
            names.add(m.group(1));
        }
        if (names.size() < 3 || !"1".equals(names.get(0)) || !"2".equals(names.get(1)))
        {
            return p_text;
        }
        for (int i = 1; i < spans.size(); ++i)
        {
            // the layer entries must follow each other directly
            if (p_text.substring(spans.get(i - 1)[1], spans.get(i)[0]).trim().length() > 0)
            {
                return p_text;
            }
        }
        StringBuilder out = new StringBuilder(p_text.length());
        out.append(p_text, 0, spans.get(0)[0]);
        out.append(p_text, spans.get(0)[0], spans.get(1)[0]); // layer 1 and the gap behind it
        for (int i = 2; i < spans.size(); ++i)
        {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : spans.get(i)[1];
            out.append(p_text, spans.get(i)[0], end);
        }
        String bottom = p_text.substring(spans.get(1)[0], spans.get(1)[1]);
        String gap = p_text.substring(spans.get(1)[1], spans.get(2)[0]);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n' && gap.isEmpty())
        {
            out.append("\n    ");
        }
        out.append(gap).append(bottom);
        out.append(p_text, spans.get(spans.size() - 1)[1], p_text.length());
        p_result.notes.add("layers: order changed from top, bottom, inner to top, inner, bottom (" + names.size() + " layers)");
        return out.toString();
    }
}
