package com.grimtorrenter.engine.proxy;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A tiny HTTP/1.0 GET client that connects through a SOCKS5 proxy - exists because the JDK's
 * built-in HTTP client can't use a SOCKS proxy, and the tracker announces and the blocklist
 * download both have to go through one when it's configured (design_docs/0079). Deliberately just
 * enough for those two jobs: GET only, {@code Connection: close}, http and https (TLS is layered
 * over the tunnel with hostname verification), a bounded number of redirects, a hard cap on the
 * body size, and an overall deadline enforced by closing the socket. HTTP/1.0 is used so a server
 * has no reason to send chunked bodies, but a chunked reply is still decoded if one arrives.
 * See design_docs/0079.
 */
public final class MiniHttp {

    private static final String USER_AGENT = "GrimTorrenter/0.1.0";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_HEADER_LINE = 8192;
    private static final int MAX_HEADER_LINES = 100;

    private MiniHttp() {
    }

    /**
     * GETs uri through the proxy, writing the body to sink, and returns the final HTTP status.
     * Follows up to maxRedirects 301/302/303/307/308 responses (http and https only).
     *
     * @throws IOException on any failure, including a body larger than maxBodyBytes or the whole
     *                     exchange taking longer than deadlineSeconds
     */
    public static int get(URI uri, ProxySettings proxy, long maxBodyBytes, OutputStream sink, int maxRedirects,
                          int deadlineSeconds) throws IOException {
        URI current = uri;
        for (int redirects = 0; ; redirects++) {
            RedirectOrStatus outcome = getOnce(current, proxy, maxBodyBytes, sink, deadlineSeconds);
            if (outcome.redirectTo == null) {
                return outcome.status;
            }
            if (redirects >= maxRedirects) {
                throw new IOException("Too many redirects");
            }
            current = outcome.redirectTo;
        }
    }

    private record RedirectOrStatus(int status, URI redirectTo) {
    }

    private static RedirectOrStatus getOnce(URI uri, ProxySettings proxy, long maxBodyBytes, OutputStream sink,
                                            int deadlineSeconds) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !scheme.equals("http")) {
            throw new IOException("Only http(s) URLs are supported");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("URL has no host: " + uri);
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : (https ? 443 : 80);

        Socket tunnel = Socks5.connect(proxy, host, port, CONNECT_TIMEOUT_MS);
        Socket socket = tunnel;
        try {
            if (https) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(tunnel, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                socket = tls;
            }
            Socket toClose = socket;
            // Bounds the whole exchange, not just each read - a server trickling one byte a second
            // would otherwise hold this thread open indefinitely. Closing unblocks a read in progress.
            CompletableFuture.delayedExecutor(deadlineSeconds, TimeUnit.SECONDS).execute(() -> closeQuietly(toClose));
            socket.setSoTimeout(READ_TIMEOUT_MS);

            String pathAndQuery = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) {
                pathAndQuery += "?" + uri.getRawQuery();
            }
            String hostHeader = uri.getPort() >= 0 ? host + ":" + uri.getPort() : host;
            String request = "GET " + pathAndQuery + " HTTP/1.0\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "User-Agent: " + USER_AGENT + "\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                throw new IOException("Not an HTTP response");
            }
            String[] statusParts = statusLine.split(" ", 3);
            if (statusParts.length < 2) {
                throw new IOException("Malformed HTTP status line");
            }
            int status = Integer.parseInt(statusParts[1].trim());

            String location = null;
            boolean chunked = false;
            String line;
            int headerLines = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (++headerLines > MAX_HEADER_LINES) {
                    throw new IOException("Too many response headers");
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("location")) {
                    location = value;
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }

            if (status >= 300 && status < 400 && location != null && status != 304) {
                return new RedirectOrStatus(status, uri.resolve(location));
            }
            if (status != 200) {
                return new RedirectOrStatus(status, null);
            }
            if (chunked) {
                copyChunked(in, sink, maxBodyBytes);
            } else {
                copyBounded(in, sink, maxBodyBytes);
            }
            return new RedirectOrStatus(status, null);
        } finally {
            closeQuietly(socket);
        }
    }

    /** One CRLF- or LF-terminated line as ISO-8859-1, or null at end of stream; a line past
     * MAX_HEADER_LINE bytes is refused. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(b);
            if (line.size() > MAX_HEADER_LINE) {
                throw new IOException("HTTP header line too long");
            }
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
    }

    private static void copyBounded(InputStream in, OutputStream sink, long max) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            total += n;
            if (total > max) {
                throw new IOException("The response is larger than the " + (max >> 10) + " KB limit");
            }
            sink.write(buffer, 0, n);
        }
    }

    private static void copyChunked(InputStream in, OutputStream sink, long max) throws IOException {
        long total = 0;
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                throw new IOException("Truncated chunked response");
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunk size");
            }
            if (size == 0) {
                return;
            }
            total += size;
            if (total > max) {
                throw new IOException("The response is larger than the " + (max >> 10) + " KB limit");
            }
            byte[] chunk = in.readNBytes((int) size);
            if (chunk.length < size) {
                throw new IOException("Truncated chunked response");
            }
            sink.write(chunk);
            readLine(in);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // already closed or failing
        }
    }
}
