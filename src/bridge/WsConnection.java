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

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server side of one WebSocket connection (RFC 6455), text messages only, no extensions.
 * The EasyEDA editor sends one large text message per routing request.
 */
final class WsConnection
{
    /** Same limit as the original EasyEDA server: 20480000 bytes per message. */
    static final long MAX_MESSAGE_BYTES = 20480000L;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed = false;

    WsConnection(Socket p_socket, InputStream p_in, OutputStream p_out)
    {
        this.socket = p_socket;
        this.in = p_in;
        this.out = p_out;
    }

    boolean is_closed()
    {
        return closed;
    }

    /** Blocks until a complete text message arrives. Returns null when the peer closes. */
    String read_text() throws IOException
    {
        ByteArrayOutputStream message = null;
        boolean text_message = false;
        for (;;)
        {
            int b0 = in.read();
            if (b0 < 0)
            {
                return null;
            }
            int b1 = read_byte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            if (length == 126)
            {
                length = ((long) read_byte() << 8) | read_byte();
            }
            else if (length == 127)
            {
                length = 0;
                for (int i = 0; i < 8; ++i)
                {
                    length = (length << 8) | read_byte();
                }
            }
            if (length < 0 || length > MAX_MESSAGE_BYTES)
            {
                close(1009, "message too big");
                return null;
            }
            byte[] mask = null;
            if (masked)
            {
                mask = read_bytes(4);
            }
            byte[] payload = read_bytes((int) length);
            if (mask != null)
            {
                for (int i = 0; i < payload.length; ++i)
                {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode)
            {
                case 0x8: // close
                    close(1000, "");
                    return null;
                case 0x9: // ping
                    send_frame(0xA, payload);
                    continue;
                case 0xA: // pong
                    continue;
                case 0x1: // text
                case 0x2: // binary
                    text_message = opcode == 0x1;
                    message = new ByteArrayOutputStream(Math.max(payload.length, 256));
                    break;
                case 0x0: // continuation
                    if (message == null)
                    {
                        close(1002, "unexpected continuation");
                        return null;
                    }
                    break;
                default:
                    close(1002, "unknown opcode");
                    return null;
            }
            if (message.size() + (long) payload.length > MAX_MESSAGE_BYTES)
            {
                close(1009, "message too big");
                return null;
            }
            message.write(payload, 0, payload.length);
            if (fin)
            {
                byte[] bytes = message.toByteArray();
                message = null;
                if (text_message)
                {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                // binary messages are ignored, as in the original server
            }
        }
    }

    void send_text(String p_text) throws IOException
    {
        send_frame(0x1, p_text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void send_frame(int p_opcode, byte[] p_payload) throws IOException
    {
        if (closed)
        {
            throw new IOException("connection is closed");
        }
        int length = p_payload.length;
        byte[] header;
        if (length < 126)
        {
            header = new byte[]
            {
                (byte) (0x80 | p_opcode), (byte) length
            };
        }
        else if (length < 65536)
        {
            header = new byte[]
            {
                (byte) (0x80 | p_opcode), (byte) 126, (byte) (length >>> 8), (byte) length
            };
        }
        else
        {
            header = new byte[10];
            header[0] = (byte) (0x80 | p_opcode);
            header[1] = (byte) 127;
            long l = length;
            for (int i = 0; i < 8; ++i)
            {
                header[9 - i] = (byte) (l >>> (8 * i));
            }
        }
        try
        {
            out.write(header);
            out.write(p_payload);
            out.flush();
        }
        catch (IOException e)
        {
            closed = true;
            throw e;
        }
    }

    void close(int p_code, String p_reason)
    {
        if (closed)
        {
            return;
        }
        try
        {
            byte[] reason = p_reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reason.length];
            payload[0] = (byte) (p_code >>> 8);
            payload[1] = (byte) p_code;
            System.arraycopy(reason, 0, payload, 2, reason.length);
            send_frame(0x8, payload);
        }
        catch (IOException e)
        {
            // the peer is gone
        }
        closed = true;
        try
        {
            socket.close();
        }
        catch (IOException e)
        {
            // nothing to do
        }
    }

    private int read_byte() throws IOException
    {
        int b = in.read();
        if (b < 0)
        {
            throw new EOFException();
        }
        return b;
    }

    private byte[] read_bytes(int p_count) throws IOException
    {
        byte[] result = new byte[p_count];
        int done = 0;
        while (done < p_count)
        {
            int n = in.read(result, done, p_count - done);
            if (n < 0)
            {
                throw new EOFException();
            }
            done += n;
        }
        return result;
    }
}
