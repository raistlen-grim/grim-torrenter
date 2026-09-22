package com.grimtorrenter.engine.proxy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A minimal SOCKS5 client (RFC 1928, username/password auth per RFC 1929), written by hand rather
 * than via the JDK's built-in SOCKS support for three reasons that matter to a privacy feature:
 * a hostname is always handed to the proxy to resolve (never resolved locally first, which would
 * leak the lookup), credentials live in one place instead of a JVM-wide {@code Authenticator},
 * and the JDK has no UDP ASSOCIATE at all - which UDP trackers need. See design_docs/0079.
 *
 * <p>Every method here either fully succeeds or throws {@link IOException}; a failure to reach or
 * authenticate with the proxy is never silently turned into a direct connection - that decision,
 * if it's ever made, belongs to the caller.
 */
public final class Socks5 {

    private static final int VERSION = 5;
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_USER_PASS = 0x02;
    private static final int METHOD_NONE_ACCEPTABLE = 0xFF;
    private static final int AUTH_VERSION = 1;
    private static final int CMD_CONNECT = 1;
    private static final int CMD_UDP_ASSOCIATE = 3;
    private static final int ATYP_IPV4 = 1;
    private static final int ATYP_DOMAIN = 3;
    private static final int ATYP_IPV6 = 4;
    private static final int UDP_BUFFER_SIZE = 4096;
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private Socks5() {
    }

    /** What {@link #test} found out about a proxy. */
    public record TestResult(boolean reachable, boolean udpSupported, String message) {
    }

    /** Direct connection when no proxy is configured, otherwise a SOCKS5 tunnel to the same
     * destination - what every outbound TCP path calls so none of them has to know about
     * proxies. A proxy that fails is a failed connection, never a fallback to direct. */
    public static Socket connect(ProxyProvider provider, InetSocketAddress destination, int timeoutMs)
            throws IOException {
        Optional<ProxySettings> proxy = provider.current();
        if (proxy.isEmpty()) {
            Socket socket = new Socket();
            try {
                socket.connect(destination, timeoutMs);
            } catch (IOException e) {
                closeQuietly(socket);
                throw e;
            }
            return socket;
        }
        return connect(proxy.get(), destination, timeoutMs);
    }

    /** A tunnel to destination through the proxy. An unresolved destination is passed to the
     * proxy by name, so the proxy does the DNS lookup. */
    public static Socket connect(ProxySettings proxy, InetSocketAddress destination, int timeoutMs)
            throws IOException {
        if (destination.isUnresolved()) {
            return connect(proxy, destination.getHostString(), destination.getPort(), timeoutMs);
        }
        return connect(proxy, destination.getAddress().getHostAddress(), destination.getPort(), timeoutMs);
    }

