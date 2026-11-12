package com.minivoice.ime.sync;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网内的点对点同步。双方各点一次“局域网同步”后：
 * 1. 每台手机开一个临时 TCP 端口，等对方推数据；
 * 2. 每台手机每秒向局域网广播一次自己的设备名、端口和本次会话码；
 * 3. 双方收到广播后互相出现在列表里，点一下就把词库和快捷短语推过去。
 *
 * 数据只在同一 WiFi 内走，不经过公网，也不上传语音和转写原文。
 * 用 UDP 广播而不是只靠 mDNS，是因为国产 ROM 和部分路由器对 mDNS 支持不稳定。
 */
public class LanSyncManager {

    public static final int DISCOVERY_PORT = 8988;
    /** 12 秒够慢的路由器反应，广播包也只有十几 KB，不会造成网络负担。 */
    public static final long DISCOVERY_WINDOW_MS = 12_000L;

    private static final String TAG = "LanSync";
    private static final String APP = "minivoice-sync";
    private static final Gson GSON = new Gson();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface PeerCallback {
        void onResult(List<Peer> peers);

        void onError(String message);
    }

    public interface PushCallback {
        void onDone(boolean ok, String message);
    }

    /** 收到对方的同步请求后，交给设置页弹窗确认。 */
    public interface OfferCallback {
        void onOffer(Offer offer, DecisionCallback decision);
    }

    public interface DecisionCallback {
        void accept();

        void reject();
    }

    public static class Offer {
        public String sender;
        public List<String> groups;
        public Map<String, String> payload;

        public Offer(String sender, List<String> groups, Map<String, String> payload) {
            this.sender = sender;
            this.groups = groups;
            this.payload = payload;
        }
    }

    public static class Peer {
        public String id;
        public String name;
        public String host;
        public String code;
        public int port;

        @Override
        public String toString() {
            return name + "  " + host + ":" + port;
        }
    }

    private static class Packet {
        String app;
        String type;
        String id;
        String name;
        String code;
        int port;
        long ts;
    }

    private final List<Peer> peers = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean discovering;
    private volatile boolean serving;
    private volatile String deviceId;
    private volatile String deviceName;
    private volatile String pairCode;
    private volatile int dataPort;
    private DatagramSocket discoverySocket;
    private ServerSocket serverSocket;
    private Thread discoveryThread;
    private Thread announceThread;
    private Thread serverThread;
    private PeerCallback activeCallback;
    private OfferCallback offerCallback;

    public void setOfferCallback(OfferCallback callback) {
        offerCallback = callback;
    }

    /**
     * 开始一次同步会话。每台手机都要调用一次，调用后本机既广播也监听。
     */
    public void startSession(Context ctx, String name, PeerCallback callback) {
        stop(ctx);

        deviceId = UUID.randomUUID().toString();
        deviceName = name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
        pairCode = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        activeCallback = callback;
        peers.clear();

        try {
            startServer(ctx);
        } catch (Exception e) {
            callback.onError("数据端口没起来：" + e.getMessage());
            stop(ctx);
            return;
        }

        try {
            discoverySocket = new DatagramSocket(null);
            discoverySocket.setReuseAddress(true);
            discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(500);
        } catch (Exception e) {
            callback.onError("发现端口被占用，关掉其它同步软件再试");
            stop(ctx);
            return;
        }

        discovering = true;
        startDiscoveryLoop();
        startAnnounceLoop();

        MAIN.postDelayed(() -> finishDiscovery(callback), DISCOVERY_WINDOW_MS);
    }

