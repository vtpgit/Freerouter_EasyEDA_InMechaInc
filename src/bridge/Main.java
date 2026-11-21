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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local auto router server for the EasyEDA Standard editor, with Freerouting as the engine.
 *
 * The editor finds a local router with GET http://127.0.0.1:3579/api/whois and then opens the
 * WebSocket ws://127.0.0.1:3579/router. This server answers both in the way the original
 * EasyEDA router 0.8.11 does.
 */
public final class Main
{
    static final String VERSION = "1.3.0";
    private static final String WHOIS_ANSWER = "EasyEDA Auto Router";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MINIMUM_ENGINE_JAVA = 25;

    static final AtomicLong job_counter = new AtomicLong();
    private static final AtomicInteger running_jobs = new AtomicInteger();

    private Main()
    {
    }

    public static void main(String[] p_args) throws Exception
    {
        if (p_args.length > 0)
        {
            System.exit(Cli.run(p_args));
            return;
        }
        Log.info("EasyEDA to Freerouting bridge " + VERSION);
        Config config = Config.load();
        if (!check_engine(config))
        {
            System.exit(2);
        }
        ServerSocket server;
        try
        {
            server = new ServerSocket(config.port, 50, InetAddress.getByName(config.ip));
        }
        catch (BindException e)
        {
            Log.error("Port " + config.port + " on " + config.ip + " is in use. Another auto router is running. Close it and start again.");
            System.exit(1);
            return;
        }
        Log.info("Listening on " + config.ip + ":" + config.port + ". Keep this window open.");
        Log.info("In the EasyEDA editor: Route > Auto Router, Router Server: Local, then Run.");
        Log.info("Rules that the editor does not send: config/rules.json. Board files: see docs/USER_GUIDE.md, \"Interface 2\".");
        for (;;)
        {
            Socket socket = server.accept();
            Thread thread = new Thread(() -> handle(config, socket), "connection");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Checks that the engine jar exists and that its Java is new enough. */
    static boolean check_engine(Config p_config)
    {
        if (!Files.isRegularFile(p_config.engine_jar_path()))
        {
            Log.error("The routing engine is missing: " + p_config.engine_jar_path());
            Log.error("Run the setup script in the bridge folder (setup.bat on Windows, setup.sh on Linux and macOS).");
            return false;
        }
        String java = p_config.engine_java();
        int version = java_major_version(java);
        if (version < 0)
        {
            Log.error("Cannot run Java for the engine: " + java);
            return false;
        }
        if (version < MINIMUM_ENGINE_JAVA)
        {
            Log.error("The engine needs Java " + MINIMUM_ENGINE_JAVA + " or later. Found Java " + version + " at " + java + ".");
            Log.error("Run the setup script. It puts a Java runtime into the folder \"runtime\".");
            return false;
        }
        Log.info("Engine: " + p_config.engine_jar_path().getFileName() + " on Java " + version + " (" + java + ")");
        int logical = Runtime.getRuntime().availableProcessors();
        long free_mb = Config.free_memory_mb();
        int wanted = p_config.wanted_runs();
        StringBuilder note = new StringBuilder();
        int runs = p_config.runs_for_job(note);
        Log.info("Engine runs per routing job: " + runs
                + (p_config.parallel_runs > 0 ? " (router.parallel_runs)" : " (automatic)") + ". This machine has " + logical
                + " logical processors" + (free_mb >= 0 ? " and " + free_mb + " MB of free memory." : "."));
        if (note.length() > 0)
        {
            Log.info(note.toString());
        }
        if (wanted > logical)
        {
            Log.info("router.parallel_runs is larger than the number of logical processors. The runs will share processors.");
        }
        if (!p_config.engine_affinity_mask.isEmpty())
        {
            Log.info("The engine is limited to the logical processors of mask 0x" + p_config.engine_affinity_mask + ".");
        }
        return true;
    }

    private static int java_major_version(String p_java)
    {
        try
        {
            ProcessBuilder builder = new ProcessBuilder(p_java, "-version");
            builder.redirectErrorStream(true);
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
            {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    sb.append(line).append('\n');
                }
                output = sb.toString();
            }
            process.waitFor(10, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(output);
            if (!m.find())
            {
                return -1;
            }
            int major = Integer.parseInt(m.group(1));
            if (major == 1 && m.group(2) != null)
            {
                major = Integer.parseInt(m.group(2)); // 1.8 is Java 8
            }
            return major;
        }
        catch (IOException e)
        {
            return -1;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static void handle(Config p_config, Socket p_socket)
    {
        try
        {
            p_socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(p_socket.getInputStream(), 65536);
            OutputStream out = p_socket.getOutputStream();
            String head = read_head(in);
            if (head == null)
            {
                p_socket.close();
                return;
            }
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2)
            {
                respond(out, "400 Bad Request", null, "bad request");
                p_socket.close();
                return;
            }
            String method = request[0];
            String path = request[1];
            int query = path.indexOf('?');
            if (query >= 0)
            {
                path = path.substring(0, query);
            }
            Map<String, String> headers = new LinkedHashMap<String, String>();
            for (int i = 1; i < lines.length; ++i)
            {
                int colon = lines[i].indexOf(':');
                if (colon > 0)
                {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                }
            }
            String origin = headers.get("origin");
            boolean origin_ok = origin_allowed(p_config, origin);

            if (path.equals("/router") && "websocket".equalsIgnoreCase(headers.get("upgrade")))
            {
                String key = headers.get("sec-websocket-key");
                if (key == null || !origin_ok)
                {
                    if (!origin_ok)
                    {
                        Log.error("Connection refused. Origin not in allowed_origins: " + origin);
                    }
                    respond(out, key == null ? "400 Bad Request" : "403 Forbidden", null, "refused");
                    p_socket.close();
                    return;
                }
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                String accept = Base64.getEncoder().encodeToString(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                serve_editor(p_config, new WsConnection(p_socket, in, out), origin);
                return;
            }

            if (path.equals("/api/whois"))
            {
                Map<String, String> extra = new LinkedHashMap<String, String>();
                if (origin != null && !origin.isEmpty() && origin_ok)
                {
                    extra.put("Access-Control-Allow-Origin", origin);
                    extra.put("Access-Control-Allow-Private-Network", "true");
                    extra.put("Vary", "Origin");
                }
                if ("OPTIONS".equals(method))
                {
                    extra.put("Access-Control-Allow-Methods", "GET, OPTIONS");
                    String requested = headers.get("access-control-request-headers");
                    if (requested != null)
                    {
                        extra.put("Access-Control-Allow-Headers", requested);
                    }
                    respond(out, "204 No Content", extra, "");
                }
                else
                {
                    respond(out, "200 OK", extra, WHOIS_ANSWER);
                }
                p_socket.close();
                return;
            }

            if (path.equals("/"))
            {
                respond(out, "200 OK", null, "EasyEDA to Freerouting bridge " + VERSION + ". Engine: Freerouting (GPLv3).");
            }
            else
            {
                respond(out, "404 Not Found", null, "not found");
            }
            p_socket.close();
        }
        catch (Exception e)
        {
            try
            {
                p_socket.close();
            }
            catch (IOException e2)
            {
                // nothing to do
            }
        }
    }

    private static boolean origin_allowed(Config p_config, String p_origin)
    {
        if (p_config.allowed_origins.isEmpty())
        {
            return true;
        }
        return p_origin != null && p_config.allowed_origins.contains(p_origin);
    }

    /**
     * One editor connection. A new request replaces a running job. Closing the socket cancels it.
     *
     * Requests:
     *   {"a":"startRoute","data":"<board text of the editor>","timeout":300}   the auto router dialog of the editor
     *   {"a":"routeFile","data":"<EasyEDA PCB source as JSON text>","timeout":300}   the script of the bridge
     */
    private static void serve_editor(Config p_config, WsConnection p_connection, String p_origin)
    {
        Log.info("Editor connected" + (p_origin == null ? "." : " (" + p_origin + ")."));
        Runnable cancel_job = null;
        Thread job_thread = null;
        try
        {
            for (;;)
            {
                String text = p_connection.read_text();
                if (text == null)
                {
                    break;
                }
                Map<String, Object> message;
                try
                {
                    message = Json.parse_object(text);
                }
                catch (IllegalArgumentException e)
                {
                    Log.error("Message is not JSON. Ignored.");
                    continue;
                }
                boolean file_request = "routeFile".equals(message.get("a"));
                if (!file_request && !"startRoute".equals(message.get("a")))
                {
                    continue;
                }
                if (cancel_job != null)
                {
                    // The old job must be gone before the new one counts against max_parallel_jobs.
                    cancel_job.run();
                    if (job_thread != null)
                    {
                        try
                        {
                            job_thread.join(8000);
                        }
                        catch (InterruptedException e)
                        {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                String data = Json.as_string(message.get("data"), null);
                String refusal = file_request ? "{\"a\": \"fileResult\", \"complete\": -2, \"message\": "
                        : "{\"a\": \"routingResult\", \"inCompleteNetNum\": -1, \"complete\": -2, \"data\": {}, \"message\": ";
                if (data == null)
                {
                    p_connection.send_text(refusal + "\"The request has no board data.\"}");
                    continue;
                }
                // A job that has just sent its result still counts for a moment, while it removes old job folders.
                for (int i = 0; i < 50 && running_jobs.get() >= p_config.max_parallel_jobs; ++i)
                {
                    try
                    {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (running_jobs.get() >= p_config.max_parallel_jobs)
                {
                    Log.error("Too many routing jobs at the same time. Request refused.");
                    p_connection.send_text(refusal + "\"Another routing job is running.\"}");
                    continue;
                }
                long id = job_counter.incrementAndGet();
                final Runnable body;
                if (file_request)
                {
                    Rules rules = Rules.load(p_config.home.resolve("config").resolve("rules.json"));
                    final FileJob file_job = new FileJob(p_config, rules, data, Json.as_int(message.get("timeout"), 0), id);
                    cancel_job = file_job::cancel;
                    body = () -> run_file_job(p_config, p_connection, file_job);
                }
                else
                {
                    final RoutingJob new_job = new RoutingJob(p_config, p_connection, data, Json.as_int(message.get("timeout"), 300), id);
                    cancel_job = new_job::cancel;
                    body = new_job;
                }
                running_jobs.incrementAndGet();
                Thread thread = new Thread(() ->
                {
                    try
                    {
                        body.run();
                    }
                    finally
                    {
                        running_jobs.decrementAndGet();
                    }
                }, "job-" + id);
                thread.setDaemon(true);
                thread.start();
                job_thread = thread;
            }
        }
        catch (IOException e)
        {
            // the editor closed the connection
        }
        finally
        {
            if (cancel_job != null)
            {
                cancel_job.run();
            }
            p_connection.close(1000, "");
            Log.info("Editor disconnected.");
        }
    }

    /**
     * Runs a file job for the script in the editor. Answers:
     *   {"a":"heartbeat"} and {"a":"fileProgress","open":n} while the engines work
     *   {"a":"fileResult","complete":1,"open":n,"openBefore":m,"data":"<board JSON text>","report":"<text>"}
     *   {"a":"fileResult","complete":-1,"message":"<reason>"}
     */
    private static void run_file_job(Config p_config, WsConnection p_connection, FileJob p_job)
    {
        try
        {
            p_connection.send_text("{\"a\":\"heartbeat\"}\n");
            FileJob.Outcome outcome = p_job.run(open ->
            {
                p_connection.send_text(open >= 0 ? "{\"a\": \"fileProgress\", \"open\": " + open + "}" : "{\"a\":\"heartbeat\"}\n");
            });
            if ("cancelled".equals(outcome.message))
            {
                return;
            }
            StringBuilder sb = new StringBuilder(outcome.ok ? outcome.json.length() + outcome.report.length() + 256 : 256);
            if (outcome.ok)
            {
                sb.append("{\"a\": \"fileResult\", \"complete\": 1, \"open\": ").append(outcome.open_after).append(", \"openBefore\": ")
                        .append(outcome.open_before).append(", \"report\": ");
                Json.append_string(sb, outcome.report);
                sb.append(", \"data\": ");
                Json.append_string(sb, outcome.json);
                sb.append("}");
            }
            else
            {
                sb.append("{\"a\": \"fileResult\", \"complete\": -1, \"message\": ");
                Json.append_string(sb, outcome.message);
                sb.append("}");
            }
            p_connection.send_text(sb.toString());
        }
        catch (IOException e)
        {
            // the editor is gone
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads the HTTP request head up to the empty line. Returns null on a closed or oversized request. */
    private static String read_head(InputStream p_in) throws IOException
    {
        StringBuilder sb = new StringBuilder(512);
        int state = 0;
        while (sb.length() < 32768)
        {
            int b = p_in.read();
            if (b < 0)
            {
                return null;
            }
            sb.append((char) b);
            if (b == '\r' && (state == 0 || state == 2))
            {
                ++state;
            }
            else if (b == '\n' && (state == 1 || state == 3))
            {
                ++state;
                if (state == 4)
                {
                    return sb.toString();
                }
            }
            else
            {
                state = 0;
            }
        }
        return null;
    }

    private static void respond(OutputStream p_out, String p_status, Map<String, String> p_headers, String p_body) throws IOException
    {
        byte[] body = p_body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(p_status).append("\r\n");
        if (p_headers != null)
        {
            for (Map.Entry<String, String> entry : p_headers.entrySet())
            {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        p_out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        p_out.write(body);
        p_out.flush();
    }
}
