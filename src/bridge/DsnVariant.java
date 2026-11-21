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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Makes a second input for the engine that describes the same board in a different order.
 *
 * The engine routes the connections in the order in which it meets them. A different order gives
 * a different result. The bridge starts several runs with different orders and keeps the best.
 *
 * Three orders change:
 *   1. the (pin ...) entries of every (image ...) in (library ...)
 *   2. the (net ...) entries of (network ...)
 *   3. the pin references inside every (pins ...) list
 * The pads, the nets and the connections stay the same.
 */
final class DsnVariant
{
    private DsnVariant()
    {
    }

    /** The text in a new order. The same seed gives the same order. On a text that cannot be read: the text unchanged. */
    static String shuffle(String p_text, long p_seed)
    {
        try
        {
            Random random = new Random(p_seed);
            String text = p_text;
            int[] library = find_top(text, "library");
            if (library != null)
            {
                String lib = text.substring(library[0], library[1] + 1);
                StringBuilder sb = new StringBuilder(lib.length());
                int pos = 0;
                for (int[] child : children(lib, 0, lib.length() - 1))
                {
                    if ("image".equals(head(lib, child)))
                    {
                        sb.append(lib, pos, child[0]);
                        sb.append(permute_children(lib, child[0], child[1] - 1, "pin", random));
                        pos = child[1];
                    }
                }
                sb.append(lib, pos, lib.length());
                text = text.substring(0, library[0]) + sb + text.substring(library[1] + 1);
            }
            int[] network = find_top(text, "network");
            if (network != null)
            {
                String net_text = permute_children(text, network[0], network[1], "net", random);
                net_text = shuffle_pin_lists(net_text, random);
                text = text.substring(0, network[0]) + net_text + text.substring(network[1] + 1);
            }
            return text;
        }
        catch (RuntimeException e)
        {
            return p_text;
        }
    }

    /**
     * true: the character at p_i is a quote that starts or ends a quoted text. The quote in "(string_quote ")" of the
     * parser scope is not one. The board text of a board file job has that scope. The text of the editor does not.
     * Without this test the scan failed on a board file job, and each run with a different order got the text unchanged.
     */
    private static boolean is_quote(String p_text, int p_i)
    {
        return p_text.charAt(p_i) == '"' && !(p_i >= 13 && p_text.startsWith("string_quote ", p_i - 13));
    }

    /** Index of the ')' that closes the '(' at p_start. */
    private static int match_paren(String p_text, int p_start)
    {
        int depth = 0;
        boolean in_quote = false;
        for (int i = p_start; i < p_text.length(); ++i)
        {
            char c = p_text.charAt(i);
            if (in_quote)
            {
                if (is_quote(p_text, i))
                {
                    in_quote = false;
                }
            }
            else if (is_quote(p_text, i))
            {
                in_quote = true;
            }
            else if (c == '(')
            {
                ++depth;
            }
            else if (c == ')')
            {
                --depth;
                if (depth == 0)
                {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("unbalanced parentheses");
    }

    /** The direct child lists of the list p_text[p_start .. p_end]. Each entry is {first index, index after the last}. */
    private static List<int[]> children(String p_text, int p_start, int p_end)
    {
        List<int[]> result = new ArrayList<int[]>();
        boolean in_quote = false;
        int i = p_start + 1;
        while (i < p_end)
        {
            char c = p_text.charAt(i);
            if (in_quote)
            {
                if (is_quote(p_text, i))
                {
                    in_quote = false;
                }
                ++i;
            }
            else if (is_quote(p_text, i))
            {
                in_quote = true;
                ++i;
            }
            else if (c == '(')
            {
                int close = match_paren(p_text, i);
                result.add(new int[]
                {
                    i, close + 1
                });
                i = close + 1;
            }
            else
            {
                ++i;
            }
        }
        return result;
    }

    /** The first word of a list. */
    private static String head(String p_text, int[] p_span)
    {
        int i = p_span[0] + 1;
        while (i < p_span[1] && Character.isWhitespace(p_text.charAt(i)))
        {
            ++i;
        }
        int end = i;
        while (end < p_span[1])
        {
            char c = p_text.charAt(end);
            if (Character.isWhitespace(c) || c == '(' || c == ')')
            {
                break;
            }
            ++end;
        }
        return p_text.substring(i, end);
    }

    /** The list p_text[p_start .. p_end] with its direct children called p_name in a new order. Other children keep their places. */
    private static String permute_children(String p_text, int p_start, int p_end, String p_name, Random p_random)
    {
        List<int[]> spans = new ArrayList<int[]>();
        for (int[] child : children(p_text, p_start, p_end))
        {
            if (p_name.equals(head(p_text, child)))
            {
                spans.add(child);
            }
        }
        if (spans.size() < 2)
        {
            return p_text.substring(p_start, p_end + 1);
        }
        List<String> bodies = new ArrayList<String>();
        for (int[] span : spans)
        {
            bodies.add(p_text.substring(span[0], span[1]));
        }
        Collections.shuffle(bodies, p_random);
        StringBuilder sb = new StringBuilder(p_end - p_start + 1);
        int pos = p_start;
        for (int i = 0; i < spans.size(); ++i)
        {
            sb.append(p_text, pos, spans.get(i)[0]);
            sb.append(bodies.get(i));
            pos = spans.get(i)[1];
        }
        sb.append(p_text, pos, p_end + 1);
        return sb.toString();
    }

    /** Span {index of '(', index of ')'} of the first list called p_name directly below the outermost list. */
    private static int[] find_top(String p_text, String p_name)
    {
        int root = p_text.indexOf('(');
        if (root < 0)
        {
            return null;
        }
        int root_end = match_paren(p_text, root);
        for (int[] child : children(p_text, root, root_end))
        {
            if (p_name.equals(head(p_text, child)))
            {
                return new int[]
                {
                    child[0], child[1] - 1
                };
            }
        }
        return null;
    }

    private static String shuffle_pin_lists(String p_network, Random p_random)
    {
        StringBuilder sb = new StringBuilder(p_network.length());
        int pos = 0;
        while (true)
        {
            int start = p_network.indexOf("(pins", pos);
            if (start < 0)
            {
                break;
            }
            int end = match_paren(p_network, start);
            String body = p_network.substring(start + 5, end).trim();
            sb.append(p_network, pos, start);
            if (body.isEmpty() || body.indexOf('"') >= 0)
            {
                // Quoted pin names can hold spaces. Keep such a list as it is.
                sb.append(p_network, start, end + 1);
            }
            else
            {
                List<String> tokens = new ArrayList<String>(Arrays.asList(body.split("\\s+")));
                Collections.shuffle(tokens, p_random);
                sb.append("(pins ").append(String.join(" ", tokens)).append(')');
            }
            pos = end + 1;
        }
        sb.append(p_network, pos, p_network.length());
        return sb.toString();
    }
}
