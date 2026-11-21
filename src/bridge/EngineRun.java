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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One run of the Freerouting engine as a separate process: its folder, its command, its log
 * and the progress that the log shows.
 */
final class EngineRun
{
    // The engine writes "1 violation" and "2 violations".
    private static final Pattern UNROUTED = Pattern.compile("\\((\\d+) unrouted and (\\d+) violations?\\)");
    private static final Pattern OPTIMIZER_STATE = Pattern.compile("incomplete connections: (\\d+), clearance violations: (\\d+)");
    private static final Pattern ROUTER_PASS = Pattern.compile("Auto-routing pass #(\\d+)");
    private static final Pattern OPTIMIZER_PASS = Pattern.compile("Optimizer pass #(\\d+): .*\\((\\w+),");
    private static final Pattern FANOUT_DONE = Pattern.compile("Fanout stage completed.*escaped pins: (\\d+)/(\\d+)");
    private static final Pattern PRE_EXISTING = Pattern.compile("Board has (\\d+) pre-existing clearance violation");
    private static final Pattern LOG_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} [\\d:.]+ +(\\w+) +(\\[[^\\]]*\\] )?");

    final int number;
    /** 1: first pass of the job. 2 and more: a pass on the result of an earlier pass. */
    int job_pass = 1;
    final Variant variant;
    final Path dir;
    final Path input;
    final Path output;
    final Path log_file;

    private final long job_id;
    /** true: every routing pass is written to the bridge log. Used when the job has one run. */
    private final boolean verbose;

    private volatile Process process;
    private volatile Thread reader;
    private volatile boolean stopped_by_bridge = false;
    private long start_ns;
    private volatile long end_ns = -1;

    volatile int open_connections = -1;
    /** Smallest count of open connections that a pass of this run has reported. The engine gives back its best pass. */
    volatile int best_open = -1;
    volatile int violations = -1;
    volatile int pre_existing_violations = -1;
    volatile boolean read_error = false;
    volatile String stage = "starting";
    volatile int pass = 0;

    EngineRun(int p_number, Variant p_variant, Path p_job_dir, long p_job_id, boolean p_verbose)
    {
        this.number = p_number;
        this.variant = p_variant;
        this.dir = p_job_dir.resolve("run-" + p_number);
        this.input = dir.resolve("input.dsn");
        this.output = dir.resolve("output.ses");
        this.log_file = dir.resolve("engine.log");
        this.job_id = p_job_id;
        this.verbose = p_verbose;
    }

    private String label()
    {
        return verbose ? "job " + job_id : "job " + job_id + " run " + number;
    }

    /**
     * Writes the input of this run and starts the engine.
     *
     * @param p_dsn        the repaired board text
     * @param p_rule_args  engine arguments from config/rules.json. engine.args of config/main.json come after them.
     * @param p_engine_ms  time limit that the engine gets
     * @param p_heap_mb    heap limit of the engine, 0: the default of Java
     * @param p_shared_cpu true: other runs use the processor at the same time
     */
    void start(Config p_config, String p_dsn, List<String> p_rule_args, long p_engine_ms, int p_heap_mb, boolean p_shared_cpu) throws IOException
    {
        Files.createDirectories(dir);
        String text = variant.text_for(p_dsn);
        Files.write(input, text.getBytes(StandardCharsets.UTF_8));

        List<String> command = new ArrayList<String>();
        command.add(p_config.engine_java());
        command.add("-Djava.awt.headless=true");
        if (p_config.engine_offline)
        {
            // Port 9 (discard) on this machine. Nothing listens there, so every HTTP request of the engine fails at once.
            command.add("-Dhttp.proxyHost=127.0.0.1");
            command.add("-Dhttp.proxyPort=9");
            command.add("-Dhttps.proxyHost=127.0.0.1");
            command.add("-Dhttps.proxyPort=9");
            command.add("-Dhttp.nonProxyHosts=");
        }
        if (p_heap_mb > 0)
        {
            command.add("-Xmx" + p_heap_mb + "m");
        }
        if (p_shared_cpu)
        {
            // Java sizes its memory-cleaning threads for the whole machine. Several engines at once do not need that many.
            command.add("-XX:ParallelGCThreads=2");
            command.add("-XX:ConcGCThreads=1");
        }
        command.add("-jar");
        command.add(p_config.engine_jar_path().toString());
        command.add("--gui.enabled=false");
        command.add("-da");
        command.add("--user_data_path=" + dir.resolve("engine-data"));
        command.add("--router.job_timeout=" + hms(p_engine_ms / 1000));
        if (p_config.fanout_share > 0.0)
        {
            command.add("--router.fanout.timeout=" + hms(Math.max(5L, Math.round(p_engine_ms / 1000.0 * p_config.fanout_share))));
        }
        if (p_config.optimizer_share > 0.0)
        {
            command.add("--router.optimizer.timeout=" + hms(Math.max(15L, Math.round(p_engine_ms / 1000.0 * p_config.optimizer_share))));
        }
        if (!variant.classic)
        {
            command.addAll(p_rule_args);
        }
        command.addAll(p_config.engine_args);
        command.addAll(variant.args);
        command.add("-de");
        command.add(input.toString());
        command.add("-do");
        command.add(output.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        // The engine must not inherit proxy settings of the shell when it is offline.
        if (p_config.engine_offline)
        {
            for (String name : new String[]
                    {
                        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy", "JAVA_TOOL_OPTIONS"
                    })
            {
                builder.environment().remove(name);
            }
        }
        start_ns = System.nanoTime();
        final Process started = builder.start();
        process = started;
        Thread thread = new Thread(() -> read_engine_output(started), "engine-log-" + job_id + "-" + number);
        thread.setDaemon(true);
        thread.start();
        reader = thread;
        if (!p_config.engine_affinity_mask.isEmpty())
        {
            set_affinity(started.pid(), p_config.engine_affinity_mask);
        }
    }

    /** Limits the engine process to the processors of the mask. A failure changes nothing and is reported once per run. */
    private void set_affinity(long p_pid, String p_mask)
    {
        final List<String> command = new ArrayList<String>();
        if (Config.is_windows())
        {
            command.add("powershell.exe");
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-Command");
            command.add("(Get-Process -Id " + p_pid + ").ProcessorAffinity = 0x" + p_mask);
        }
        else
        {
            command.add("taskset");
            command.add("-a");
            command.add("-p");
            command.add(p_mask);
            command.add(Long.toString(p_pid));
        }
        Thread thread = new Thread(() ->
        {
            try
            {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process helper = builder.start();
                if (!helper.waitFor(20, TimeUnit.SECONDS))
                {
                    helper.destroyForcibly();
                    Log.error(label() + ": engine.affinity_mask was not applied (no answer from " + command.get(0) + ").");
                }
                else if (helper.exitValue() != 0)
                {
                    Log.error(label() + ": engine.affinity_mask was not applied (" + command.get(0) + " ended with code "
                            + helper.exitValue() + ").");
                }
            }
            catch (IOException e)
            {
                Log.error(label() + ": engine.affinity_mask was not applied: " + e.getMessage());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }, "affinity-" + job_id + "-" + number);
        thread.setDaemon(true);
        thread.start();
    }

    boolean started()
    {
        return process != null;
    }

    boolean alive()
    {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** Seconds from the start to the end of the engine process, or to now while it runs. */
    double seconds()
    {
        long end = end_ns >= 0 ? end_ns : System.nanoTime();
        return (end - start_ns) / 1e9;
    }

    /** Records the end time when the process has ended. Returns true one time: at the first call after the end. */
    boolean note_end()
    {
        if (end_ns >= 0 || process == null || process.isAlive())
        {
            return false;
        }
        end_ns = System.nanoTime();
        return true;
    }

    /** true: the engine ended by itself and wrote a session file. */
    /** For tests: the run did not start, and its folder holds the output of an earlier job. */
    boolean preloaded = false;

    boolean has_result()
    {
        try
        {
            if (preloaded)
            {
                return Files.isRegularFile(output) && Files.size(output) > 0;
            }
            return started() && !alive() && !stopped_by_bridge && Files.isRegularFile(output) && Files.size(output) > 0;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    boolean was_stopped()
    {
        return stopped_by_bridge;
    }

    String exit_code()
    {
        Process p = process;
        if (p == null)
        {
            return "not started";
        }
        return p.isAlive() ? "none" : String.valueOf(p.exitValue());
    }

    /** Waits for the end of the log copy. */
    void join_reader(long p_ms) throws InterruptedException
    {
        Thread t = reader;
        if (t != null)
        {
            t.join(p_ms);
        }
    }

    /** Stops the engine process. */
    void stop()
    {
        Process p = process;
        if (p == null || !p.isAlive())
        {
            return;
        }
        stopped_by_bridge = true;
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroy();
        try
        {
            if (!p.waitFor(2, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
            }
        }
        catch (InterruptedException e)
        {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** One short text for the status line of a job with several runs. */
    String status()
    {
        String open = open_connections >= 0 ? ", " + open_connections + " open" : "";
        if (!started())
        {
            return "not started";
        }
        if (!alive())
        {
            return (stopped_by_bridge ? "stopped" : "done") + open;
        }
        if ("routing".equals(stage) && pass > 0)
        {
            return "pass " + pass + open;
        }
        return stage + open;
    }

    /** Copies the engine output to engine.log and follows the progress. */
    private void read_engine_output(Process p_process)
    {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p_process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = Files.newBufferedWriter(log_file, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                writer.write(line);
                writer.newLine();
                writer.flush();
                follow(line);
            }
        }
        catch (IOException e)
        {
            // the process ended or was stopped
        }
    }

    private void follow(String p_line)
    {
        Matcher m = UNROUTED.matcher(p_line);
        if (m.find())
        {
            open_connections = Integer.parseInt(m.group(1));
            violations = Integer.parseInt(m.group(2));
            if (best_open < 0 || open_connections < best_open)
            {
                best_open = open_connections;
            }
        }
        m = OPTIMIZER_STATE.matcher(p_line);
        if (m.find())
        {
            open_connections = Integer.parseInt(m.group(1));
            violations = Integer.parseInt(m.group(2));
        }
        m = FANOUT_DONE.matcher(p_line);
        if (m.find())
        {
            stage = "routing";
            if (verbose)
            {
                Log.info(label() + ": fanout done, " + m.group(1) + " of " + m.group(2) + " SMD pads escaped.");
            }
            return;
        }
        if (p_line.contains("Fanout stage started"))
        {
            stage = "fanout";
            return;
        }
        if (p_line.contains("Auto-routing stage started"))
        {
            stage = "routing";
            return;
        }
        m = ROUTER_PASS.matcher(p_line);
        if (m.find())
        {
            stage = "routing";
            pass = Integer.parseInt(m.group(1));
            if (verbose)
            {
                Log.info(label() + ": routing pass " + m.group(1) + ", open connections: " + open_connections);
            }
            return;
        }
        m = OPTIMIZER_PASS.matcher(p_line);
        if (m.find())
        {
            stage = "optimizing";
            if (verbose)
            {
                Log.info(label() + ": optimizer pass " + m.group(1) + " (" + m.group(2).toLowerCase() + ")");
            }
            return;
        }
        if (p_line.contains("Optimization stage started"))
        {
            stage = "optimizing";
            return;
        }
        m = PRE_EXISTING.matcher(p_line);
        if (m.find())
        {
            pre_existing_violations = Integer.parseInt(m.group(1));
            if (number == 1)
            {
                Log.info("job " + job_id + ": the board as sent has " + m.group(1)
                        + " clearance violations (pads or fixed copper closer than the clearance rule). Routing near them can fail.");
            }
            return;
        }
        if (p_line.contains("error while reading DSN") || p_line.contains("Couldn't load the board"))
        {
            read_error = true;
        }
        Matcher prefix = LOG_PREFIX.matcher(p_line);
        if (prefix.find() && "ERROR".equals(prefix.group(1)))
        {
            Log.error(label() + ": engine: " + p_line.substring(prefix.end()));
        }
    }

    private static String hms(long p_seconds)
    {
        return String.format("%02d:%02d:%02d", p_seconds / 3600, (p_seconds % 3600) / 60, p_seconds % 60);
    }
}
