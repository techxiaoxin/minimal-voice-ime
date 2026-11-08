package com.minivoice.ime.sync;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网内的点对点偏好同步。用 NSD 发现同网设备，TCP 直连互推 KB 级 JSON。
 * 不传语音，不传转写原文，也没有任何公网中转。
 */
public class LanSyncManager {

    public static final String SERVICE_TYPE = "_minivoice._tcp.";
    public static final int PORT = 8988;

    private static final String TAG = "LanSync";
    private static final Gson GSON = new Gson();

    public interface PeerCallback {
        void onResult(List<Peer> peers);

        void onError(String message);
    }

    public interface PushCallback {
        void onDone(boolean ok, String message);
    }

    public static class Peer {
        public String name;
        public String host;
        public int port;

        @Override
        public String toString() {
            return name + "  " + host + ":" + port;
        }
    }

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discovery;
    private android.net.wifi.WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener registration;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean serving = false;

    public void startAdvertising(Context ctx, String deviceName) {
        if (nsdManager != null) return;
        nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return;

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(deviceName == null ? Build.MODEL : deviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);

        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "service registered: " + serviceInfo.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        // 安卓的 mDNS 发现要拿组播锁，不然像小米、荣耀这类机型会一台都搜不到
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicast = wm.createMulticastLock("minivoice-lan");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Throwable ignored) {
        }
        startServer(ctx);
    }

    public void stop(Context ctx) {
        serving = false;
        if (multicast != null) {
            try {
                if (multicast.isHeld()) multicast.release();
            } catch (Throwable ignored) {
            }
            multicast = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
        if (nsdManager != null) stopDiscovery(nsdManager);
        if (nsdManager != null && registration != null) {
            try {
                nsdManager.unregisterService(registration);
            } catch (Exception ignored) {
            }
            registration = null;
            nsdManager = null;
        }
    }

    /** 本机开一个 TCP 端口，收到对端推来的偏好就合并进本地。 */
    private void startServer(Context ctx) {
        if (serving) return;
        serving = true;
        final Context app = ctx.getApplicationContext();
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (serving) {
                    Socket socket = serverSocket.accept();
                    handleIncoming(app, socket);
                }
            } catch (Exception e) {
                Log.w(TAG, "server stopped: " + e.getMessage());
            }
        }, "lan-sync-server");
        serverThread.start();
    }

    private void handleIncoming(Context app, Socket socket) {
        new Thread(() -> {
            try (Socket s = socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) return;
                Map<String, String> payload = GSON.fromJson(line,
                        new com.google.gson.reflect.TypeToken<Map<String, String>>() {
                        }.getType());
                com.minivoice.ime.settings.Prefs.importSyncPayload(app, payload);
                OutputStream out = s.getOutputStream();
                out.write("{\"ok\":true}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "incoming failed: " + e.getMessage());
            }
        }, "lan-sync-in").start();
    }

    private void stopDiscovery(NsdManager manager) {
        if (discovery == null) return;
        try {
            manager.stopServiceDiscovery(discovery);
        } catch (Exception ignored) {
        }
        discovery = null;
    }

    /** 扫一遍同 WiFi 下的其它设备。 */
    public void discover(Context ctx, PeerCallback callback) {
        NsdManager manager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            callback.onError("系统没有 NSD 服务");
            return;
        }
        stopDiscovery(manager);

        final List<Peer> peers = new ArrayList<>();
        final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];

        holder[0] = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!serviceInfo.getServiceType().contains("minivoice")) return;
                resolve(manager, serviceInfo, peer -> {
                    if (peer != null) {
                        for (Peer p : peers) {
                            if (p.host.equals(peer.host)) return;
                        }
                        peers.add(peer);
                        List<Peer> snap = new ArrayList<>(peers);
                        MAIN.post(() -> callback.onResult(snap));
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                MAIN.post(() -> callback.onError("启动发现失败：" + errorCode));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };

        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, holder[0]);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                manager.stopServiceDiscovery(holder[0]);
            } catch (Exception ignored) {
            }
            if (peers.isEmpty()) {
                List<Peer> snap = new ArrayList<>(peers);
                MAIN.post(() -> callback.onResult(snap));
            }
        }, 4000);
        discovery = holder[0];
    }

    private interface ResolveCallback {
        void onResolved(Peer peer);
    }

    @SuppressWarnings("deprecation")
    private void resolve(NsdManager manager, NsdServiceInfo info, ResolveCallback cb) {
        manager.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                cb.onResolved(null);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                InetAddress addr = serviceInfo.getHost();
                if (addr == null) {
                    cb.onResolved(null);
                    return;
                }
                Peer p = new Peer();
                p.name = serviceInfo.getServiceName();
                p.host = addr.getHostAddress();
                p.port = serviceInfo.getPort();
                cb.onResolved(p);
            }
        });
    }

    /** 把本机偏好推到指定设备，对端收到后合并。 */
    public void push(Peer peer, Map<String, String> payload, PushCallback callback) {
        new Thread(() -> {
            try (Socket socket = new Socket(peer.host, peer.port)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                out.write((GSON.toJson(payload) + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String ack = reader.readLine();
                boolean ok = ack != null && ack.contains("true");
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