    public void stop(Context ctx) {
        discovering = false;
        serving = false;
        activeCallback = null;

        if (discoverySocket != null) {
            try {
                discoverySocket.close();
            } catch (Exception ignored) {
            }
            discoverySocket = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
        if (discoveryThread != null) {
            discoveryThread.interrupt();
            discoveryThread = null;
        }
        if (announceThread != null) {
            announceThread.interrupt();
            announceThread = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        peers.clear();
    }

    private void finishDiscovery(PeerCallback callback) {
        if (!discovering) return;
        discovering = false;
        if (discoverySocket != null) {
            try {
                discoverySocket.close();
            } catch (Exception ignored) {
            }
            discoverySocket = null;
        }
        if (discoveryThread != null) {
            discoveryThread.interrupt();
            discoveryThread = null;
        }
        List<Peer> snap = snapshot();
        MAIN.post(() -> callback.onResult(snap));
    }

    /** 本机开一个临时 TCP 端口，收到对端推来的词库和快捷短语就合并进本地。 */
    private void startServer(Context ctx) throws Exception {
        if (serving) return;
        serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        dataPort = serverSocket.getLocalPort();
        serving = true;
        final Context app = ctx.getApplicationContext();
        serverThread = new Thread(() -> {
            while (serving) {
                try {
                    Socket socket = serverSocket.accept();
                    handleIncoming(app, socket);
                } catch (Exception e) {
                    if (serving) Log.w(TAG, "server stopped: " + e.getMessage());
                    break;
                }
            }
        }, "lan-sync-server");
        serverThread.start();
    }

    private void handleIncoming(Context app, Socket socket) {
        new Thread(() -> {
            try (Socket s = socket) {
                s.setSoTimeout(30_000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null) return;
                Map<String, String> payload = GSON.fromJson(line,
                        new TypeToken<Map<String, String>>() {
                        }.getType());
                if (payload == null) payload = new LinkedHashMap<>();

                OutputStream out = s.getOutputStream();
                String code = payload.remove("__pairCode");
                if (pairCode != null && !pairCode.equals(code)) {
                    writeAck(out, false, "code");
                    return;
                }

                String type = payload.remove("__type");
                String sender = payload.remove("__sender");
                if (!"offer".equals(type)) {
                    writeAck(out, false, "type");
                    return;
                }

                OfferCallback callback = offerCallback;
                if (callback == null) {
                    writeAck(out, false, "busy");
                    return;
                }

                List<String> groups = new ArrayList<>();
                try {
                    String groupsJson = payload.get("__groups");
                    if (groupsJson != null) {
                        groups = GSON.fromJson(groupsJson,
                                new TypeToken<List<String>>() {
                                }.getType());
                    }
                } catch (Exception ignored) {
                }
                if (groups == null) groups = new ArrayList<>();
                final Map<String, String> offerPayload = payload;
                Offer offer = new Offer(sender == null ? "对方设备" : sender, groups, offerPayload);

                CountDownLatch latch = new CountDownLatch(1);
                AtomicBoolean accepted = new AtomicBoolean(false);
                DecisionCallback decision = new DecisionCallback() {
                    @Override
                    public void accept() {
                        try {
                            com.minivoice.ime.settings.Prefs.importSyncPayload(app, offerPayload);
                            accepted.set(true);
                        } finally {
                            latch.countDown();
                        }
                    }

                    @Override
                    public void reject() {
                        accepted.set(false);
                        latch.countDown();
                    }
                };
                MAIN.post(() -> callback.onOffer(offer, decision));
                boolean answered = latch.await(30, TimeUnit.SECONDS);
                if (!answered) accepted.set(false);
                writeAck(out, accepted.get(), answered ? null : "timeout");
            } catch (Exception e) {
                Log.w(TAG, "incoming failed: " + e.getMessage());
            }
        }, "lan-sync-in").start();
    }

    private void writeAck(OutputStream out, boolean ok, String error) throws Exception {
        String json = ok
                ? "{\"ok\":true}\n"
                : "{\"ok\":false" + (error == null ? "" : ",\"error\":\"" + error + "\"") + "}\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void startDiscoveryLoop() {
        discoveryThread = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (discovering) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    discoverySocket.receive(packet);
                    handlePacket(packet);
                } catch (SocketTimeoutException ignored) {
                    // 每 500ms 醒一次，方便及时响应 stop
                } catch (Exception e) {
                    if (discovering) Log.w(TAG, "discovery stopped: " + e.getMessage());
                    break;
                }
            }
        }, "lan-sync-discover");
        discoveryThread.start();
    }

    private void startAnnounceLoop() {
        announceThread = new Thread(() -> {
            for (int i = 0; i < 12 && discovering; i++) {
                for (InetAddress address : broadcastAddresses()) {
                    sendAnnounce(address);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "lan-sync-announce");
        announceThread.start();
    }

    private void handlePacket(DatagramPacket datagram) {
        try {
            String raw = new String(datagram.getData(), datagram.getOffset(),
                    datagram.getLength(), StandardCharsets.UTF_8).trim();
            Packet packet = GSON.fromJson(raw, Packet.class);
            if (packet == null || !APP.equals(packet.app)) return;
            if (packet.id == null || packet.id.equals(deviceId)) return;
            if (packet.port <= 0) return;

            InetAddress from = datagram.getAddress();
            if (from == null) return;

            Peer peer = new Peer();
            peer.id = packet.id;
            peer.name = packet.name == null || packet.name.isEmpty() ? from.getHostAddress() : packet.name;
            peer.host = from.getHostAddress();
            peer.port = packet.port;
            peer.code = packet.code;

            boolean added = false;
            synchronized (peers) {
                for (Peer old : peers) {
                    if (old.id != null && old.id.equals(peer.id)) {
                        old.name = peer.name;
                        old.host = peer.host;
                        old.port = peer.port;
                        old.code = peer.code;
                        break;
                    }
                }
                if (!containsPeer(peer.id)) {
                    peers.add(peer);
                    added = true;
                }
            }

            if (added) {
                List<Peer> snap = snapshot();
                PeerCallback cb = activeCallback;
                if (cb != null) MAIN.post(() -> cb.onResult(snap));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad packet: " + e.getMessage());
        }
    }

    private boolean containsPeer(String id) {
        for (Peer p : peers) {
            if (p.id != null && p.id.equals(id)) return true;
        }
        return false;
    }

    private List<Peer> snapshot() {
        synchronized (peers) {
            return new ArrayList<>(peers);
        }
    }

    private void sendAnnounce(InetAddress address) {
        DatagramSocket socket = discoverySocket;
        if (socket == null || socket.isClosed()) return;
        try {
            Packet packet = new Packet();
            packet.app = APP;
            packet.type = "announce";
            packet.id = deviceId;
            packet.name = deviceName;
            packet.code = pairCode;
            packet.port = dataPort;
            packet.ts = System.currentTimeMillis();
            byte[] data = (GSON.toJson(packet) + "\n").getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, address, DISCOVERY_PORT));
        } catch (Exception e) {
            Log.w(TAG, "announce failed: " + e.getMessage());
        }
    }

    private List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();
                    InetAddress broadcast = ia.getBroadcast();
                    if (address instanceof Inet4Address && broadcast != null
                            && !address.isLoopbackAddress()) {
                        out.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 把本机词库和快捷短语推到指定设备，对端收到后合并。 */
    public void push(Peer peer, Map<String, String> payload, PushCallback callback) {
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(peer.host, peer.port), 5000);
                socket.setSoTimeout(5000);
                Map<String, String> body = new LinkedHashMap<>(payload);
                body.put("__type", "offer");
                body.put("__sender", deviceName);
                if (peer.code != null && !peer.code.isEmpty()) {
                    body.put("__pairCode", peer.code);
                }
                OutputStream out = socket.getOutputStream();
                out.write((GSON.toJson(body) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String ack = reader.readLine();
                boolean ok = ack != null && ack.contains("\"ok\":true");
                MAIN.post(() -> callback.onDone(ok, ok ? "同步完成" : "对端没有确认"));
            } catch (Exception e) {
                MAIN.post(() -> callback.onDone(false, "同步失败：" + e.getMessage()));
            }
        }, "lan-sync-push").start();
    }

    public static Map<String, String> emptyPayload() {
        return new LinkedHashMap<>();
    }
}
