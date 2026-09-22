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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real SOCKS5 proxy on loopback for tests (design_docs/0079): CONNECT (piping bytes to the real
 * target) and UDP ASSOCIATE (relaying datagrams both ways), with optional username/password
 * auth. A destination *name* is mapped to a real local address with {@link #map} - so a test can
 * announce to "tracker.test" and prove the proxy, not the client, was the one that resolved it -
 * and every destination the client asked for is recorded exactly as it was sent (a name stays a
 * name).
 */
public final class FakeSocks5Proxy implements Closeable {

    private final ServerSocket server;
    private final Map<String, InetSocketAddress> hosts = new ConcurrentHashMap<>();
    private final List<Closeable> open = new CopyOnWriteArrayList<>();

    /** null means no authentication is required. */
    public volatile String requiredUsername;
    public volatile String requiredPassword;
    /** When true, UDP ASSOCIATE is answered "command not supported". */
    public volatile boolean rejectUdp;
    /** A non-zero SOCKS reply code to send for every CONNECT instead of connecting. */
    public volatile int connectReplyCode;
    /** Destinations requested by CONNECT / by UDP datagrams, as "host:port" exactly as sent. */
    public final List<String> connectTargets = new CopyOnWriteArrayList<>();
    public final List<String> udpTargets = new CopyOnWriteArrayList<>();
    public final AtomicInteger connectionsAccepted = new AtomicInteger();

    public FakeSocks5Proxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("fake-socks5-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public ProxySettings settings() {
        return new ProxySettings("127.0.0.1", port(), null, null);
    }

    public ProxySettings settings(String username, String password) {
        return new ProxySettings("127.0.0.1", port(), username, password);
    }

    /** Makes the proxy resolve name to target (whatever port the client asked for is ignored). */
    public void map(String name, InetSocketAddress target) {
        hosts.put(name, target);
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // shutting down
        }
        for (Closeable closeable : open) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                open.add(client);
                connectionsAccepted.incrementAndGet();
                Thread.ofVirtual().name("fake-socks5-conn").start(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (!authenticate(in, out)) {
                client.close();
                return;
            }
            in.readUnsignedByte(); // version
            int command = in.readUnsignedByte();
            in.readUnsignedByte(); // reserved
            String host = readAddress(in, in.readUnsignedByte());
            int port = in.readUnsignedShort();
            if (command == 1) {
                handleConnect(client, out, host, port);
            } else if (command == 3) {
                handleUdpAssociate(client, in, out);
            } else {
                reply(out, 7, 0);
                client.close();
            }
        } catch (IOException e) {
            try {
                client.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private boolean authenticate(DataInputStream in, OutputStream out) throws IOException {
        in.readUnsignedByte(); // version
        int methodCount = in.readUnsignedByte();
        boolean offersUserPass = false;
        boolean offersNoAuth = false;
        for (int i = 0; i < methodCount; i++) {
            int method = in.readUnsignedByte();
            offersUserPass |= method == 2;
            offersNoAuth |= method == 0;
        }
        if (requiredUsername == null) {
            if (!offersNoAuth) {
                out.write(new byte[] {5, (byte) 0xFF});
                return false;
            }
            out.write(new byte[] {5, 0});
            return true;
        }
        if (!offersUserPass) {
            out.write(new byte[] {5, (byte) 0xFF});
            return false;
        }
        out.write(new byte[] {5, 2});
        in.readUnsignedByte(); // auth version
        byte[] user = new byte[in.readUnsignedByte()];
        in.readFully(user);
        byte[] pass = new byte[in.readUnsignedByte()];
        in.readFully(pass);
        boolean ok = requiredUsername.equals(new String(user, StandardCharsets.UTF_8))
                && requiredPassword.equals(new String(pass, StandardCharsets.UTF_8));
        out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
        return ok;
    }

    private static String readAddress(DataInputStream in, int atyp) throws IOException {
        return switch (atyp) {
            case 1 -> {
                byte[] bytes = new byte[4];
                in.readFully(bytes);
                yield (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
            }
            case 3 -> {
                byte[] name = new byte[in.readUnsignedByte()];
                in.readFully(name);
                yield new String(name, StandardCharsets.UTF_8);
            }
            default -> throw new IOException("unsupported ATYP " + atyp);
        };
    }

    private InetSocketAddress resolve(String host, int port) throws IOException {
        InetSocketAddress mapped = hosts.get(host);
        return mapped != null ? mapped : new InetSocketAddress(InetAddress.getByName(host), port);
    }

    private static void reply(OutputStream out, int code, int boundPort) throws IOException {
        out.write(new byte[] {5, (byte) code, 0, 1, 127, 0, 0, 1, (byte) (boundPort >>> 8), (byte) boundPort});
        out.flush();
    }

    private void handleConnect(Socket client, OutputStream out, String host, int port) throws IOException {
        connectTargets.add(host + ":" + port);
        if (connectReplyCode != 0) {
            reply(out, connectReplyCode, 0);
            client.close();
            return;
        }
        Socket target;
        try {
            target = new Socket();
            target.connect(resolve(host, port), 3000);
        } catch (IOException e) {
            reply(out, 4, 0);
            client.close();
            return;
        }
        open.add(target);
        reply(out, 0, 0);
        Thread.ofVirtual().start(() -> pipe(target, client));
        pipe(client, target);
    }

    private static void pipe(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException ignored) {
            // one side closed - normal end of a tunnel
        } finally {
            try {
                to.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    private void handleUdpAssociate(Socket client, DataInputStream in, OutputStream out) throws IOException {
        if (rejectUdp) {
            reply(out, 7, 0);
            client.close();
            return;
        }
        DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        open.add(relay);
        reply(out, 0, relay.getLocalPort());
        Thread.ofVirtual().name("fake-socks5-udp").start(() -> relayLoop(relay));
        // The association lasts as long as this TCP connection does.
        try {
            while (in.read() >= 0) {
                // ignore anything the client sends on the control connection
            }
        } catch (IOException ignored) {
            // closed
        } finally {
            relay.close();
            client.close();
        }
    }

    private void relayLoop(DatagramSocket relay) {
        InetSocketAddress clientAddress = null;
        byte[] buffer = new byte[4096];
        while (!relay.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
                if (clientAddress == null) {
                    clientAddress = source;
                }
                if (source.equals(clientAddress)) {
                    forwardToDestination(relay, packet);
                } else {
                    // A reply from a destination: wrap it and hand it to the client.
                    byte[] payload = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                    byte[] ip = packet.getAddress().getAddress();
                    byte[] wrapped = new byte[10 + payload.length];
                    wrapped[3] = 1;
                    System.arraycopy(ip, 0, wrapped, 4, 4);
                    wrapped[8] = (byte) (packet.getPort() >>> 8);
                    wrapped[9] = (byte) packet.getPort();
                    System.arraycopy(payload, 0, wrapped, 10, payload.length);
                    relay.send(new DatagramPacket(wrapped, wrapped.length, clientAddress));
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    private void forwardToDestination(DatagramSocket relay, DatagramPacket packet) throws IOException {
        byte[] data = packet.getData();
        DataInputStream header = new DataInputStream(new java.io.ByteArrayInputStream(data, 0, packet.getLength()));
        header.skipBytes(3);
        String host = readAddress(header, header.readUnsignedByte());
        int port = header.readUnsignedShort();
        udpTargets.add(host + ":" + port);
        int headerLength = packet.getLength() - header.available();
        byte[] payload = java.util.Arrays.copyOfRange(data, headerLength, packet.getLength());
        InetSocketAddress destination = resolve(host, port);
        relay.send(new DatagramPacket(payload, payload.length, destination));
    }
}
