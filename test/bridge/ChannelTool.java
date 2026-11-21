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

/** Test tool: ChannelTool <editor.dsn> <out.dsn> [rules.json]. Repairs the text of the editor and applies the rules. Prints the engine arguments. */
public final class ChannelTool
{
    public static void main(String[] p_args) throws Exception
    {
        String dsn = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        Rules rules = p_args.length > 2 ? Rules.load(Paths.get(p_args[2])) : new Rules();
        DsnRepair.Result repaired = DsnRepair.repair(dsn, new DsnRepair.Options());
        ChannelSetup setup = new ChannelSetup(repaired.text, rules, repaired.renamed_nets);
        Files.write(Paths.get(p_args[1]), setup.text.getBytes(StandardCharsets.UTF_8));
        for (String w : rules.warnings)
        {
            System.err.println("warning: " + w);
        }
        for (String note : setup.notes)
        {
            System.err.println("note: " + note);
        }
        System.out.println(String.join(" ", setup.args));
    }
}