    /** A tunnel to host:port through the proxy; host may be a name or an IP literal. */
    public static Socket connect(ProxySettings proxy, String host, int port, int timeoutMs) throws IOException {
        Socket socket = openControl(proxy, timeoutMs);
        try {
            OutputStream out = socket.getOutputStream();
            writeRequest(out, CMD_CONNECT, host, port);
            readReply(socket.getInputStream());
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /** Opens a UDP association on the proxy (the relay a UDP tracker's datagrams go through). */
    public static UdpRelay openUdpRelay(ProxySettings proxy, int timeoutMs) throws IOException {
        Socket control = openControl(proxy, timeoutMs);
        DatagramSocket udp = null;
        try {
            // Zero address/port: we don't know the source of our UDP datagrams yet, which RFC 1928
            // explicitly allows for.
            writeRequest(control.getOutputStream(), CMD_UDP_ASSOCIATE, "0.0.0.0", 0);
            Reply reply = readReply(control.getInputStream());
            InetAddress relayAddress = reply.address != null && !reply.address.isAnyLocalAddress()
                    ? reply.address
                    : control.getInetAddress();
            udp = new DatagramSocket();
            control.setSoTimeout(0);
            return new UdpRelay(control, udp, new InetSocketAddress(relayAddress, reply.port));
        } catch (IOException | RuntimeException e) {
            closeQuietly(control);
            if (udp != null) {
                udp.close();
            }
            throw e;
        }
    }

    /** Checks a proxy end to end for the settings page's "Test" button: is it reachable, does it
     * accept our credentials, and does it relay UDP (needed for UDP trackers)? Never throws. */
    public static TestResult test(ProxySettings proxy, int timeoutMs) {
        try (Socket control = openControl(proxy, timeoutMs)) {
            // Reaching this line means the greeting and (if configured) the login both succeeded.
            try (UdpRelay ignored = openUdpRelay(proxy, timeoutMs)) {
                return new TestResult(true, true,
                        "Connected to the proxy. UDP is supported, so UDP trackers will work.");
            } catch (IOException e) {
                return new TestResult(true, false, "Connected to the proxy, but it doesn't relay UDP ("
                        + e.getMessage() + "). UDP trackers won't work through it; HTTP trackers and peers will.");
            }
        } catch (IOException e) {
            return new TestResult(false, false, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static Socket openControl(ProxySettings proxy, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxy.host(), proxy.port()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            negotiate(socket.getInputStream(), socket.getOutputStream(), proxy);
            return socket;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            if (e instanceof java.net.ConnectException || e instanceof SocketTimeoutException) {
                throw new IOException("Could not reach the proxy at " + proxy.host() + ":" + proxy.port()
                        + " (" + e.getMessage() + ")", e);
            }
            throw e;
        }
    }

    private static void negotiate(InputStream in, OutputStream out, ProxySettings proxy) throws IOException {
        if (proxy.hasCredentials()) {
            out.write(new byte[] {VERSION, 2, METHOD_USER_PASS, METHOD_NO_AUTH});
        } else {
            out.write(new byte[] {VERSION, 1, METHOD_NO_AUTH});
        }
        out.flush();
        DataInputStream data = new DataInputStream(in);
        int version = data.readUnsignedByte();
        int method = data.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("The server at " + proxy.host() + ":" + proxy.port() + " isn't a SOCKS5 proxy");
        }
        if (method == METHOD_NONE_ACCEPTABLE) {
            throw new IOException("The proxy rejected every authentication method offered");
        }
        if (method == METHOD_USER_PASS) {
            if (!proxy.hasCredentials()) {
                throw new IOException("The proxy requires a username and password");
            }
            byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
            byte[] pass = (proxy.password() == null ? "" : proxy.password()).getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) {
                throw new IOException("The proxy username or password is longer than SOCKS5 allows (255 bytes)");
            }
            byte[] request = new byte[3 + user.length + pass.length];
            request[0] = AUTH_VERSION;
            request[1] = (byte) user.length;
            System.arraycopy(user, 0, request, 2, user.length);
            request[2 + user.length] = (byte) pass.length;
            System.arraycopy(pass, 0, request, 3 + user.length, pass.length);
            out.write(request);
            out.flush();
            data.readUnsignedByte();
            if (data.readUnsignedByte() != 0) {
                throw new IOException("The proxy rejected the username or password");
            }
        } else if (method != METHOD_NO_AUTH) {
            throw new IOException("The proxy chose an unsupported authentication method (" + method + ")");
        }
    }

    private static void writeRequest(OutputStream out, int command, String host, int port) throws IOException {
        byte[] address = addressBytes(host);
        // VER, CMD, RSV, then ATYP+address (address already includes its ATYP byte), then the
        // 2-byte port - exactly 3 + address.length + 2 bytes. An extra trailing byte here would be
        // forwarded by the proxy as the first byte of the tunnelled data.
        byte[] request = new byte[3 + address.length + 2];
        request[0] = VERSION;
        request[1] = (byte) command;
        request[2] = 0;
        System.arraycopy(address, 0, request, 3, address.length);
        request[3 + address.length] = (byte) (port >>> 8);
        request[4 + address.length] = (byte) port;
        out.write(request);
        out.flush();
    }

    /** ATYP + address, for an IPv4 literal or a domain name (IPv6 literals aren't supported - this
     * engine is IPv4-only elsewhere too). */
    private static byte[] addressBytes(String host) throws IOException {
        if (IPV4_LITERAL.matcher(host).matches()) {
            String[] octets = host.split("\\.");
            byte[] bytes = new byte[5];
            bytes[0] = ATYP_IPV4;
            for (int i = 0; i < 4; i++) {
                int value = Integer.parseInt(octets[i]);
                if (value > 255) {
                    throw new IOException("Invalid IPv4 address: " + host);
                }
                bytes[1 + i] = (byte) value;
            }
            return bytes;
        }
        if (host.indexOf(':') >= 0) {
            throw new IOException("IPv6 destinations aren't supported through the proxy");
        }
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > 255) {
            throw new IOException("Invalid hostname length for SOCKS5: " + host);
        }
        byte[] bytes = new byte[2 + name.length];
        bytes[0] = ATYP_DOMAIN;
        bytes[1] = (byte) name.length;
        System.arraycopy(name, 0, bytes, 2, name.length);
        return bytes;
    }

    private record Reply(InetAddress address, int port) {
    }

    private static Reply readReply(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int version = data.readUnsignedByte();
        int status = data.readUnsignedByte();
        data.readUnsignedByte();
        int atyp = data.readUnsignedByte();
        InetAddress address = null;
        switch (atyp) {
            case ATYP_IPV4 -> {
                byte[] bytes = new byte[4];
                data.readFully(bytes);
                address = InetAddress.getByAddress(bytes);
            }
            case ATYP_IPV6 -> {
                byte[] bytes = new byte[16];
                data.readFully(bytes);
                address = InetAddress.getByAddress(bytes);
            }
            case ATYP_DOMAIN -> {
                // Read and ignored, never resolved: looking up a name the proxy handed us would be
                // a local DNS query nobody asked for. The callers that care about the bound
                // address fall back to the proxy's own address when this is null.
                byte[] name = new byte[data.readUnsignedByte()];
                data.readFully(name);
            }
            default -> throw new IOException("The proxy sent an unrecognised address type (" + atyp + ")");
        }
        int port = data.readUnsignedShort();
        if (version != VERSION) {
            throw new IOException("Not a SOCKS5 reply");
        }
        if (status != 0) {
            throw new IOException("The proxy refused the request: " + describe(status));
        }
        return new Reply(address, port);
    }

    private static String describe(int status) {
        return switch (status) {
            case 1 -> "general failure";
            case 2 -> "not allowed by the proxy's rules";
            case 3 -> "network unreachable";
            case 4 -> "host unreachable";
            case 5 -> "connection refused";
            case 6 -> "TTL expired";
            case 7 -> "command not supported (the proxy doesn't do UDP)";
            case 8 -> "address type not supported";
            default -> "error " + status;
        };
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    /**
     * A UDP association: datagrams are wrapped in the SOCKS5 UDP header and sent to the proxy's
     * relay, which forwards them and returns replies the same way. The TCP control connection
     * must stay open for as long as the association is needed - closing it (via {@link #close})
     * ends the association. Not thread-safe; one announce uses one relay.
     */
    public static final class UdpRelay implements Closeable {

        private final Socket control;
        private final DatagramSocket udp;
        private final InetSocketAddress relay;

        private UdpRelay(Socket control, DatagramSocket udp, InetSocketAddress relay) {
            this.control = control;
            this.udp = udp;
            this.relay = relay;
        }

        /** Sends data to host:port via the relay. host may be a name (resolved by the proxy) or an
         * IPv4 literal. */
        public void send(String host, int port, byte[] data) throws IOException {
            byte[] address = addressBytes(host);
            byte[] packet = new byte[3 + address.length + 2 + data.length];
            // RSV (2 bytes) and FRAG (1 byte) are zero; fragmentation isn't used.
            System.arraycopy(address, 0, packet, 3, address.length);
            packet[3 + address.length] = (byte) (port >>> 8);
            packet[4 + address.length] = (byte) port;
            System.arraycopy(data, 0, packet, 5 + address.length, data.length);
            udp.send(new DatagramPacket(packet, packet.length, relay));
        }

        /** Waits up to timeoutMs for one datagram from the relay and returns its payload with the
         * SOCKS header stripped; datagrams from anywhere else, and malformed ones, are skipped.
         * @throws SocketTimeoutException if nothing usable arrived in time */
        public byte[] receive(int timeoutMs) throws IOException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buffer = new byte[UDP_BUFFER_SIZE];
            while (true) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    throw new SocketTimeoutException("No reply from the proxy's UDP relay");
                }
                udp.setSoTimeout((int) remainingMs);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                if (!packet.getAddress().equals(relay.getAddress())) {
                    continue;
                }
                byte[] payload = stripHeader(packet.getData(), packet.getLength());
                if (payload != null) {
                    return payload;
                }
            }
        }

        private static byte[] stripHeader(byte[] data, int length) {
            if (length < 4) {
                return null;
            }
            int atyp = data[3] & 0xFF;
            int headerLength = switch (atyp) {
                case ATYP_IPV4 -> 3 + 1 + 4 + 2;
                case ATYP_IPV6 -> 3 + 1 + 16 + 2;
                case ATYP_DOMAIN -> length > 4 ? 3 + 1 + 1 + (data[4] & 0xFF) + 2 : -1;
                default -> -1;
            };
            if (headerLength < 0 || headerLength > length) {
                return null;
            }
            byte[] payload = new byte[length - headerLength];
            System.arraycopy(data, headerLength, payload, 0, payload.length);
            return payload;
        }

        @Override
        public void close() {
            udp.close();
            closeQuietly(control);
        }
    }
}
