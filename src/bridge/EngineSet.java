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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The engine runs of one job. It starts them, follows them and stops them. The editor channel and the
 * file interface both use it.
 */
final class EngineSet
{
    /** Called at each heartbeat interval while the engines work. */
    interface Ticker
    {
        /** @param p_least_open the smallest count of open connections that a run reports, -1: no count yet */
        void tick(int p_least_open) throws IOException;
    }

    private static final long STATUS_INTERVAL_MS = 20000L;

    private final Config config;
    private final Path job_dir;
    private final long id;
    private final List<EngineRun> runs = new ArrayList<EngineRun>();
    private volatile boolean cancelled = false;

    EngineSet(Config p_config, Path p_job_dir, long p_id)
    {
        this.config = p_config;
        this.job_dir = p_job_dir;
        this.id = p_id;
    }

    List<EngineRun> runs()
    {
        synchronized (runs)
        {
            return new ArrayList<EngineRun>(runs);
        }
    }

    boolean cancelled()
    {
        return cancelled;
    }

    /** Stops the engines. The call of run() returns false. */
    void cancel()
    {
        cancelled = true;
        stop_all();
    }

    void stop_all()
    {
        for (EngineRun run : runs())
        {
            run.stop();
        }
    }

    /**
     * Starts one engine for each variant and waits until all have ended or the time is used up.
     *
     * @param p_dsn       the board text
     * @param p_rule_args engine arguments from the rules
     * @param p_budget_ms time of the whole job
     * @param p_start_ns  System.nanoTime() at the start of the job
     * @return false: cancelled
     */
    boolean run(String p_dsn, List<Variant> p_variants, List<String> p_rule_args, long p_budget_ms, long p_start_ns, int p_heap_mb,
            Ticker p_ticker) throws IOException, InterruptedException
    {
        List<String> texts = new ArrayList<String>();
        for (int i = 0; i < p_variants.size(); ++i)
        {
            texts.add(p_dsn);
        }
        return run(texts, p_variants, p_rule_args, p_budget_ms, p_start_ns, p_heap_mb, p_ticker);
    }

