package com.fantest.wifhub;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Best-effort discovery; local LAN multicast/broadcast and reachable MagicDNS seeds.
 *  Tailscale VPN apps do not grant arbitrary Android apps a complete peer-list API.
 *  Once a single gateway responds, its public read-only /v1/discover provides the peer list.
 *  No ADB controls are available until explicit per-host enrollment.
 */
final class DiscoveryEngine {
    private static final int PORT = 42424;
    private static final int BEACON_PORT = 42426;
    private static final String DISCOVERY = "WIFIHUB_DISCOVER_V1";
    private static final String[] COMMON_HOSTS = {
        "wifi-hub", "wifihub", "gateway", "master-pc", "master-pc-1", "agentbox"
    };

    static final class Result {
        final JSONArray peers = new JSONArray();
        String source = "";
        String note = "";
    }
    private static final class Seed {
        final String host;
        final int port;
        final String token;
        Seed(String h, int p, String t) { host=h; port=p; token=t; }
    }

    static Result scan(Context context, JSONArray stored) {
        Result result = new Result();
        LinkedHashMap<String, Seed> seeds = new LinkedHashMap<>();
        addSeed(seeds, "127.0.0.1", 42425, "");
        for (int i=0; i<stored.length(); i++) {
            JSONObject h=stored.optJSONObject(i);
            if (h!=null) addSeed(seeds,h.optString("ip"),h.optInt("port",PORT),h.optString("token"));
        }

        // Tailscale's MagicDNS can resolve known peer names when enabled. These are
        // guesses, not a guarantee of whole-tailnet enumeration.
        for (String hostname: COMMON_HOSTS) addSeed(seeds, hostname, PORT, "");
        String searchSuffix = dnsSearchSuffix(context);
        if (!searchSuffix.isEmpty()) for (String hostname: COMMON_HOSTS)
            addSeed(seeds, hostname + "." + searchSuffix, PORT, "");

        for (Seed s: discoverLan()) addSeed(seeds,s.host,s.port,"");

        ExecutorService pool=Executors.newFixedThreadPool(9);
        List<Future<JSONObject>> jobs=new ArrayList<>();
        try {
            for (Seed seed: seeds.values()) jobs.add(pool.submit(() -> query(seed)));
            Map<String, JSONObject> peers=new LinkedHashMap<>();
            int reached=0;
            for (Future<JSONObject> job: jobs) {
                try {
                    JSONObject data=job.get(4,TimeUnit.SECONDS);
                    if (data==null || !data.optBoolean("ok")) continue;
                    reached++;
                    if (result.source.isEmpty()) result.source=data.optString("_via");
                    JSONObject self=data.optJSONObject("self");
                    if(self!=null) upsert(peers,self);
                    JSONArray list=data.optJSONArray("peers");
                    if(list!=null) for(int i=0;i<list.length();i++) {
                        JSONObject item=list.optJSONObject(i);
                        if(item!=null) upsert(peers,item);
                    }
                } catch (Exception ignored) { }
            }
            // Probe gateway status for each peer, including those without the gateway app.
            List<Future<JSONObject>> checks=new ArrayList<>();
            List<JSONObject> targets=new ArrayList<>(peers.values());
            for(JSONObject node: targets) checks.add(pool.submit(() -> {
                String ip=node.optString("ip");
                JSONObject gateway=get("http://" + ip + ":" + PORT + "/v1/info", "", 850);
                JSONObject update=new JSONObject();
                try {
                    update.put("ip",ip);
                    update.put("gateway",gateway!=null && gateway.optBoolean("ok"));
                    if (gateway!=null && gateway.optBoolean("ok"))
                        update.put("gatewayName",gateway.optString("name"));
                } catch(Exception ignored) {}
                return update;
            }));
            for(int i=0;i<checks.size();i++) {
                try {
                    JSONObject probe=checks.get(i).get(2,TimeUnit.SECONDS);
                    targets.get(i).put("gateway",probe.optBoolean("gateway"));
                } catch (Exception ignored) {
                    try { targets.get(i).put("gateway",false); } catch(Exception ignored2) {}
                }
            }
            for(JSONObject node: peers.values()) result.peers.put(node);
            if (reached>0) result.note="Discovered " + peers.size() +
                    " Tailscale machines/devices via " + reached + " gateway(s).";
            else result.note="No gateway found automatically. Connect Tailscale and install a WiFi HUB gateway on a reachable machine; MagicDNS or same-LAN discovery is required for first contact.";
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    private static String dnsSearchSuffix(Context context) {
        try {
            ConnectivityManager cm=(ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null) return "";
            for(Network n:cm.getAllNetworks()) {
                LinkProperties lp=cm.getLinkProperties(n);
                if(lp==null) continue;
                String domain=lp.getDomains();
                if(domain==null) continue;
                for(String segment:domain.split("\\s+")) {
                    if(segment.endsWith(".ts.net")) return segment;
                }
            }
        } catch(Exception ignored) {}
        return "";
    }

    private static void addSeed(Map<String,Seed> seeds, String host, int port, String token) {
        if(host==null || host.isEmpty() || host.length()>253 || port<1 || port>65535) return;
        String key=host+":"+port;
        // Prefer an existing enrolled gateway's token over an anonymous candidate.
        Seed old=seeds.get(key);
        if (old==null || (old.token.isEmpty() && !token.isEmpty()))
            seeds.put(key,new Seed(host,port,token));
    }

    private static List<Seed> discoverLan() {
        LinkedHashMap<String,Seed> found=new LinkedHashMap<>();
        try(DatagramSocket socket=new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(380);
            byte[] payload=DISCOVERY.getBytes(StandardCharsets.UTF_8);
            Set<String> broadcast=new LinkedHashSet<>();
            broadcast.add("255.255.255.255");
            for(NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!ni.isUp() || ni.isLoopback()) continue;
                for(InterfaceAddress addr:ni.getInterfaceAddresses())
                    if(addr.getBroadcast()!=null) broadcast.add(addr.getBroadcast().getHostAddress());
            }
            for(String host:broadcast) {
                try {
                    socket.send(new DatagramPacket(payload,payload.length,
                            InetAddress.getByName(host),BEACON_PORT));
                } catch(Exception ignored) {}
            }
            long deadline=System.currentTimeMillis()+1450;
            while(System.currentTimeMillis()<deadline) {
                byte[] b=new byte[1024];
                DatagramPacket packet=new DatagramPacket(b,b.length);
                try {
                    socket.receive(packet);
                    JSONObject reply=new JSONObject(new String(packet.getData(),packet.getOffset(),
                            packet.getLength(),StandardCharsets.UTF_8));
                    if (!"wifi-hub-gateway".equals(reply.optString("kind"))) continue;
                    String ip=reply.optString("ip");
                    if(ip.startsWith("100.")) found.put(ip,new Seed(ip,reply.optInt("port",PORT),""));
                } catch(SocketTimeoutException ignored) { }
                  catch(Exception ignored) { }
            }
        } catch(Exception ignored) {}
        return new ArrayList<>(found.values());
    }

    private static JSONObject query(Seed seed) {
        String base="http://" + seed.host + ":" + seed.port;
        JSONObject data=get(base+"/v1/discover","",1150);
        if(data==null || !data.optBoolean("ok")) {
            if(seed.token.isEmpty()) return null;
            data=get(base+"/v1/peers",seed.token,1700);
        }
        if(data!=null && data.optBoolean("ok")) try { data.put("_via",seed.host); } catch(Exception ignored){}
        return data;
    }
    private static JSONObject get(String url,String token,int timeout) {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(timeout);c.setReadTimeout(timeout);
            c.setRequestProperty("Accept","application/json");
            if(!token.isEmpty()) c.setRequestProperty("Authorization","Bearer "+token);
            if(c.getResponseCode()!=200) return null;
            try(InputStream stream=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] b=new byte[2048];int n;
                while((n=stream.read(b))>=0 && bytes.size()<160000) bytes.write(b,0,n);
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } catch(Exception ignored) {return null;}
        finally {if(c!=null)c.disconnect();}
    }
    private static void upsert(Map<String,JSONObject> peers,JSONObject node) {
        String ip=node.optString("ip");
        if(!ip.startsWith("100.") || ip.length()>80) return;
        JSONObject old=peers.get(ip);
        if(old==null || node.optString("name").length()>old.optString("name").length())
            peers.put(ip,node);
    }
}
