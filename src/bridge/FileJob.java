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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Routes an EasyEDA Standard PCB file (the JSON source of the editor) and gives back the same file
 * with the new tracks and vias.
 *
 * Differences from the editor channel: the bridge reads the copper areas, the footprints and the rules
 * from the file. So it can use planes, keep-outs, a second via size and vias in pads.
 */
final class FileJob
{
    static final class Outcome
    {
        boolean ok = false;
        /** Reason of a failure. */
        String message = "";
        /** The routed board file. */
        String json;
        String report = "";
        int open_before = -1;
        int open_after = -1;
        Path job_dir;
    }

    private static final class Candidate
    {
        EngineRun run;
        EeResult result;
        /** Tracks that join cut-off parts of a plane net to its plane. null: the rules switch them off. */
        EeBridge bridge;
        /** The board file of this run, with the bridge tracks. */
        String text;
        EeCheck check;
        double length;
        /** 1: first pass. 2 and more: a pass on the result of an earlier pass. */
        int pass = 1;
        EePlan plan;
        EeDsn dsn;
        String ses;
        /** How the input of this pass was made from the result of the pass before. null: first pass. */
        EeRework rework;
        /** The clean-up of the tracks. Only the chosen result has one. null: no clean-up. */
        EeTidy tidy;
        String tidy_note;
        /** What the bridge changed before each further pass that led to this result. */
        final List<String> pass_lines = new ArrayList<String>();
        /** true: the engine could put the small via into any pad in this run and in the runs that it goes on with. */
        boolean pad_via;
        /** true: the engine made no narrow tracks in the run of this result (rules: track.narrow = mixed). */
        boolean full_width;
        /** Vias of the result with their centre in a surface-mount pad of their net. */
        int vias_in_pads;
        /** true: at an equal count of open connections, the result with more vias in pads comes first (rules: via.on_pads = mixed). */
        boolean prefer_pad_vias;
        /** The board that this result belongs to: the board of the job, or that board with moved parts (rules: parts.move). */
        EeBoard board;
        /** The plan of that board without the changes of the further passes. */
        EePlan first_plan;
        /** The moves of parts that led to the board of this result, one line each. */
        final List<String> move_lines = new ArrayList<String>();
        /** Pads that stayed open and that no move could help, with the cause. From the last search on this result. */
        final List<String> not_moved = new ArrayList<String>();
        final Set<String> moved_refs = new LinkedHashSet<String>();
    }

    /** Largest number of passes on a result. */
    private static final int MAX_PASSES = 4;
    /** A further pass starts only when this time is left. */
    /** A further pass starts with this time left or more. A pass on a small board needs some seconds. A pass that the time stops is not chosen. */
    private static final long MIN_PASS_MS = 15000L;
    /** The reason why a job with open pads in copper areas had no further pass, for the report. */
    private volatile String no_pass_note;

    private final Config config;
    private final Rules rules;
    private final String board_json;
    private final int seconds;
    private final long id;
    private volatile boolean cancelled = false;
    private volatile EngineSet engines;

    /**
     * @param p_seconds time for the job. 0 or less: the time of the rules.
     */
    FileJob(Config p_config, Rules p_rules, String p_board_json, int p_seconds, long p_id)
    {
        this.config = p_config;
        this.rules = p_rules;
        this.board_json = p_board_json;
        this.seconds = p_seconds > 0 ? p_seconds : p_rules.job_seconds;
        this.id = p_id;
    }

    void cancel()
    {
        cancelled = true;
        EngineSet set = engines;
        if (set != null)
        {
            set.cancel();
        }
    }

