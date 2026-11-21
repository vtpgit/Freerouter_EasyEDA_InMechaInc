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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Commands without the editor:
 *   route <board.json> [<result.json>] [--time <seconds>] [--rules <rules.json>]
 *   check <board.json> [--rules <rules.json>]
 */
final class Cli
{
    private Cli()
    {
    }

    static int run(String[] p_args) throws IOException
    {
        String command = p_args[0].toLowerCase();
        List<String> files = new ArrayList<String>();
        int seconds = 0;
        Path rules_file = null;
        for (int i = 1; i < p_args.length; ++i)
        {
            String arg = p_args[i];
            if (arg.equals("--time") && i + 1 < p_args.length)
            {
                try
                {
                    seconds = Integer.parseInt(p_args[++i]);
                }
                catch (NumberFormatException e)
                {
                    System.err.println("--time needs a number of seconds.");
                    return 64;
                }
            }
            else if (arg.equals("--rules") && i + 1 < p_args.length)
            {
                rules_file = Paths.get(p_args[++i]);
            }
            else if (arg.startsWith("--"))
            {
                System.err.println("Unknown option: " + arg);
                return 64;
            }
            else
            {
                files.add(arg);
            }
        }
        if (command.equals("version") || command.equals("--version"))
        {
            System.out.println(Main.VERSION);
            return 0;
        }
        if ((!command.equals("route") && !command.equals("check")) || files.isEmpty())
        {
            help();
            return command.equals("help") || command.equals("--help") || command.equals("-h") ? 0 : 64;
        }
        Path input = Paths.get(files.get(0)).toAbsolutePath();
        if (!Files.isRegularFile(input))
        {
            System.err.println("File not found: " + input);
            return 66;
        }
        Config config = Config.load();
        if (rules_file == null)
        {
            rules_file = config.home.resolve("config").resolve("rules.json");
        }
        else if (!Files.isRegularFile(rules_file))
        {
            System.err.println("Rules file not found: " + rules_file);
            return 66;
        }
        Rules rules = Rules.load(rules_file);
        String json = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
        if (command.equals("check"))
        {
            return check(json, rules);
        }

        Log.info("EasyEDA to Freerouting bridge " + Main.VERSION + ". File routing.");
        if (!Main.check_engine(config))
        {
            return 2;
        }
        String name = input.getFileName().toString();
        String stem = name.toLowerCase().endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        Path output = files.size() > 1 ? Paths.get(files.get(1)).toAbsolutePath() : input.resolveSibling(stem + ".routed.json");
        if (output.equals(input))
        {
            System.err.println("The result file must not be the input file.");
            return 64;
        }
        String out_name = output.getFileName().toString();
        Path report = output.resolveSibling((out_name.toLowerCase().endsWith(".json") ? out_name.substring(0, out_name.length() - 5) : out_name)
                + ".report.txt");
        final FileJob job = new FileJob(config, rules, json, seconds, Main.job_counter.incrementAndGet());
        Runtime.getRuntime().addShutdownHook(new Thread(job::cancel, "stop-engines"));
        FileJob.Outcome outcome;
        try
        {
            outcome = job.run(null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return 130;
        }
        if (!outcome.ok)
        {
            System.err.println(outcome.message);
            return 1;
        }
        Files.write(output, outcome.json.getBytes(StandardCharsets.UTF_8));
        Files.write(report, outcome.report.getBytes(StandardCharsets.UTF_8));
        Log.info("Result file: " + output);
        Log.info("Report: " + report);
        System.out.println();
        System.out.println(outcome.report);
        return outcome.open_after == 0 ? 0 : 3;
    }

    /** Prints what the bridge reads from the file and what it plans. The engine does not run. */
    private static int check(String p_json, Rules p_rules)
    {
        EeBoard board;
        try
        {
            board = new EeBoard(p_json, p_rules.design_rules_first());
        }
        catch (RuntimeException e)
        {
            System.err.println("The file is not an EasyEDA Standard PCB file that the bridge can read (" + e.getMessage() + ").");
            return 65;
        }
        EePlan plan = new EePlan(board, p_rules);
        EeCheck check = new EeCheck(board, plan, p_rules.existing.equals("remove"));
        for (String warning : p_rules.warnings)
        {
            System.out.println("Warning: " + warning);
        }
        System.out.println("Parts: " + board.part_count + ". Pads: " + board.pads.size() + ". Copper layers: " + board.layers.size() + ".");
        System.out.println("Tracks in the file: " + board.tracks.size() + ". Vias in the file: " + board.vias.size() + ". Copper areas: "
                + board.areas.size() + ".");
        System.out.println("Nets to route: " + plan.nets.size() + ". Open connections" + (p_rules.existing.equals("remove")
                ? " after the removal of the tracks and vias that are not locked" : "") + ": " + check.open_connections + " in " + check.open_nets + " nets.");
        for (String line : FileJob.plan_lines(board, plan))
        {
            System.out.println("Plan: " + line);
        }
        if (check.open_connections > 0)
        {
            int shown = 0;
            for (EeCheck.NetState state : check.nets.values())
            {
                if (state.groups > 1 && shown++ < 40)
                {
                    System.out.println("Open: " + state.net + ": " + (state.groups - 1) + " of " + (state.pads - 1) + " connections.");
                }
            }
            if (shown > 40)
            {
                System.out.println("Open: " + (shown - 40) + " more nets.");
            }
        }
        return 0;
    }

    private static void help()
    {
        System.out.println("EasyEDA to Freerouting bridge " + Main.VERSION);
        System.out.println();
        System.out.println("No argument: start the local auto router for the EasyEDA editor.");
        System.out.println();
        System.out.println("route <board.json> [<result.json>] [--time <seconds>] [--rules <rules.json>]");
        System.out.println("    Route an EasyEDA Standard PCB file. The result is a new file. The input file does not change.");
        System.out.println("    Without <result.json> the result is <board>.routed.json. A report is written next to the result.");
        System.out.println("    Exit code 0: all connections made. 3: some connections are open. 1: no result.");
        System.out.println();
        System.out.println("check <board.json> [--rules <rules.json>]");
        System.out.println("    Show what the bridge reads from the file and what it plans. The engine does not run.");
        System.out.println();
        System.out.println("The rules are in config/rules.json. The time of a job comes from --time or from the rules.");
    }
}
