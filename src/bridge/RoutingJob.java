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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One routing request of the editor: repair the DSN text, run the Freerouting engine one or
 * several times as separate processes, convert the best session file and send the result.
 *
 * Messages to the editor are the ones of the original EasyEDA local router:
 *   {"a":"heartbeat"}
 *   {"a": "routingResult", "inCompleteNetNum": n, "complete": 1, "data": {...}}
 * complete is 1 for a result, -1 when routing failed, -2 when the board could not be read.
 */
final class RoutingJob implements Runnable
{
    /** EasyEDA writes its 10 mil canvas unit into the DSN text. */
    private static final double MM_PER_UNIT = 0.254;

    private final Config config;
    private final WsConnection connection;
    private final String dsn;
    private final int timeout_seconds;
    private final long id;

    private volatile boolean cancelled = false;
    private volatile boolean finished = false;
    /** true: the result is on its way to the editor. A close of the connection after this point is not a cancel. */
    private volatile boolean result_sent = false;
    private volatile EngineSet engines;

    RoutingJob(Config p_config, WsConnection p_connection, String p_dsn, int p_timeout_seconds, long p_id)
    {
        this.config = p_config;
        this.connection = p_connection;
        this.dsn = p_dsn;
        this.timeout_seconds = p_timeout_seconds;
        this.id = p_id;
    }

    /** Stops the engines. Called when the editor disconnects or sends a new request. */
    void cancel()
    {
        if (finished || result_sent)
        {
            return;
        }
        cancelled = true;
        EngineSet set = engines;
        if (set != null)
        {
            set.cancel();
        }
    }

    /** One finished run with its converted result. */
    private static final class Candidate
    {
        EngineRun run;
        SesConverter.Result result;
        int open;
        int violations;
        NarrowCount narrow;
    }