    Outcome run(EngineSet.Ticker p_ticker) throws InterruptedException
    {
        long start = System.nanoTime();
        Outcome outcome = new Outcome();
        EngineSet set = null;
        List<EngineSet> more_sets = new ArrayList<EngineSet>();
        try
        {
            EeBoard board;
            try
            {
                board = new EeBoard(board_json, rules.design_rules_first());
            }
            catch (RuntimeException e)
            {
                outcome.message = "The file is not an EasyEDA Standard PCB file that the bridge can read (" + e.getMessage() + ").";
                Log.error("job " + id + ": " + outcome.message);
                return outcome;
            }
            EePlan plan = new EePlan(board, rules);
            EeDsn dsn = new EeDsn(board, plan, rules);
            EeCheck before = new EeCheck(board, plan, rules.existing.equals("remove"));
            outcome.open_before = before.open_connections;

            Path job_dir = config.home.resolve("jobs")
                    .resolve("job-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + id);
            Files.createDirectories(job_dir);
            outcome.job_dir = job_dir;
            Files.write(job_dir.resolve("original.json"), board_json.getBytes(StandardCharsets.UTF_8));

            StringBuilder memory_note = new StringBuilder();
            int run_count = config.runs_for_job(memory_note);
            int heap_mb = config.heap_mb_for(run_count);
            long budget_ms = seconds * 1000L;

            Log.info("job " + id + ": board file with " + board.part_count + " parts, " + board.pads.size() + " pads, " + plan.nets.size()
                    + " nets to route, " + board.layers.size() + " copper layers. Open connections: " + before.open_connections + ". Time "
                    + seconds + " s. " + (run_count == 1 ? "One engine run." : run_count + " engine runs at the same time."));
            if (memory_note.length() > 0)
            {
                Log.info("job " + id + ": " + memory_note);
            }
            for (String warning : rules.warnings)
            {
                Log.error("job " + id + ": " + warning);
            }
            for (String line : plan_lines(board, plan))
            {
                Log.info("job " + id + ": plan: " + line);
            }
            int kept_tracks = 0;
            for (EeBoard.Track track : board.tracks)
            {
                if (plan.nets.containsKey(track.net) && (track.locked || rules.existing.equals("keep")))
                {
                    ++kept_tracks;
                }
            }
            if (kept_tracks > 0 && before.open_connections > 0)
            {
                Log.info("job " + id + ": " + kept_tracks + " tracks of the file stay and are fixed. The engine reads an existing track as a connection"
                        + " only when it ends on the centre of a pad or on a second track. To route the board again from the start, set existing to remove.");
            }
            if (before.open_connections == 0)
            {
                outcome.message = "The board has no open connection. Nothing to route. To route the board again from the start, set existing to remove in the rules.";
                Log.info("job " + id + ": " + outcome.message);
                return outcome;
            }
            if (plan.nets.isEmpty())
            {
                outcome.message = "The board has no net with two or more pads to route.";
                Log.error("job " + id + ": " + outcome.message);
                return outcome;
            }

            set = new EngineSet(config, job_dir, id);
            engines = set;
            if (cancelled)
            {
                outcome.message = "cancelled";
                return outcome;
            }
            final boolean mixed = rules.via_on_pads.equals("mixed");
            // One run of four gets no narrow tracks from the engine (rules: track.narrow = mixed).
            final boolean narrow_mixed = rules.narrow_tracks.equals("mixed") && run_count >= 4;
            List<Variant> variants = Variant.built_in(run_count, !rules.narrow_tracks.equals("avoid"), false, mixed, narrow_mixed);
            // Pads in copper areas can need a second pass. The first pass leaves time for it.
            final boolean via_passes = rules.second_pass.equals("on") && rules.plane_bridges.equals("on") && !plan.pour_pads.isEmpty();
            // Routes of other runs for open connections (rules: recombine). They need the results of two runs or more.
            final boolean recombine = rules.recombine.equals("on") && run_count > 1;
            boolean second_pass = via_passes || recombine;
            // One quarter of the job time, 50 s to 300 s. A job of 600 s with a reserve of 60 s had no time left for a
            // further pass: the result files of 4 runs and their repair took 25 s of it.
            long reserve_ms = second_pass && budget_ms >= 150000L ? Math.max(50000L, Math.min(300000L, budget_ms / 4)) : 0L;
            long first_pass_ms = budget_ms - reserve_ms;
            // for tests of the further passes: a short first pass
            String test_first_pass = System.getProperty("bridge.test.first_pass_s");
            if (test_first_pass != null && second_pass)
            {
                first_pass_ms = Math.min(first_pass_ms, Long.parseLong(test_first_pass) * 1000L);
            }
            // for tests of the further passes: the first pass comes from the folder of an earlier job with the same plan
            String test_first_from = System.getProperty("bridge.test.first_pass_from");
            List<EngineRun> first_runs = new ArrayList<EngineRun>();
            if (test_first_from != null)
            {
                for (int i = 0; i < variants.size(); ++i)
                {
                    EngineRun run = new EngineRun(i + 1, variants.get(i), java.nio.file.Paths.get(test_first_from), id, false);
                    run.preloaded = true;
                    first_runs.add(run);
                }
            }
            else
            {
                boolean completed = set.run(dsn.text, variants, EngineSetup.args(rules, 1.0), first_pass_ms, start, heap_mb, p_ticker);
                if (!completed || cancelled)
                {
                    outcome.message = "cancelled";
                    return outcome;
                }
                first_runs.addAll(set.runs());
            }

            List<Candidate> candidates = new ArrayList<Candidate>();
            List<EngineRun> all_runs = new ArrayList<EngineRun>(first_runs);
            for (EngineRun run : first_runs)
            {
                Candidate c = candidate(board, plan, dsn, run, 1, null);
                if (c != null)
                {
                    candidates.add(c);
                }
            }
            Candidate best = choose(candidates, rules.narrow_tracks.equals("avoid") || narrow_mixed);

            // Further passes: vias for the pads that tracks cut off from their copper area, and the tracks in the way routed again.
            final boolean avoid_narrow = rules.narrow_tracks.equals("avoid") || narrow_mixed;
            List<Candidate> last = new ArrayList<Candidate>(candidates);
            for (int pass = 2; pass <= MAX_PASSES && second_pass && best != null && best.check.open_connections > 0 && !cancelled; ++pass)
            {
                long left_ms = budget_ms - config.finish_margin_ms - (System.nanoTime() - start) / 1000000L;
                if (left_ms < MIN_PASS_MS)
                {
                    if (pass == 2)
                    {
                        no_pass_note = "No further pass ran. " + Math.max(0L, left_ms / 1000L) + " s of the job time were left and a pass needs "
                                + (MIN_PASS_MS / 1000L) + " s. A longer job time (rules: time.job_s) gives the further passes their time.";
                        Log.info("job " + id + ": " + no_pass_note);
                    }
                    break;
                }
                last.sort((a, b) -> compare(a, b, avoid_narrow));
                List<Candidate> bases = new ArrayList<Candidate>();
                List<EeRework> reworks = new ArrayList<EeRework>();
                List<String> texts = new ArrayList<String>();
                List<Variant> pass_variants = new ArrayList<Variant>();
                // The runs of this pass, the best result first. For each result: the repair with vias, and the same with routes of
                // other runs where a closed group of nets can be taken (EeRework).
                List<Candidate> slot_bases = new ArrayList<Candidate>();
                List<EeRework> slot_reworks = new ArrayList<EeRework>();
                for (Candidate c : last)
                {
                    if (slot_bases.size() >= run_count || c.check.open_connections == 0)
                    {
                        continue;
                    }
                    EeRework plain = via_passes ? EeRework.build(c.board, rules, c.plan, c.dsn, c.ses, c.text, c.bridge) : null;
                    if (plain != null)
                    {
                        slot_bases.add(c);
                        slot_reworks.add(plain);
                    }
                    if (!recombine)
                    {
                        continue;
                    }
                    List<EeRework.Donor> donors = new ArrayList<EeRework.Donor>();
                    for (Candidate d : candidates)
                    {
                        // a result on a board with other part positions cannot give its routes
                        if (d != c && d.board == c.board)
                        {
                            donors.add(new EeRework.Donor((d.pass > 1 ? "pass " + d.pass + " " : "") + "run " + d.run.number, d.plan, d.dsn, d.ses, d.check));
                        }
                    }
                    try
                    {
                        EeRework with_routes = donors.isEmpty() ? null : EeRework.build(c.board, rules, c.plan, c.dsn, c.ses, c.text, c.bridge, c.check, donors);
                        if (with_routes != null && with_routes.routes_taken > 0)
                        {
                            slot_bases.add(c);
                            slot_reworks.add(with_routes);
                        }
                    }
                    catch (RuntimeException e)
                    {
                        Log.error("job " + id + ": the routes of other runs could not be read: " + e);
                    }
                }
                for (int k = 0; k < slot_bases.size() && bases.size() < run_count; ++k)
                {
                    Candidate c = slot_bases.get(k);
                    EeRework rework = slot_reworks.get(k);
                    String base_name = (c.pass > 1 ? "pass " + c.pass + " " : "") + "run " + c.run.number;
                    bases.add(c);
                    reworks.add(rework);
                    texts.add(rework.text);
                    pass_variants.add(Variant.continued("on the result of " + base_name + (rework.routes_taken > 0 ? ", with routes of other runs" : ""),
                            c.pad_via, c.full_width));
                    Log.info("job " + id + ": pass " + pass + " on the result of " + base_name + " (" + c.check.open_connections + " open): "
                            + rework.via_pads + " pads get a via in the pad, " + rework.net_pads + " pads go back into their net, "
                            + (rework.wires_removed + rework.vias_removed) + " tracks and vias of other nets are taken out and routed again."
                            + (rework.routes_taken > 0 ? " " + rework.nets_taken + " nets in " + rework.routes_taken + " groups get their routes from other runs."
                                    : ""));
                }
                if (bases.isEmpty())
                {
                    if (pass == 2)
                    {
                        no_pass_note = "No further pass ran. No open pad can take a via in the pad, no pad can go back into its net"
                                + (recombine ? ", and no other run has a usable route for an open connection." : ".");
                        Log.info("job " + id + ": " + no_pass_note);
                    }
                    break;
                }
                Path pass_dir = job_dir.resolve("pass-" + pass);
                Files.createDirectories(pass_dir);
                EngineSet next = new EngineSet(config, pass_dir, id);
                engines = next;
                more_sets.add(next);
                if (cancelled)
                {
                    outcome.message = "cancelled";
                    return outcome;
                }
                boolean done = next.run(texts, pass_variants, EngineSetup.args(rules, 1.0), budget_ms, start, heap_mb, p_ticker);
                if (!done || cancelled)
                {
                    outcome.message = "cancelled";
                    return outcome;
                }
                List<Candidate> pass_candidates = new ArrayList<Candidate>();
                for (EngineRun run : next.runs())
                {
                    run.job_pass = pass;
                    all_runs.add(run);
                    int k = run.number - 1;
                    Candidate base = bases.get(k);
                    Candidate c = candidate(base.board, reworks.get(k).plan, reworks.get(k).dsn, run, pass, reworks.get(k), base, base.first_plan);
                    if (c != null)
                    {
                        c.pass_lines.addAll(base.pass_lines);
                        for (String line : reworks.get(k).lines)
                        {
                            c.pass_lines.add("Pass " + pass + ", on the result of " + (base.pass > 1 ? "pass " + base.pass + " " : "") + "run " + base.run.number
                                    + ": " + line);
                        }
                        pass_candidates.add(c);
                    }
                }
                candidates.addAll(pass_candidates);
                best = choose(candidates, avoid_narrow);
                if (!pass_candidates.contains(best))
                {
                    break; // the pass gave no better result
                }
                last = pass_candidates;
            }
            double used = (System.nanoTime() - start) / 1e9;
            if (best == null)
            {
                outcome.message = "The engine gave no result (stage and engine exit code per run: " + set.failure_line() + "). See the engine.log files in "
                        + job_dir + "." + (kept_tracks > 0 ? " The file has " + kept_tracks + " tracks that stay (rules: existing = keep). The engine reads an"
                                + " existing track as a connection only when it ends on the centre of a pad or on a second track. On a board that is routed"
                                + " already, set existing to remove. The bridge then routes the board again in the result file." : "");
                Log.error("job " + id + ": " + outcome.message);
                return outcome;
            }
            outcome.ok = true;
            EeClear clear = tidy(best.board, best.first_plan, best);
            outcome.json = best.text;
            outcome.open_after = best.check.open_connections;
            outcome.report = report(best.board, best.first_plan, before, candidates, best, clear, used, run_count, all_runs);
            Files.write(job_dir.resolve("result.json"), outcome.json.getBytes(StandardCharsets.UTF_8));
            Files.write(job_dir.resolve("report.txt"), outcome.report.getBytes(StandardCharsets.UTF_8));
            Files.write(job_dir.resolve("chosen.txt"),
                    ((best.pass > 1 ? "pass-" + best.pass + "/" : "") + "run-" + best.run.number + " (" + best.run.variant.name + ")\n").getBytes(StandardCharsets.UTF_8));
            int narrow_now = best.tidy != null ? best.tidy.narrow_segments : best.result.narrow_segments;
            Log.info(String.format(Locale.ROOT, "job %d: done in %.0f s. Open connections: %d of %d. New tracks: %d, vias: %d.%s", id, used,
                    best.check.open_connections, before.open_connections,
                    best.tidy != null ? best.tidy.records_after
                            : best.result.track_count + (best.bridge == null ? 0 : best.bridge.tracks_added + best.bridge.surface_tracks_added),
                    best.result.via_count + added_vias(best),
                    narrow_now > 0 && !rules.narrow_tracks.equals("allow") ? " Segments narrower than the track rule: " + narrow_now + "." : ""));
            return outcome;
        }
        catch (IOException | RuntimeException e)
        {
            outcome.ok = false;
            outcome.message = cancelled ? "cancelled" : "The job failed: " + e;
            if (!cancelled)
            {
                Log.error("job " + id + ": " + outcome.message);
            }
            return outcome;
        }
        finally
        {
            if (set != null)
            {
                set.stop_all();
            }
            for (EngineSet other : more_sets)
            {
                other.stop_all();
            }
            RoutingJob.prune_job_folders(config, outcome.job_dir);
        }
    }

    /** The board file of one engine run, with the repair of the copper areas and the count of the connections. null: no result. */
    private Candidate candidate(EeBoard p_board, EePlan p_plan, EeDsn p_dsn, EngineRun p_run, int p_pass, EeRework p_rework)
    {
        return candidate(p_board, p_plan, p_dsn, p_run, p_pass, p_rework, null, p_plan);
    }

    /**
     * @param p_base       the result that this run went on from. null: first pass.
     * @param p_first_plan the plan of p_board without the changes of the further passes
     */
    private Candidate candidate(EeBoard p_board, EePlan p_plan, EeDsn p_dsn, EngineRun p_run, int p_pass, EeRework p_rework, Candidate p_base, EePlan p_first_plan)
    {
        if (!p_run.has_result())
        {
            return null;
        }
        try
        {
            Candidate c = new Candidate();
            c.board = p_board;
            c.first_plan = p_first_plan;
            if (p_base != null)
            {
                c.move_lines.addAll(p_base.move_lines);
                c.moved_refs.addAll(p_base.moved_refs);
            }
            c.run = p_run;
            c.pass = p_pass;
            c.plan = p_plan;
            c.dsn = p_dsn;
            c.rework = p_rework;
            c.ses = new String(Files.readAllBytes(p_run.output), StandardCharsets.UTF_8);
            c.result = new EeResult(p_board, p_plan, p_dsn, c.ses);
            c.text = c.result.text;
            EeBoard routed = new EeBoard(c.text, rules.design_rules_first());
            if (rules.plane_bridges.equals("on") && !p_plan.fill_areas.isEmpty())
            {
                c.bridge = new EeBridge(routed, p_plan);
                if (!c.bridge.text.equals(c.text))
                {
                    c.text = c.bridge.text;
                    routed = new EeBoard(c.text, rules.design_rules_first());
                }
            }
            c.check = new EeCheck(routed, p_plan);
            move_parts(c, p_board, p_plan);
            write_open_list(c, p_run, p_rework);
            c.pad_via = p_run.variant.pad_via;
            c.full_width = p_run.variant.full_width;
            c.vias_in_pads = vias_in_pads(routed);
            c.prefer_pad_vias = rules.via_on_pads.equals("mixed");
            for (double value : c.result.length_by_layer.values())
            {
                c.length += value;
            }
            return c;
        }
        catch (IOException | RuntimeException e)
        {
            Log.error("job " + id + (p_pass > 1 ? " pass " + p_pass : "") + " run " + p_run.number + ": the result could not be put into the board file: " + e);
            return null;
        }
    }

    /**
     * Small moves of parts for pads that the repair could not join to their copper area (rules: parts.move, EePerturb).
     * The moved board is repaired and counted again. It takes the place of the result only when it has fewer open
     * connections. The tracks of the engine do not change.
     */
    private void move_parts(Candidate p_c, EeBoard p_board, EePlan p_plan)
    {
        if (!rules.part_moves.equals("on") || p_c.bridge == null || p_c.check.open_connections == 0)
        {
            return;
        }
        try
        {
            EePerturb step = EePerturb.via_room(p_board, p_plan, rules, p_c.text, p_c.check, p_c.moved_refs);
            if (step == null)
            {
                return;
            }
            p_c.not_moved.addAll(step.not_moved);
            if (step.moves.isEmpty())
            {
                return;
            }
            EeBoard moved_board = new EeBoard(step.apply_to(p_board), rules.design_rules_first());
            EePlan moved_first = new EePlan(moved_board, rules);
            EePlan moved_plan = moved_first;
            if (p_plan.rework != null)
            {
                EePlan.Rework rework = new EePlan.Rework();
                for (EeBoard.Pad pad : p_plan.rework.via_pads)
                {
                    rework.via_pads.add(moved_board.pads.get(pad.index));
                }
                for (EeBoard.Pad pad : p_plan.rework.net_pads)
                {
                    rework.net_pads.add(moved_board.pads.get(pad.index));
                }
                rework.fixed_vias.addAll(p_plan.rework.fixed_vias);
                rework.fixed_via_nets.addAll(p_plan.rework.fixed_via_nets);
                moved_plan = new EePlan(moved_board, rules, rework);
            }
            EeBoard moved_routed = new EeBoard(step.apply_to(new EeBoard(p_c.result.text, rules.design_rules_first())), rules.design_rules_first());
            EeBridge moved_bridge = new EeBridge(moved_routed, moved_plan);
            EeBoard moved_repaired = new EeBoard(moved_bridge.text, rules.design_rules_first());
            EeCheck moved_check = new EeCheck(moved_repaired, moved_plan);
            EeClear before = new EeClear(new EeBoard(p_c.text, rules.design_rules_first()), p_plan);
            EeClear after = new EeClear(moved_repaired, moved_plan);
            boolean clean = after.routed_errors <= before.routed_errors && after.placement_errors <= before.placement_errors
                    && after.edge_errors <= before.edge_errors && after.keepout_errors <= before.keepout_errors;
            String where = (p_c.pass > 1 ? "pass " + p_c.pass + " " : "") + "run " + p_c.run.number;
            if (moved_check.open_connections < p_c.check.open_connections && clean)
            {
                Log.info("job " + id + " " + where + ": " + step.moves.size() + " part(s) moved (rules: parts.move). Open connections " + p_c.check.open_connections
                        + " -> " + moved_check.open_connections + ".");
                for (EePerturb.Move move : step.moves)
                {
                    p_c.moved_refs.add(move.ref);
                }
                for (String line : step.lines)
                {
                    p_c.move_lines.add(line + " Found on the result of " + where + ". Open connections of that result: " + p_c.check.open_connections + " before, "
                            + moved_check.open_connections + " after.");
                }
                p_c.board = moved_board;
                p_c.first_plan = moved_first;
                p_c.plan = moved_plan;
                p_c.dsn = new EeDsn(moved_board, moved_plan, rules);
                p_c.bridge = moved_bridge;
                p_c.text = moved_bridge.text;
                p_c.check = moved_check;
                p_c.not_moved.clear();
            }
            else
            {
                Log.info("job " + id + " " + where + ": a move of " + step.moves.size() + " part(s) was tried and not kept. Open connections "
                        + p_c.check.open_connections + " -> " + moved_check.open_connections + (clean ? "." : ", and a check of the moved board was worse."));
            }
        }
        catch (EeMove.Unsupported | RuntimeException e)
        {
            Log.error("job " + id + ": the search for part moves failed: " + e);
        }
    }

    /**
     * The clean-up of the tracks of the chosen result (rules: track.tidy). The cleaned file is taken only when its checks
     * are not worse than the checks of the file before the clean-up: open connections with the fill model, clearance,
     * board edge and keep-outs. When the file with all changes is worse, the changes of copper are tested for each layer
     * alone, and the layers that are worse keep their copper.
     *
     * @return the clearance check of the file that the job gives back
     */
    private EeClear tidy(EeBoard p_board, EePlan p_plan, Candidate p_best)
    {
        EeBoard routed = new EeBoard(p_best.text, rules.design_rules_first());
        EeClear clear = new EeClear(routed, p_plan);
        if (rules.tidy_tracks.equals("off"))
        {
            return clear;
        }
        try
        {
            Set<String> none = new LinkedHashSet<String>();
            Object[] state = tidy_try(p_board, p_plan, routed, p_best, clear, rules.tidy_tracks.equals("on") ? null : none);
            if (state == null && rules.tidy_tracks.equals("on"))
            {
                Set<String> good = new LinkedHashSet<String>();
                List<String> kept = new ArrayList<String>();
                for (String layer : routed.layers)
                {
                    Set<String> one = new LinkedHashSet<String>();
                    one.add(layer);
                    if (tidy_try(p_board, p_plan, routed, p_best, clear, one) != null)
                    {
                        good.add(layer);
                    }
                    else
                    {
                        kept.add(routed.layer_name(layer));
                    }
                }
                state = good.isEmpty() ? null : tidy_try(p_board, p_plan, routed, p_best, clear, good);
                if (state == null)
                {
                    state = tidy_try(p_board, p_plan, routed, p_best, clear, none);
                    kept.clear();
                    for (String layer : routed.layers)
                    {
                        kept.add(routed.layer_name(layer));
                    }
                }
                p_best.tidy_note = "The clean-up changed no copper on " + String.join(", ", kept)
                        + ". With the change, the checks of the result file were worse than before.";
                Log.info("job " + id + ": " + p_best.tidy_note);
            }
            if (state == null)
            {
                return clear;
            }
            p_best.tidy = (EeTidy) state[0];
            p_best.text = p_best.tidy.text;
            p_best.check = (EeCheck) state[1];
            return (EeClear) state[2];
        }
        catch (RuntimeException e)
        {
            Log.error("job " + id + ": the clean-up of the tracks failed: " + e + ". The result has the tracks as the engine made them.");
            return clear;
        }
    }

    /** @return the clean-up, the check of the connections and the check of the clearance. null: the cleaned file is worse than the file before. */
    private Object[] tidy_try(EeBoard p_board, EePlan p_plan, EeBoard p_routed, Candidate p_best, EeClear p_clear, Set<String> p_copper_layers)
    {
        String fail = System.getProperty("bridge.test.tidy_fail_layer"); // for tests of the fall-back
        if (fail != null && (p_copper_layers == null || p_copper_layers.contains(fail)))
        {
            return null;
        }
        EeTidy tidy = new EeTidy(p_board, p_routed, p_best.plan, p_copper_layers);
        if (tidy.text.equals(p_routed.text))
        {
            return new Object[]
            {
                tidy, p_best.check, p_clear
            };
        }
        EeBoard cleaned = new EeBoard(tidy.text, rules.design_rules_first());
        EeCheck check = new EeCheck(cleaned, p_best.plan);
        EeClear clear = new EeClear(cleaned, p_plan);
        boolean worse = check.open_connections > p_best.check.open_connections || clear.routed_errors > p_clear.routed_errors
                || clear.edge_errors > p_clear.edge_errors || clear.keepout_errors > p_clear.keepout_errors;
        return worse ? null : new Object[]
        {
            tidy, check, clear
        };
    }

    /** Fewest open connections, then fewest clearance violations of the engine, then (rule) narrow segments, then vias, then length. */
    private static Candidate choose(List<Candidate> p_candidates, boolean p_avoid_narrow)
    {
        Candidate best = null;
        for (Candidate c : p_candidates)
        {
            if (best == null || compare(c, best, p_avoid_narrow) < 0)
            {
                best = c;
            }
        }
        return best;
    }

    /** Below 0: p_a is the better result. */
    private static int compare(Candidate p_a, Candidate p_b, boolean p_avoid_narrow)
    {
        int[] a = key(p_a, p_avoid_narrow);
        int[] b = key(p_b, p_avoid_narrow);
        for (int i = 0; i < a.length; ++i)
        {
            if (a[i] != b[i])
            {
                return a[i] < b[i] ? -1 : 1;
            }
        }
        return Double.compare(p_a.length, p_b.length);
    }

    /** Vias that the bridge added after the engine. */
    private static int added_vias(Candidate p_c)
    {
        return p_c.bridge == null ? 0 : p_c.bridge.island_vias_added + p_c.bridge.pad_vias_added + p_c.bridge.track_vias_added;
    }

    private static int[] key(Candidate p_c, boolean p_avoid_narrow)
    {
        return new int[]
        {
            p_c.check.open_connections, Math.max(p_c.run.violations, 0), p_avoid_narrow ? p_c.result.narrow_segments : 0, p_c.moved_refs.size(),
            p_c.prefer_pad_vias ? -p_c.vias_in_pads : 0, p_c.result.via_count + added_vias(p_c)
        };
    }

    /** Writes the open connections of a run into its folder (open.txt), with the changes that the bridge made before the run. */
    private void write_open_list(Candidate p_c, EngineRun p_run, EeRework p_rework)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("open connections: ").append(p_c.check.open_connections).append(System.lineSeparator());
        for (EeCheck.NetState state : p_c.check.nets.values())
        {
            if (state.groups > 1)
            {
                sb.append(state.net).append(": ").append(state.groups - 1).append(" open. Pads not joined to the largest group: ")
                        .append(String.join(", ", state.loose_pads)).append(System.lineSeparator());
            }
        }
        if (p_rework != null)
        {
            sb.append(System.lineSeparator()).append("changes before this run:").append(System.lineSeparator());
            for (String line : p_rework.lines)
            {
                sb.append(line).append(System.lineSeparator());
            }
        }
        try
        {
            Files.write(p_run.dir.resolve("open.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            // the list is for the job folder only
        }
    }

    /** The count of the vias that have their centre in a surface-mount pad of their net. */
    static int vias_in_pads(EeBoard p_routed)
    {
        int count = 0;
        for (EeBoard.Via via : p_routed.vias)
        {
            if (via.net.isEmpty())
            {
                continue;
            }
            for (EeBoard.Pad pad : p_routed.pads)
            {
                if (pad.through || !via.net.equals(pad.net) || Math.abs(pad.cx - via.x) > 6.0 || Math.abs(pad.cy - via.y) > 6.0)
                {
                    continue;
                }
                boolean inside;
                if (pad.polygon != null)
                {
                    inside = false;
                    double[] q = pad.polygon;
                    for (int i = 0, j = q.length - 2; i < q.length; j = i, i += 2)
                    {
                        if ((q[i + 1] > via.y) != (q[j + 1] > via.y) && via.x < (q[j] - q[i]) * (via.y - q[i + 1]) / (q[j + 1] - q[i + 1]) + q[i])
                        {
                            inside = !inside;
                        }
                    }
                }
                else
                {
                    inside = Math.hypot(pad.cx - via.x, pad.cy - via.y) <= pad.radius;
                }
                if (inside)
                {
                    ++count;
                    break;
                }
            }
        }
        return count;
    }

    /** What the bridge does before the engine starts. Also printed by the check command. */
    static List<String> plan_lines(EeBoard p_board, EePlan p_plan)
    {
        Rules rules = p_plan.rules;
        List<String> lines = new ArrayList<String>();
        lines.add(String.format(Locale.ROOT, "rules: track %.3f mm, clearance %.3f mm, via %.3f mm with %.3f mm hole. Source: %s (rules: rules_from).%s",
                p_board.track_mm, p_board.clearance_mm, p_plan.via_diameter_mm, p_plan.via_hole_mm,
                p_board.design_rules_first ? "the design rules of the file, as the Design Rule dialog of the editor shows them"
                        : "the Auto Router values that the editor stored in the file",
                rules.via_diameter_mm > 0 ? " The via size is the value of the rules file (via.diameter_mm, via.hole_mm)." : ""));
        double[] other = p_board.design_rules_first ? p_board.router_rules : p_board.design_rules;
        double[] used =
        {
            p_board.track_mm, p_board.clearance_mm, p_board.via_mm, p_board.via_hole_mm
        };
        boolean different = false;
        for (int i = 0; i < 4; ++i)
        {
            different |= other[i] > 0 && Math.abs(other[i] - used[i]) > 1e-4;
        }
        if (different)
        {
            lines.add(String.format(Locale.ROOT, "%s other values: track %.3f mm, clearance %.3f mm, via %.3f mm with %.3f mm hole. The job does not use them.",
                    p_board.design_rules_first ? "the Auto Router values that the editor stored in the file (menu Route, Auto Router) have"
                            : "the design rules of the file have",
                    other[0], other[1], other[2], other[3]));
        }
        for (Map.Entry<String, List<EeBoard.Region>> entry : p_plan.planes.entrySet())
        {
            List<String> names = new ArrayList<String>();
            for (EeBoard.Region area : entry.getValue())
            {
                String name = p_board.layer_name(area.layer);
                if (!names.contains(name))
                {
                    names.add(name);
                }
            }
            lines.add("copper area as plane: net " + entry.getKey() + " on " + String.join(", ", names)
                    + ". The engine routes nothing for a pad of this net that lies in an area on its layer or has a via into an area.");
        }
        if (EngineSetup.uses_layer_costs(rules, p_board.layers.size()))
        {
            Map<String, String> plane_net = new LinkedHashMap<String, String>();
            for (Map.Entry<String, List<EeBoard.Region>> entry : p_plan.planes.entrySet())
            {
                for (EeBoard.Region area : entry.getValue())
                {
                    if (!p_plan.is_outer(area.layer) && (!plane_net.containsKey(area.layer) || rules.ground_net.matcher(entry.getKey()).matches()))
                    {
                        plane_net.put(area.layer, entry.getKey());
                    }
                }
            }
            StringBuilder costs = new StringBuilder();
            for (int i = 0; i < p_board.layers.size(); ++i)
            {
                String layer = p_board.layers.get(i);
                costs.append(costs.length() > 0 ? ", " : "").append(p_board.layer_name(layer)).append(' ')
                        .append(EngineSetup.cost(rules, p_board.layer_name(layer), i, p_board.layers.size(), plane_net.get(layer)));
            }
            lines.add("track cost factor by layer: " + costs + ".");
        }
        else
        {
            lines.add("track cost by layer: set by the engine.");
        }
        if (!p_plan.pads_without_exit_and_via.isEmpty())
        {
            List<String> names = new ArrayList<String>();
            for (EeBoard.Pad pad : p_plan.pads_without_exit_and_via)
            {
                names.add(pad.ref + "." + pad.number);
            }
            lines.add("pads with no exit where no via fits: " + String.join(", ", names) + ". The engine cannot reach them.");
        }
        if (!p_plan.keepouts.isEmpty() || !p_plan.hole_keepouts.isEmpty())
        {
            lines.add("keep-out: " + p_plan.keepouts.size() + " rectangles and " + p_plan.hole_keepouts.size() + " holes.");
        }
        if (!p_plan.skipped_nets.isEmpty())
        {
            lines.add("nets not routed (skip): " + String.join(", ", p_plan.skipped_nets) + ".");
        }
        int kept_tracks = 0, kept_vias = 0;
        for (EeBoard.Track track : p_board.tracks)
        {
            if (p_plan.nets.containsKey(track.net) && (track.locked || rules.existing.equals("keep")))
            {
                ++kept_tracks;
            }
        }
        for (EeBoard.Via via : p_board.vias)
        {
            if (p_plan.nets.containsKey(via.net) && (via.locked || rules.existing.equals("keep")))
            {
                ++kept_vias;
            }
        }
        lines.add("existing copper of the routed nets: " + p_board.tracks.size() + " tracks and " + p_board.vias.size() + " vias in the file. "
                + (rules.existing.equals("keep") ? "All stay and are fixed." : kept_tracks + " locked tracks and " + kept_vias
                        + " locked vias stay. The others are removed."));
        lines.addAll(p_board.notes);
        lines.addAll(p_plan.notes);
        return lines;
    }

    private String report(EeBoard p_board, EePlan p_plan, EeCheck p_before, List<Candidate> p_candidates, Candidate p_best, EeClear p_clear,
            double p_seconds, int p_run_count, List<EngineRun> p_runs)
    {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        sb.append("EasyEDA to Freerouting bridge ").append(Main.VERSION).append(". Report of file job ").append(id).append(nl);
        sb.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(nl).append(nl);

        sb.append("BOARD").append(nl);
        List<String> layer_names = new ArrayList<String>();
        for (String layer : p_board.layers)
        {
            layer_names.add(p_board.layer_name(layer));
        }
        sb.append("  Parts: ").append(p_board.part_count).append(". Pads: ").append(p_board.pads.size()).append(". Copper layers: ")
                .append(String.join(", ", layer_names)).append(".").append(nl);
        sb.append("  Nets to route: ").append(p_plan.nets.size()).append(". Open connections before routing: ").append(p_before.open_connections)
                .append(".").append(nl).append(nl);

        sb.append("PLAN").append(nl);
        for (String line : plan_lines(p_board, p_plan))
        {
            sb.append("  ").append(Character.toUpperCase(line.charAt(0))).append(line.substring(1)).append(nl);
        }
        sb.append(nl);

        sb.append("ENGINE RUNS (").append(p_run_count).append(" at the same time, ").append(Math.round(p_seconds)).append(" s)").append(nl);
        for (Candidate c : p_candidates)
        {
            sb.append(String.format(Locale.ROOT, "  %sRun %d (%s): open connections %d, vias %d (%d in pads), track length %.0f mm, narrow segments %d%s.%s",
                    c.pass > 1 ? "Pass " + c.pass + ". " : "", c.run.number, c.run.variant.name, c.check.open_connections,
                    c.result.via_count + added_vias(c), c.vias_in_pads, c.length, c.result.narrow_segments,
                    c.run.violations > 0 ? ", clearance violations " + c.run.violations : "", c == p_best ? " Chosen." : "")).append(nl);
        }
        if (p_best.prefer_pad_vias && p_run_count > 1)
        {
            sb.append("  Mixed job (rules: via.on_pads = mixed): each second run could put the small via into any pad. The result with the fewest open")
                    .append(" connections is chosen. At an equal count, the result with more vias in pads is chosen.").append(nl);
        }
        boolean has_full = false;
        for (Candidate c : p_candidates)
        {
            has_full |= c.full_width;
        }
        if (has_full && rules.narrow_tracks.equals("mixed"))
        {
            sb.append("  Mixed job (rules: track.narrow = mixed): one run of four got no narrow tracks from the engine. At an equal count of open connections,"
                    + " the result with fewer narrow segments is chosen.").append(nl);
        }
        for (EngineRun run : p_runs)
        {
            boolean listed = false;
            for (Candidate c : p_candidates)
            {
                listed |= c.run == run;
            }
            if (!listed)
            {
                sb.append("  ").append(run.job_pass > 1 ? "Pass " + run.job_pass + ". " : "").append("Run ").append(run.number).append(" (").append(run.variant.name)
                        .append("): no result. ")
                        .append(run.was_stopped() ? "It still worked at the end of the job time and was stopped." : "The engine ended with code "
                                + run.exit_code() + ".").append(nl);
            }
        }
        sb.append(nl);

        if (rules.part_moves.equals("on"))
        {
            sb.append("PART MOVES (rules: parts.move)").append(nl);
            if (p_best.move_lines.isEmpty())
            {
                sb.append("  No part was moved. All parts are where the input file has them.").append(nl);
            }
            else
            {
                sb.append("  The result file has ").append(p_best.moved_refs.size()).append(" part(s) in a new position. Check each one before you use the file.").append(nl);
                for (String line : p_best.move_lines)
                {
                    sb.append("  ").append(line).append(nl);
                }
            }
            for (String line : p_best.not_moved)
            {
                sb.append("  Open pad without a move: ").append(line).append(nl);
            }
            sb.append(nl);
        }
        if (!p_best.pass_lines.isEmpty() || no_pass_note != null)
        {
            sb.append("FURTHER PASSES (for pads that tracks cut off from the copper area of their net)").append(nl);
            for (String line : p_best.pass_lines)
            {
                sb.append("  ").append(line).append(nl);
            }
            if (no_pass_note != null)
            {
                sb.append("  ").append(no_pass_note).append(nl);
            }
            sb.append(nl);
        }

        EeResult r = p_best.result;
        sb.append("RESULT").append(nl);
        sb.append("  Open connections: ").append(p_best.check.open_connections).append(" in ").append(p_best.check.open_nets).append(" nets.");
        if (p_best.run.open_connections >= 0 && p_best.run.open_connections != p_best.check.open_connections)
        {
            sb.append(" The engine counts ").append(p_best.run.open_connections)
                    .append(". The two counts use different rules for nets with a plane. The count of the bridge is the one of this report.");
        }
        sb.append(nl);
        int bridge_tracks = p_best.bridge == null ? 0 : p_best.bridge.tracks_added + p_best.bridge.surface_tracks_added;
        EeTidy tidy = p_best.tidy;
        if (tidy != null && tidy.records_after != tidy.records_before)
        {
            sb.append("  Tracks: ").append(tidy.records_after).append(" records. The engine and the bridge made ").append(tidy.records_before)
                    .append(" pieces").append(bridge_tracks > 0 ? " (" + bridge_tracks + " of them added after the engine, see COPPER AREAS)" : "")
                    .append(". Pieces that join end to end are one record now (rules: track.tidy)");
        }
        else
        {
            sb.append("  Tracks: ").append(r.track_count + bridge_tracks)
                    .append(bridge_tracks > 0 ? " (" + bridge_tracks + " of them added after the engine, see COPPER AREAS)" : "");
        }
        sb.append(". Length by layer in mm, engine tracks:");
        for (Map.Entry<String, Double> entry : r.length_by_layer.entrySet())
        {
            sb.append(String.format(Locale.ROOT, " %s %.1f", entry.getKey(), entry.getValue()));
        }
        sb.append(".").append(nl);
        int bridge_vias = added_vias(p_best);
        sb.append("  Vias: ").append(r.via_count + bridge_vias).append(", ").append(p_best.vias_in_pads).append(" of them in a pad of their net.");
        if (bridge_vias > 0)
        {
            sb.append(" Added after the engine: ").append(p_best.bridge.island_vias_added).append(" in islands of copper areas, ")
                    .append(p_best.bridge.track_vias_added).append(" at the end of a short track from a pad, ").append(p_best.bridge.pad_vias_added)
                    .append(" in pads. From the engine:");
        }
        for (Map.Entry<String, Integer> entry : r.vias_by_size.entrySet())
        {
            sb.append(" ").append(entry.getValue()).append(" of ").append(entry.getKey()).append(".");
        }
        sb.append(nl);
        if (p_best.run.violations > 0)
        {
            sb.append("  Clearance violations that the engine reports: ").append(p_best.run.violations);
            if (p_best.run.pre_existing_violations >= 0)
            {
                sb.append(" (").append(p_best.run.pre_existing_violations).append(" were in the board before routing)");
            }
            sb.append(".").append(nl);
        }
        if (r.narrow_from_engine > 0)
        {
            sb.append("  The engine made ").append(r.narrow_from_engine).append(" track segments narrower than the track rule. ")
                    .append(r.narrow_restored).append(" of them got the full width back, because the clearance permits it (rules: track.narrow).")
                    .append(nl);
        }
        if (tidy != null && tidy.copper_changed())
        {
            sb.append(String.format(Locale.ROOT,
                    "  Track clean-up (rules: track.tidy): %d narrow segments got the full width, %d in their position and %d after a shift to the side"
                            + " of %.3f mm or less. %d small steps and %d side steps went away. New copper keeps the clearance rule plus %.4f mm."
                            + " A segment with the full width keeps the clearance rule plus %.4f mm.",
                    tidy.widened_in_place + tidy.widened_after_shift, tidy.widened_in_place, tidy.widened_after_shift, tidy.largest_shift_mm,
                    tidy.steps_removed, tidy.side_steps_removed, EeTidy.MARGIN_MM, EeTidy.WIDEN_MARGIN_MM)).append(nl);
        }
        if (p_best.tidy_note != null)
        {
            sb.append("  ").append(p_best.tidy_note).append(nl);
        }
        int narrow_segments = tidy != null ? tidy.narrow_segments : r.narrow_segments;
        if (narrow_segments > 0)
        {
            sb.append(String.format(Locale.ROOT,
                    "  Segments narrower than the track rule in the result: %d. Smallest width %.3f mm. Total length %.1f mm. The full width does not fit there.",
                    narrow_segments, tidy != null ? tidy.narrow_min_mm : r.narrow_min_mm, tidy != null ? tidy.narrow_length_mm : r.narrow_length_mm))
                    .append(nl);
            if (tidy != null)
            {
                for (String line : tidy.narrow_lines)
                {
                    sb.append("    ").append(line).append(nl);
                }
                if (!tidy.narrow_lines.isEmpty())
                {
                    sb.append("    Positions are in mm from the origin of the file. With track.narrow = avoid the engine makes no narrow segment.").append(nl);
                }
            }
        }
        else
        {
            sb.append("  Segments narrower than the track rule in the result: 0.").append(nl);
        }
        sb.append("  Records removed from the file (old tracks and vias of the routed nets): ").append(r.removed_records).append(".").append(nl);
        sb.append(nl);

        if (p_best.check.open_connections > 0)
        {
            List<String> no_exit = new ArrayList<String>();
            for (EeBoard.Pad pad : p_plan.pads_without_exit_and_via)
            {
                no_exit.add(pad.ref + "." + pad.number);
            }
            sb.append("OPEN CONNECTIONS BY NET").append(nl);
            for (EeCheck.NetState state : p_best.check.nets.values())
            {
                if (state.groups <= 1)
                {
                    continue;
                }
                List<String> pads = new ArrayList<String>();
                for (String pad : state.loose_pads)
                {
                    pads.add(no_exit.contains(pad) ? pad + " (no exit, no via fits)" : pad);
                }
                sb.append("  ").append(state.net).append(": ").append(state.groups - 1).append(" open. Pads not joined to the largest group: ")
                        .append(String.join(", ", pads)).append(".").append(nl);
            }
            sb.append(nl);
        }

        if (!p_best.check.planes.isEmpty())
        {
            sb.append("COPPER AREAS (model of the fill: outline minus the clearance of the area to other nets, parts narrower than 0.19 mm removed)").append(nl);
            if (p_best.bridge != null)
            {
                for (String line : p_best.bridge.lines)
                {
                    sb.append("  ").append(line).append(nl);
                }
            }
            for (EeCheck.PlaneState ps : p_best.check.planes)
            {
                EeCheck.NetState state = p_best.check.nets.get(ps.net);
                sb.append("  ").append(ps.net).append(" on ").append(p_board.layer_name(ps.layer)).append(" in the result file: ").append(ps.islands)
                        .append(" pieces of fill, ").append(ps.islands_with_copper).append(" of them reach copper of the net. Open connections of the net: ")
                        .append(state == null ? 0 : Math.max(0, state.groups - 1)).append(".").append(nl);
            }
            sb.append("  The fill of the editor can differ from the model at narrow positions. After the rebuild, look for connection lines that stay visible.").append(nl).append(nl);
        }

        if (!r.widen_lines.isEmpty())
        {
            sb.append("WIDER TRACKS (rules: nets, widen_to_mm)").append(nl);
            for (String line : r.widen_lines)
            {
                sb.append("  ").append(line).append(nl);
            }
            sb.append("  A wider track can divide a copper area. Examine the areas after the rebuild.").append(nl).append(nl);
        }

        sb.append("CHECKS OF THE RESULT FILE (made by the bridge on the written file, without the engine)").append(nl);
        sb.append(String.format(Locale.ROOT, "  Clearance between copper of different nets, tolerance %.3f mm: %d errors with a track or a via.",
                EeClear.TOLERANCE_MM, p_clear.routed_errors)).append(nl);
        if (p_clear.placement_errors > 0)
        {
            sb.append("  Pads of different parts closer than the clearance rule: ").append(p_clear.placement_errors)
                    .append(". These come from the placement. Routing does not change them.").append(nl);
        }
        sb.append(String.format(Locale.ROOT, "  Tracks and vias nearer than %.2f mm to the board edge: %d.", rules.copper_to_edge_mm, p_clear.edge_errors)).append(nl);
        sb.append("  Tracks and vias inside a keep-out: ").append(p_clear.keepout_errors).append(".").append(nl);
        sb.append(String.format(Locale.ROOT, "  Smallest track width: %.3f mm. Rule of the file: %.3f mm.",
                p_clear.min_track_mm == Double.MAX_VALUE ? 0.0 : p_clear.min_track_mm, p_board.track_mm)).append(nl);
        sb.append("  Via sizes: ").append(String.join(", ", p_clear.via_sizes)).append(".").append(nl);
        sb.append("  Ids that occur two times: ").append(p_clear.duplicate_ids).append(".").append(nl);
        sb.append("  Copper areas: the connections are counted with a model of the fill of each area of a routed net. See COPPER AREAS.").append(nl);
        for (String example : p_clear.examples)
        {
            sb.append("  Error: ").append(example).append(nl);
        }
        sb.append(nl);

        sb.append("NEXT STEPS IN THE EDITOR").append(nl);
        sb.append("  1. Open the result file as a new PCB.").append(nl);
        sb.append("  2. Rebuild the copper areas (Shift+B). The bridge emptied their stored fill.").append(nl);
        sb.append("  3. Run the design rule check.").append(nl);
        if (p_best.check.open_connections > 0)
        {
            sb.append("  4. Route the open connections by hand.").append(nl);
        }
        return sb.toString();
    }
}
