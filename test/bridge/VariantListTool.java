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

/** Test tool: prints the run list for each combination of the two switches. */
public final class VariantListTool
{
    public static void main(String[] p_args)
    {
        for (boolean first : new boolean[] { true, false })
        {
            for (boolean pads : new boolean[] { true, false })
            {
                StringBuilder sb = new StringBuilder("first stage " + first + ", channel " + pads + ":");
                for (Variant v : Variant.built_in(6, first, pads))
                {
                    sb.append(" [").append(v.name).append(" | seed ").append(v.order_seed).append(" | ").append(v.args.isEmpty() ? "fanout" : "no fanout")
                            .append(" | ").append(v.classic ? "classic" : "rules").append("]");
                }
                System.out.println(sb);
            }
        }
    }
}