    /** As above, with a board text for each run. */
    boolean run(List<String> p_texts, List<Variant> p_variants, List<String> p_rule_args, long p_budget_ms, long p_start_ns, int p_heap_mb,
            Ticker p_ticker) throws IOException, InterruptedException
    {
        int run_count = p_variants.size();
        long used_ms = (System.nanoTime() - p_start_ns) / 1000000L;
        long engine_ms = Math.max(30000L, p_budget_ms - config.finish_margin_ms - used_ms);
        synchronized (runs)
        {
            for (int i = 0; i < run_count; ++i)
            {
                runs.add(new EngineRun(i + 1, p_variants.get(i), job_dir, id, run_count == 1));
            }
        }
        for (EngineRun run : runs())
        {
            if (cancelled)
            {
                return false;
            }
            run.start(config, p_texts.get(run.number - 1), p_rule_args, engine_ms, p_heap_mb, run_count > 1);
        }

        long budget_end = p_start_ns + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, p_budget_ms - 2000L));
        long hard_deadline = p_start_ns + TimeUnit.MILLISECONDS.toNanos(p_budget_ms + 17000L);
        long next_heartbeat = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.keep_heartbeat_ms);
        long next_status = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STATUS_INTERVAL_MS);
        double first_complete_seconds = -1.0;
        // a run was complete while a run without narrow tracks still worked
        double waiting_complete_seconds = -1.0;
        boolean waiting_for_better = false;
        for (;;)
        {
            Thread.sleep(250);
            if (cancelled)
            {
                return false;
            }
            long now = System.nanoTime();
            int alive = 0;
            for (EngineRun run : runs())
            {
                if (run.alive())
                {
                    ++alive;
                }
                else if (run.note_end())
                {
                    run.join_reader(3000);
                    if (run_count > 1)
                    {
                        Log.info("job " + id + " run " + run.number + " (" + run.variant.name + "): ended after " + Math.round(run.seconds()) + " s"
                                + (run.has_result() ? ", open connections: " + text_of(run.open_connections) + "."
                                        : " with no result (engine exit code " + run.exit_code() + ")."));
                    }
                    // A run without narrow tracks needs more time. While one works, only a complete run of that kind starts the early stop.
                    if (first_complete_seconds < 0 && run.has_result() && run.open_connections == 0)
                    {
                        if (run.variant.full_width || !full_width_alive())
                        {
                            first_complete_seconds = (now - p_start_ns) / 1e9;
                        }
                        else if (waiting_complete_seconds < 0)
                        {
                            waiting_complete_seconds = (now - p_start_ns) / 1e9;
                        }
                    }
                }
            }
            if (alive == 0)
            {
                break;
            }
            if (first_complete_seconds < 0 && waiting_complete_seconds >= 0 && !full_width_alive())
            {
                first_complete_seconds = waiting_complete_seconds;
            }
            if (now >= budget_end && has_any_result())
            {
                // The engines stop by themselves before this point. A run that still works is late.
                EngineRun better = better_run_alive();
                if (better == null)
                {
                    Log.info("job " + id + ": the time is used up. Stopping " + alive + " late run(s) and taking the best finished result.");
                    stop_all();
                    break;
                }
                if (!waiting_for_better)
                {
                    waiting_for_better = true;
                    Log.info("job " + id + ": the time is used up. Run " + better.number + " has a pass with " + better.best_open
                            + " open connections, fewer than every finished run. Waiting up to 15 s for it.");
                }
            }
            if (now >= hard_deadline)
            {
                Log.error("job " + id + ": " + alive + " engine run(s) did not stop in time. Stopping.");
                stop_all();
                break;
            }
            if (first_complete_seconds >= 0 && config.early_stop_factor > 0.0
                    && (now - p_start_ns) / 1e9 >= first_complete_seconds * config.early_stop_factor + 5.0)
            {
                Log.info("job " + id + ": one run connected everything after " + Math.round(first_complete_seconds) + " s. Stopping the " + alive
                        + " run(s) that still work.");
                stop_all();
                break;
            }
            if (now >= next_heartbeat)
            {
                next_heartbeat = now + TimeUnit.MILLISECONDS.toNanos(config.keep_heartbeat_ms);
                if (p_ticker != null)
                {
                    p_ticker.tick(least_open());
                }
            }
            if (run_count > 1 && now >= next_status)
            {
                next_status = now + TimeUnit.MILLISECONDS.toNanos(STATUS_INTERVAL_MS);
                Log.info("job " + id + ": " + Math.round((now - p_start_ns) / 1e9) + " s. " + status_line());
            }
        }
        for (EngineRun run : runs())
        {
            run.join_reader(3000);
        }
        return !cancelled;
    }

    /** true: a run that makes no narrow tracks (rules: track.narrow = mixed) still works. */
    private boolean full_width_alive()
    {
        for (EngineRun run : runs())
        {
            if (run.variant.full_width && run.alive())
            {
                return true;
            }
        }
        return false;
    }

    private boolean has_any_result()
    {
        for (EngineRun run : runs())
        {
            if (run.has_result())
            {
                return true;
            }
        }
        return false;
    }

    /** A run that still works and reports fewer open connections than every finished run, or null. */
    private EngineRun better_run_alive()
    {
        int best_finished = Integer.MAX_VALUE;
        for (EngineRun run : runs())
        {
            if (run.has_result() && run.open_connections >= 0)
            {
                best_finished = Math.min(best_finished, run.open_connections);
            }
        }
        // A run that still works will give back its best pass, not its last one.
        EngineRun result = null;
        for (EngineRun run : runs())
        {
            int open = run.best_open;
            if (run.alive() && open >= 0 && open < best_finished && (result == null || open < result.best_open))
            {
                result = run;
            }
        }
        return result;
    }

    int least_open()
    {
        int result = -1;
        for (EngineRun run : runs())
        {
            int open = run.open_connections;
            if (open >= 0 && (result < 0 || open < result))
            {
                result = open;
            }
        }
        return result;
    }

    String status_line()
    {
        StringBuilder sb = new StringBuilder();
        for (EngineRun run : runs())
        {
            if (sb.length() > 0)
            {
                sb.append(" | ");
            }
            sb.append("run ").append(run.number).append(": ").append(run.status());
        }
        return sb.toString();
    }

    /** Stage and exit code of each run, for a failure message. */
    String failure_line()
    {
        String stages = "";
        for (EngineRun run : runs())
        {
            stages += (stages.isEmpty() ? "" : ", ") + run.stage + "/exit " + run.exit_code();
        }
        return stages;
    }

    /** true: every run failed to read the board. */
    boolean all_read_errors()
    {
        List<EngineRun> list = runs();
        boolean result = !list.isEmpty();
        for (EngineRun run : list)
        {
            result &= run.read_error;
        }
        return result;
    }

    static String text_of(int p_count)
    {
        return p_count < 0 ? "unknown" : String.valueOf(p_count);
    }
}
