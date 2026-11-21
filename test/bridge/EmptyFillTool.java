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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Empties the stored fill of each copper area of a board file, as a job of the bridge does. All other text stays.
 * Usage: EmptyFillTool <in.json> <out.json>
 */
public class EmptyFillTool
{
    public static void main(String[] p_args) throws Exception
    {
        EeBoard board = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(board.text.length());
        sb.append(board.text, 0, board.shape_start + 1);
        int changed = 0;
        for (int i = 0; i < board.records.size(); ++i)
        {
            String record = board.records.get(i);
            String literal = null;
            if (record.startsWith("COPPERAREA~"))
            {
                String[] f = record.split("~", -1);
                if (f.length > 10 && !f[10].trim().equals("[]"))
                {
                    f[10] = "[]";
                    StringBuilder text = new StringBuilder();
                    Json.append_string(text, String.join("~", f));
                    literal = text.toString();
                    ++changed;
                }
            }
            if (literal == null)
            {
                int[] span = board.record_spans.get(i);
                literal = board.text.substring(span[0], span[1]);
            }
            sb.append(i == 0 ? "" : ",").append(literal);
        }
        sb.append(board.text, board.shape_end, board.text.length());
        Files.write(Paths.get(p_args[1]), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("copper areas with emptied fill: " + changed);
    }
}
