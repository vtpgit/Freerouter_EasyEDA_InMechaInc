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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test tool: MoveTool <board.json> <out.json> <REF> <dx mm> <dy mm> [turn]. Moves one part and checks the file that
 * comes out with the reader of the bridge. REF "ALL": every part in turn, no file, counts only.
 */
public final class MoveTool
{
    public static void main(String[] p_args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        EeBoard board = new EeBoard(json);
        Map<String, EeMove.Part> parts = EeMove.parts(board);
        double dx = Double.parseDouble(p_args[3]), dy = Double.parseDouble(p_args[4]);
        boolean turn = p_args.length > 5 && p_args[5].equals("turn");
        int good = 0, bad = 0, refused = 0;
        for (EeMove.Part part : parts.values())
        {
            if (!p_args[2].equals("ALL") && !p_args[2].equals(part.ref))
            {
                continue;
            }
            String record;
            try
            {
                if (part.fixed_reason != null)
                {
                    throw new EeMove.Unsupported(part.fixed_reason);
                }
                record = EeMove.moved_record(board, board.records.get(part.record), dx, dy, turn, part.cx(), part.cy());
            }
            catch (EeMove.Unsupported e)
            {
                ++refused;
                System.out.println(part.ref + ": not moved: " + e.getMessage());
                continue;
            }
            Map<Integer, String> changed = new LinkedHashMap<Integer, String>();
            changed.put(part.record, record);
            String text = EeMove.with_records(board, changed);
            EeBoard moved = new EeBoard(text);
            String error = compare(board, moved, part, dx, dy, turn);
            // the same number of shapes and of fields in each shape
            String[] a = board.records.get(part.record).split("#@\\$", -1), b = record.split("#@\\$", -1);
            if (error == null && a.length != b.length)
            {
                error = "shape count " + a.length + " -> " + b.length;
            }
            for (int i = 0; error == null && i < a.length; ++i)
            {
                if (!a[i].startsWith("SVGNODE~") && a[i].split("~", -1).length != b[i].split("~", -1).length)
                {
                    error = "field count of shape " + i;
                }
            }
            // a move back gives the first record again, up to the rounding of the file
            if (error == null)
            {
                EeMove.Part again = EeMove.parts(moved).get(part.ref);
                String back = EeMove.moved_record(moved, record, -dx, -dy, turn, again.cx(), again.cy());
                Map<Integer, String> m2 = new LinkedHashMap<Integer, String>();
                m2.put(part.record, back);
                EeBoard restored = new EeBoard(EeMove.with_records(moved, m2));
                error = compare(board, restored, part, 0, 0, false);
                if (error != null)
                {
                    error = "move back: " + error;
                }
            }
            if (error != null)
            {
                ++bad;
                System.out.println(part.ref + ": ERROR " + error);
            }
            else
            {
                ++good;
            }
            if (!p_args[2].equals("ALL"))
            {
                Files.write(Paths.get(p_args[1]), text.getBytes(StandardCharsets.UTF_8));
                System.out.println(part.ref + " package " + part.pack + ", pads " + part.pads.size() + ", body box " + EeMove.mm(part.x0) + " " + EeMove.mm(part.y0) + " "
                        + EeMove.mm(part.x1) + " " + EeMove.mm(part.y1) + ", size " + EeMove.mm(part.x1 - part.x0) + " x " + EeMove.mm(part.y1 - part.y0));
            }
        }
        System.out.println("parts " + parts.size() + ", moved and checked " + good + ", errors " + bad + ", not moved " + refused);
    }

    private static String compare(EeBoard p_a, EeBoard p_b, EeMove.Part p_part, double p_dx, double p_dy, boolean p_turn)
    {
        if (p_a.pads.size() != p_b.pads.size() || p_a.records.size() != p_b.records.size() || p_a.holes.size() != p_b.holes.size()
                || p_a.solids.size() != p_b.solids.size() || p_a.footprint_tracks.size() != p_b.footprint_tracks.size())
        {
            return "counts differ";
        }
        for (int i = 0; i < p_a.pads.size(); ++i)
        {
            EeBoard.Pad a = p_a.pads.get(i), b = p_b.pads.get(i);
            if (!a.ref.equals(b.ref) || !a.number.equals(b.number) || !a.net.equals(b.net) || a.through != b.through)
            {
                return "pad " + i + " identity";
            }
            boolean mine = a.ref.equals(p_part.ref);
            double ex = !mine ? a.cx : p_turn ? 2 * p_part.cx() - a.cx + p_dx : a.cx + p_dx;
            double ey = !mine ? a.cy : p_turn ? 2 * p_part.cy() - a.cy + p_dy : a.cy + p_dy;
            if (Math.abs(b.cx - ex) > 0.0002 || Math.abs(b.cy - ey) > 0.0002)
            {
                return "pad " + a.ref + "." + a.number + " centre " + b.cx + " " + b.cy + ", expected " + ex + " " + ey;
            }
            double[] ba = a.box(), bb = b.box();
            double wa = ba[2] - ba[0], ha = ba[3] - ba[1], wb = bb[2] - bb[0], hb = bb[3] - bb[1];
            if (Math.abs(wa - wb) > 0.0003 || Math.abs(ha - hb) > 0.0003)
            {
                return "pad " + a.ref + "." + a.number + " size";
            }
            double bcx = (bb[0] + bb[2]) / 2.0, bcy = (bb[1] + bb[3]) / 2.0, acx = (ba[0] + ba[2]) / 2.0, acy = (ba[1] + ba[3]) / 2.0;
            double ecx = !mine ? acx : p_turn ? 2 * p_part.cx() - acx + p_dx : acx + p_dx;
            double ecy = !mine ? acy : p_turn ? 2 * p_part.cy() - acy + p_dy : acy + p_dy;
            if (Math.abs(bcx - ecx) > 0.0003 || Math.abs(bcy - ecy) > 0.0003)
            {
                return "pad " + a.ref + "." + a.number + " box";
            }
        }
        return null;
    }
}