    @Override
    public void run()
    {
        long start = System.nanoTime();
        Path job_dir = null;
        EngineSet set = null;
        try
        {
            send_raw("{\"a\":\"heartbeat\"}\n");

            long budget_ms = Math.max(timeout_seconds * 1000L, config.min_timeout_ms);

            job_dir = config.home.resolve("jobs")
                    .resolve("job-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + id);
            Files.createDirectories(job_dir);
            Files.write(job_dir.resolve("original.dsn"), dsn.getBytes(StandardCharsets.UTF_8));

            DsnRepair.Result repaired = DsnRepair.repair(dsn, config.repair);
            Rules rules = Rules.load(config.home.resolve("config").resolve("rules.json"));
            ChannelSetup setup = new ChannelSetup(repaired.text, rules, repaired.renamed_nets);

            StringBuilder memory_note = new StringBuilder();
            int run_count = config.runs_for_job(memory_note);
            int heap_mb = config.heap_mb_for(run_count);

            Log.info("job " + id + ": board with " + count(repaired.text, "(pin ") + " pads, "
                    + count(repaired.text, "(net ") + " nets to route, "
                    + count_layers(repaired.text) + " layers. Budget " + budget_ms / 1000 + " s. "
                    + (run_count == 1 ? "One engine run." : run_count + " engine runs at the same time."));
            if (memory_note.length() > 0)
            {
                Log.info("job " + id + ": " + memory_note);
            }
            for (String note : repaired.notes)
            {
                Log.info("job " + id + ": repair: " + note);
            }
            for (String warning : rules.warnings)
            {
                Log.error("job " + id + ": " + warning);
            }
            for (String note : setup.notes)
            {
                Log.info("job " + id + ": rules: " + note);
            }

            set = new EngineSet(config, job_dir, id);
            engines = set;
            if (cancelled)
            {
                return;
            }
            List<Variant> variants = Variant.built_in(run_count, !rules.narrow_tracks.equals("avoid"), true);
            boolean completed = set.run(setup.text, variants, setup.args, budget_ms, start, heap_mb, open ->
            {
                if (config.send_progress && open >= 0)
                {
                    send_raw("{\"a\": \"routingProgress\", \"inCompleteNetNum\": " + open + "}");
                }
                else
                {
                    send_raw("{\"a\":\"heartbeat\"}\n");
                }
            });
            if (!completed || cancelled)
            {
                return;
            }

            // Convert every result and take the best.
            boolean count_narrow = !rules.narrow_tracks.equals("allow");
            List<Candidate> candidates = new ArrayList<Candidate>();
            for (EngineRun run : set.runs())
            {
                if (!run.has_result())
                {
                    continue;
                }
                try
                {
                    String ses = new String(Files.readAllBytes(run.output), StandardCharsets.UTF_8);
                    Candidate candidate = new Candidate();
                    candidate.run = run;
                    candidate.result = SesConverter.convert(ses, repaired.renamed_nets);
                    candidate.open = run.open_connections;
                    candidate.violations = run.violations;
                    candidate.narrow = count_narrow ? new NarrowCount(setup.text, ses) : null;
                    candidates.add(candidate);
                }
                catch (IOException | RuntimeException e)
                {
                    Log.error("job " + id + " run " + run.number + ": the session file could not be read: " + e);
                }
            }

            double seconds = (System.nanoTime() - start) / 1e9;
            Candidate best = choose(candidates, rules.narrow_tracks.equals("avoid"));
            if (best != null)
            {
                if (run_count > 1)
                {
                    for (Candidate c : candidates)
                    {
                        Log.info(String.format("job %d run %d (%s): open %s, vias %d, track length %.0f mm.%s", id, c.run.number,
                                c.run.variant.name, EngineSet.text_of(c.open), c.result.via_count, c.result.wire_length * MM_PER_UNIT,
                                c == best ? " Chosen." : ""));
                    }
                }
                int open = Math.max(best.open, 0);
                result_sent = true;
                send_raw("{\"a\": \"routingResult\", \"inCompleteNetNum\": " + open + ", \"complete\": 1, \"data\": "
                        + best.result.json + "}");
                Files.write(job_dir.resolve("chosen.txt"), ("run-" + best.run.number + " (" + best.run.variant.name + ")\n")
                        .getBytes(StandardCharsets.UTF_8));
                Log.info(String.format("job %d: done in %.0f s. Open connections: %s. Nets with copper: %d, wires: %d, vias: %d.%s%s",
                        id, seconds, EngineSet.text_of(best.open), best.result.net_count, best.result.wire_count, best.result.via_count,
                        best.violations > 0 ? " Clearance violations in the result: " + best.violations
                                + (best.run.pre_existing_violations >= 0 ? " (" + best.run.pre_existing_violations
                                        + " are in the board as sent)." : ".") : "",
                        best.open < 0 ? " The engine log gave no count of open connections. Check the ratlines in the editor." : ""));
                if (best.narrow != null && best.narrow.segments > 0)
                {
                    Log.info(String.format("job %d: %d track segments are narrower than the width rule of their net (smallest %.3f mm, total length %.1f mm)."
                            + " The engine narrows a track where the full width does not fit. The design rule check of the editor shows them."
                            + " See track.narrow in config/rules.json.", id, best.narrow.segments, best.narrow.min_width * MM_PER_UNIT,
                            best.narrow.length * MM_PER_UNIT));
                }
                if (!setup.plane_nets.isEmpty())
                {
                    Log.info("job " + id + ": nets with a plane in the rules are connected by vias into the plane layer. Rebuild the copper areas"
                            + " in the editor (Shift+B) and check the ratlines.");
                }
            }
            else
            {
                int code = set.all_read_errors() ? -2 : -1;
                send_raw("{\"a\": \"routingResult\", \"inCompleteNetNum\": -1, \"complete\": " + code + ", \"data\": {}}");
                Log.error("job " + id + ": no result after " + Math.round(seconds) + " s (stage and engine exit code per run: "
                        + set.failure_line() + "). See the engine.log files in " + job_dir);
            }
        }
        catch (IOException e)
        {
            if (!cancelled)
            {
                Log.error("job " + id + ": " + e);
                send_failure();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch (RuntimeException e)
        {
            Log.error("job " + id + ": " + e);
            send_failure();
        }
        finally
        {
            boolean was_cancelled = cancelled;
            finished = true;
            if (set != null)
            {
                set.stop_all();
            }
            if (was_cancelled)
            {
                Log.info("job " + id + ": cancelled.");
            }
            prune_job_folders(config, job_dir);
        }
    }

    /**
     * The best result: fewest defects first (open connections plus clearance violations that the
     * other runs do not have), then fewest vias, then shortest track length.
     */
    private static Candidate choose(List<Candidate> p_candidates, boolean p_avoid_narrow)
    {
        if (p_candidates.isEmpty())
        {
            return null;
        }
        int least_violations = Integer.MAX_VALUE;
        for (Candidate c : p_candidates)
        {
            if (c.violations >= 0)
            {
                least_violations = Math.min(least_violations, c.violations);
            }
        }
        Candidate best = null;
        long best_defects = Long.MAX_VALUE;
        for (Candidate c : p_candidates)
        {
            // A run without a count in its log ranks behind every run with a count.
            long defects = c.open < 0 ? Integer.MAX_VALUE
                    : (long) c.open + (c.violations > least_violations ? c.violations - least_violations : 0);
            boolean better;
            if (best == null || defects != best_defects)
            {
                better = best == null || defects < best_defects;
            }
            else if (p_avoid_narrow && c.narrow != null && best.narrow != null && c.narrow.segments != best.narrow.segments)
            {
                better = c.narrow.segments < best.narrow.segments;
            }
            else if (c.result.via_count != best.result.via_count)
            {
                better = c.result.via_count < best.result.via_count;
            }
            else
            {
                better = c.result.wire_length < best.result.wire_length;
            }
            if (better)
            {
                best = c;
                best_defects = defects;
            }
        }
        return best;
    }

    private void send_failure()
    {
        try
        {
            send_raw("{\"a\": \"routingResult\", \"inCompleteNetNum\": -1, \"complete\": -1, \"data\": {}}");
        }
        catch (IOException e)
        {
            // the editor is gone
        }
    }

    private void send_raw(String p_text) throws IOException
    {
        connection.send_text(p_text);
    }

    /** Removes the oldest job folders, so that config.keep_jobs stay. */
    static void prune_job_folders(Config p_config, Path p_current)
    {
        try
        {
            Path jobs = p_config.home.resolve("jobs");
            if (!Files.isDirectory(jobs))
            {
                return;
            }
            List<Path> folders;
            try (Stream<Path> stream = Files.list(jobs))
            {
                folders = stream.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("job-"))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                        .collect(Collectors.toList());
            }
            for (int i = p_config.keep_jobs; i < folders.size(); ++i)
            {
                delete_tree(folders.get(i));
            }
        }
        catch (IOException e)
        {
            // housekeeping only
        }
    }

    private static void delete_tree(Path p_root) throws IOException
    {
        try (Stream<Path> walk = Files.walk(p_root))
        {
            for (Path p : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
            {
                Files.deleteIfExists(p);
            }
        }
    }

    private static int count(String p_text, String p_token)
    {
        int result = 0;
        int index = 0;
        while ((index = p_text.indexOf(p_token, index)) >= 0)
        {
            ++result;
            index += p_token.length();
        }
        return result;
    }

    private static int count_layers(String p_text)
    {
        int end = p_text.indexOf("(placement");
        return count(end < 0 ? p_text : p_text.substring(0, end), "(type signal)");
    }
}
