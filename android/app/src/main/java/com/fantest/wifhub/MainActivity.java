package com.fantest.wifhub;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xff0c1424, PANEL = 0xff17243a, BORDER = 0xff2e3e59;
    private static final int WHITE = 0xfff1f6ff, MUTED = 0xffa3b3c9, ACCENT = 0xff37cdb8;
    private static final int BLUE = 0xff4f8df5, RED = 0xfffc7586;
    private final ExecutorService io = Executors.newFixedThreadPool(6);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private JSONArray hosts = new JSONArray();
    private int selected = -1;
    private LinearLayout base, hostCards, discoveredCards, deviceCards, serviceCards;
    private ScrollView pageScroll;
    private View[] pageSections;
    private Button[] tabButtons;
    private int activeTab = 0;
    private TextView banner, currentHost, helperInfo;
    private EditText hostIp, hostPort, hostCode, hostName, targetIp, pairPort, pairCode, connectPort;
    private boolean alive = true;

    interface Receive { void done(JSONObject data); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        try {
            hosts = new JSONArray(getPreferences(0).getString("hosts", "[]"));
            selected = getPreferences(0).getInt("selected", -1);
            if (selected >= hosts.length()) selected = -1;
        } catch (Exception e) { hosts = new JSONArray(); selected = -1; }
        layout();
        drawHosts();
        if (selected >= 0) refresh();
    }

    int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    TextView text(String label, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(label); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    LinearLayout vertical() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c;
    }
    void put(LinearLayout to, View child, int marginBottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(marginBottom); to.addView(child, lp);
    }
    void space(LinearLayout c, int px) { View v = new View(this); c.addView(v, new LinearLayout.LayoutParams(1, dp(px))); }
    LinearLayout card(LinearLayout parent, String title, String subtitle) {
        LinearLayout c = vertical(); c.setPadding(dp(17), dp(16), dp(17), dp(16));
        c.setBackground(shape(PANEL, 18, BORDER)); put(parent, c, 14);
        put(c, text(title, 17, WHITE, true), 6);
        if (subtitle != null) put(c, text(subtitle, 12, MUTED, false), 12);
        return c;
    }
    EditText input(LinearLayout container, String hint, String sample, int type) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(15); e.setTextColor(WHITE);
        e.setHintTextColor(MUTED); e.setHint(hint);
        e.setPadding(dp(13), dp(11), dp(13), dp(11)); e.setBackground(shape(0xff101d30, 11, BORDER));
        if (type != 0) e.setInputType(type);
        if (sample != null) e.setText(sample);
        put(container, e, 9); return e;
    }
    Button button(String title, boolean filled, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(filled ? BG : WHITE);
        b.setBackground(shape(filled ? ACCENT : 0xff243750, 12, filled ? 0 : BORDER));
        b.setPadding(dp(11), dp(7), dp(11), dp(7)); b.setOnClickListener(listener); return b;
    }
    void action(LinearLayout target, String title, boolean filled, View.OnClickListener onClick) {
        put(target, button(title, filled, onClick), 9);
    }
    void show(String message) {
        banner.setText(message);
        banner.setTextColor(message.startsWith("Error") ? RED : ACCENT);
    }
    void layout() {
        ScrollView scroll = new ScrollView(this); pageScroll = scroll; scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        base = vertical(); base.setPadding(dp(18), dp(16), dp(18), dp(32));
        scroll.addView(base);
        scroll.setClipToPadding(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            scroll.setPadding(0, top, 0, bottom);
            return insets;
        });
        setContentView(scroll);

        TextView logo = text("WiF  HUB", 31, WHITE, true); put(base, logo, 5);
        put(base, text("Private ADB command center • Tailscale", 13, MUTED, false), 14);
        banner = text("Select a gateway or enroll a new computer.", 13, ACCENT, false);
        banner.setPadding(dp(12), dp(12), dp(12), dp(12));
        banner.setBackground(shape(0xff13333b, 10, 0xff23595a)); put(base, banner, 15);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(shape(PANEL, 14, BORDER));
        String[] tabNames = {"Hosts", "Devices", "Pair", "Helper"};
        tabButtons = new Button[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            final int tab = i;
            Button b = button(tabNames[i], false, v -> showTab(tab));
            b.setTextSize(12);
            b.setMinimumHeight(dp(43));
            b.setMinHeight(dp(43));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, dp(44), 1);
            item.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(b, item);
            tabButtons[i] = b;
        }
        put(base, tabs, 15);

        LinearLayout gateway = card(base, "01   Gateways", "Manage multiple Windows and Linux computers.");
        currentHost = text("No gateway selected", 13, MUTED, true); put(gateway, currentHost, 10);
        hostCards = vertical(); put(gateway, hostCards, 4);
        action(gateway, "Refresh selected gateway", true, v -> refresh());
        action(gateway, "Discover tailnet gateways", false, v -> discover());
        discoveredCards = vertical(); put(gateway, discoveredCards, 4);
        action(gateway, "Reconnect offline ADB devices", false, v -> postSelected("/v1/reconnect", new JSONObject(), true));

        LinearLayout enroll = card(base, "02   Enroll a computer",
                "Obtain the six-digit approval code from the gateway's terminal.");
        hostName = input(enroll, "Display name (optional)", "", 0);
        hostIp = input(enroll, "Computer Tailscale IP (100.x.x.x)", "", 0);
        hostPort = input(enroll, "Gateway port", "42424", 2);
        hostCode = input(enroll, "Six-digit host approval code", "", 2);
        action(enroll, "Test gateway reachability", false, v -> testGateway());
        action(enroll, "Connect and enroll", true, v -> enrollHost());

        LinearLayout dev = card(base, "03   ADB device inventory",
                "Live state from the selected computer, not from this phone.");
        deviceCards = vertical(); put(dev, deviceCards, 4);
        action(dev, "Scan devices + mDNS services", true, v -> refresh());
        serviceCards = vertical(); put(dev, serviceCards, 3);

        LinearLayout pair = card(base, "04   Pair a phone",
                "Open Wireless debugging → Pair with pairing code on the target phone.");
        targetIp = input(pair, "Target phone IP (Wi-Fi or Tailscale)", "", 0);
        pairPort = input(pair, "Pairing port from pairing dialog", "", 2);
        pairCode = input(pair, "Six-digit Android pairing code", "", 2);
        action(pair, "Check pairing endpoint", false, v -> probePhone(pairPort));
        action(pair, "Pair through selected PC", true, v -> pairPhone());
        connectPort = input(pair, "Connection port (different from pairing port)", "", 2);
        action(pair, "Check connection endpoint", false, v -> probePhone(connectPort));
        action(pair, "Connect ADB using connection port", false, v -> connectPhone());
        action(pair, "Disconnect ADB at this address", false, v -> disconnectPhone());

        LinearLayout helper = card(base, "05   Phone setup helper",
                "Android requires approval before Wireless debugging can be enabled.");
        helperInfo = text("Inspecting Tailscale address…", 13, MUTED, false); put(helper, helperInfo, 10);
        action(helper, "Open Android developer settings", true, v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Exception ex) { show("Error opening developer settings: " + ex.getMessage()); }
        });
        action(helper, "Copy this phone's Tailscale IP", false, v -> {
            String ip = localTailIp();
            if (ip == null) { show("Error: Tailscale IP is not available. Connect Tailscale."); return; }
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("WiF HUB Tailscale IP", ip));
            show("Copied: " + ip);
        });
        put(helper, text("Secure pairing and USB authorization cannot be forced on an unprivileged Android device. A previously ADB-authorized host can request TCP/IP mode.", 12, MUTED, false), 0);
        helperInfo.setText("Phone tailnet IP: " + (localTailIp() == null ? "not detected" : localTailIp()));
        action(helper, "Open Tailscale app", false, v -> {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
                if (launch == null) {
                    show("Error: Tailscale app is not installed or visible on this phone.");
                } else startActivity(launch);
            } catch (Exception ex) { show("Error opening Tailscale: " + ex.getMessage()); }
        });
        put(base, text("WiF HUB v0.1.3  •  Private gateways  •  No cloud hosting", 11, MUTED, false), 10);
        pageSections = new View[]{gateway, enroll, dev, pair, helper};
        showTab(0);
    }

    void showTab(int tab) {
        if (pageSections == null || tabButtons == null || tab < 0 || tab > 3) return;
        activeTab = tab;
        for (int i = 0; i < pageSections.length; i++) {
            int owner = i == 0 || i == 1 ? 0 : i - 1;
            pageSections[i].setVisibility(owner == tab ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < tabButtons.length; i++) {
            boolean on = i == tab;
            tabButtons[i].setTextColor(on ? BG : MUTED);
            tabButtons[i].setBackground(shape(on ? ACCENT : PANEL, 11, on ? 0 : BORDER));
        }
        if (pageScroll != null) pageScroll.post(() -> pageScroll.smoothScrollTo(0, 0));
    }

    String localTailIp() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp()) continue;
                for (InetAddress a : Collections.list(network.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress(); int one = b[0] & 255, two = b[1] & 255;
                    if (one == 100 && two >= 64 && two < 128) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
    void persist() {
        getPreferences(0).edit().putString("hosts", hosts.toString()).putInt("selected", selected).apply();
    }
    JSONObject chosen() { return selected >= 0 && selected < hosts.length() ? hosts.optJSONObject(selected) : null; }
    String value(EditText e) { return e.getText().toString().trim(); }
    int port(EditText e) {
        try { int p = Integer.parseInt(value(e)); return p > 0 && p <= 65535 ? p : -1; }
        catch (Exception x) { return -1; }
    }
    void drawHosts() {
        if (hostCards == null) return;
        hostCards.removeAllViews();
        if (hosts.length() == 0) put(hostCards, text("No enrolled gateways yet.", 12, MUTED, false), 9);
        for (int i = 0; i < hosts.length(); i++) {
            final int index = i; JSONObject h = hosts.optJSONObject(i); if (h == null) continue;
            String title = (index == selected ? "●  " : "○  ") + h.optString("name", "Gateway");
            String info = h.optString("ip") + ":" + h.optInt("port", 42424);
            LinearLayout row = vertical();
            row.setPadding(dp(10), dp(9), dp(10), dp(6));
            row.setBackground(shape(index == selected ? 0xff174b50 : 0xff142036, 12, index == selected ? ACCENT : BORDER));
            put(row, text(title, 15, WHITE, true), 3);
            put(row, text(info, 12, MUTED, false), 8);
            action(row, index == selected ? "Selected • refresh" : "Switch to this computer", false, v -> {
                selected = index; persist(); drawHosts(); refresh();
            });
            action(row, "Remove gateway from this phone", false, v -> {
                JSONArray changed = new JSONArray();
                for (int j = 0; j < hosts.length(); j++) if (j != index) changed.put(hosts.opt(j));
                hosts = changed;
                if (selected == index) selected = -1; else if (selected > index) selected--;
                persist(); drawHosts(); deviceCards.removeAllViews(); serviceCards.removeAllViews();
                show("Gateway removed locally.");
            });
            put(hostCards, row, 10);
        }
        JSONObject current = chosen();
        currentHost.setText(current == null ? "No gateway selected" :
                "Active: " + current.optString("name") + " • " + current.optString("ip"));
    }
    void request(String ip, int port, String token, String path, JSONObject data, Receive receive) {
        io.execute(() -> {
            JSONObject result;
            try {
                URL u = new URL("http", ip, port, path);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(3500); c.setReadTimeout(8000); c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                if (data != null) {
                    c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
                    try (OutputStream out = c.getOutputStream()) { out.write(body); }
                }
                int code = c.getResponseCode();
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (stream != null) try (InputStream in = stream) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1 && out.size() < 102400) out.write(buf, 0, n);
                }
                result = new JSONObject(out.toString("UTF-8"));
                result.put("_http", code);
                c.disconnect();
            } catch (Exception ex) {
                result = new JSONObject();
                try { result.put("ok", false).put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage()); }
                catch (Exception ignored) {}
            }
            final JSONObject finalResult = result;
            ui.post(() -> { if (alive) receive.done(finalResult); });
        });
    }
    void requestSelected(String path, JSONObject data, Receive receive) {
        JSONObject h = chosen();
        if (h == null) { show("Error: Choose or enroll a computer first."); return; }
        request(h.optString("ip"), h.optInt("port", 42424), h.optString("token"), path, data, receive);
    }
    void postSelected(String path, JSONObject body, boolean refreshAfter) {
        requestSelected(path, body, response -> {
            show((response.optBoolean("ok") ? "Success: " : "Error: ") +
                    response.optString("output", response.optString("error", "No response")).replace("\n", "  "));
            if (refreshAfter) refresh();
        });
    }
    void testGateway() {
        String ip = value(hostIp); int p = port(hostPort);
        if (!validIp(ip) || p < 1) { show("Error: Enter a numeric Tailscale IP and gateway port."); return; }
        show("Testing gateway " + ip + ":" + p + "…");
        request(ip, p, null, "/v1/info", null, response -> {
            if (response.optBoolean("ok"))
                show("Gateway reachable: " + response.optString("name") + " (" + response.optString("platform") + "). Enter its approval code.");
            else
                show("Error: Gateway unreachable: " + response.optString("error", "Check Tailscale ACL/firewall and port."));
        });
    }

    void probePhone(EditText field) {
        String ip = value(targetIp); int p = port(field);
        if (!validIp(ip) || p < 1) { show("Error: Enter phone IP and active Android debugging port."); return; }
        try {
            show("Checking ADB endpoint through selected computer…");
            requestSelected("/v1/probe", new JSONObject().put("host", ip).put("port", p), response ->
                show((response.optBoolean("ok") ? "Reachable: " : "Error: ") +
                     response.optString("output", response.optString("error", "No response"))));
        } catch (Exception ex) { show("Error: " + ex.getMessage()); }
    }

    void enrollHost() {
        String ip = value(hostIp), code = value(hostCode), name = value(hostName);
        int p = port(hostPort);
        if (!validIp(ip) || p < 1 || !code.matches("[0-9]{6}")) {
            show("Error: Enter valid numeric IP, port and six-digit host code."); return;
        }
        show("Connecting to " + ip + "…");
        try {
            JSONObject payload = new JSONObject().put("code", code).put("client", android.os.Build.MODEL);
            request(ip, p, null, "/v1/enroll", payload, response -> {
                String token = response.optString("token");
                if (!response.optBoolean("ok") || token.isEmpty()) {
                    show("Error: " + response.optString("error", "Enrollment failed")); return;
                }
                try {
                    JSONObject newHost = new JSONObject().put("ip", ip).put("port", p).put("token", token)
                            .put("name", name.isEmpty() ? response.optString("name", ip) : name);
                    int existing = -1;
                    for (int i = 0; i < hosts.length(); i++)
                        if (ip.equals(hosts.optJSONObject(i).optString("ip")) && p == hosts.optJSONObject(i).optInt("port")) existing = i;
                    if (existing >= 0) { hosts.put(existing, newHost); selected = existing; }
                    else { hosts.put(newHost); selected = hosts.length() - 1; }
                    persist(); hostCode.setText(""); drawHosts();
                    show("Gateway enrolled and selected: " + ip); refresh();
                } catch (Exception e) { show("Error: " + e.getMessage()); }
            });
        } catch (Exception ex) { show("Error: " + ex.getMessage()); }
    }
    boolean validIp(String ip) {
        if (ip.isEmpty() || ip.contains("/") || ip.contains(" ") || ip.length() > 80) return false;
        if (ip.contains(":")) return ip.matches("[0-9a-fA-F:]+");
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return false;
        try { for (String part : octets) { int n = Integer.parseInt(part); if (n < 0 || n > 255) return false; } return true; }
        catch (Exception e) { return false; }
    }
    void refresh() {
        if (chosen() == null) { show("Error: No gateway selected."); return; }
        show("Checking " + chosen().optString("name") + "…");
        requestSelected("/v1/state", null, response -> {
            if (!response.optBoolean("ok")) { show("Error: " + response.optString("error", "Unreachable")); return; }
            JSONArray list = response.optJSONObject("adb") == null ? null :
                    response.optJSONObject("adb").optJSONArray("devices");
            deviceCards.removeAllViews(); serviceCards.removeAllViews();
            if (list == null || list.length() == 0)
                put(deviceCards, text("No ADB devices connected on this computer.", 13, MUTED, false), 12);
            else for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.optJSONObject(i); if (d == null) continue;
                String serial = d.optString("serial");
                String state = d.optString("state");
                LinearLayout c = vertical(); c.setPadding(dp(11), dp(10), dp(11), dp(7));
                c.setBackground(shape(0xff111f34, 10, BORDER));
                put(c, text((state.equals("device") ? "●  " : "○  ") + serial, 14, state.equals("device") ? ACCENT : RED, true), 4);
                put(c, text(state.toUpperCase() + "  " + d.optString("details"), 11, MUTED, false), 9);
                if (state.equals("device")) {
                    action(c, "Request Wireless debugging ON", false, v -> {
                        try { postSelected("/v1/wifi-enable", new JSONObject().put("serial", serial), true); }
                        catch (Exception ex) { show("Error: " + ex.getMessage()); }
                    });
                    action(c, "Request TCP/IP on port 5555", false, v -> {
                        try { postSelected("/v1/tcpip", new JSONObject().put("serial", serial), true); }
                        catch (Exception ex) { show("Error: " + ex.getMessage()); }
                    });
                }
                put(deviceCards, c, 9);
            }
            JSONObject mdns = response.optJSONObject("mdns");
            JSONArray services = mdns == null ? null : mdns.optJSONArray("services");
            if (services != null && services.length() > 0) {
                put(serviceCards, text("Nearby Android ADB advertisements", 13, WHITE, true), 8);
                for (int i = 0; i < services.length(); i++) {
                    JSONObject s = services.optJSONObject(i); if (s == null) continue;
                    String ip = s.optString("host");
                    int sp = s.optInt("port");
                    LinearLayout c = vertical(); c.setPadding(dp(8), dp(8), dp(8), dp(8));
                    c.setBackground(shape(0xff142b3c, 9, BORDER));
                    put(c, text(s.optString("kind") + "  •  " + ip + ":" + sp, 12, WHITE, true), 6);
                    action(c, "Use this endpoint", false, v -> {
                        targetIp.setText(ip);
                        if ("pair".equals(s.optString("kind"))) pairPort.setText(String.valueOf(sp));
                        else connectPort.setText(String.valueOf(sp));
                        show("Endpoint copied into pairing / connection form.");
                        showTab(2);
                    });
                    put(serviceCards, c, 8);
                }
            }
            show("Connected: " + response.optString("host") + " • " + (list == null ? 0 : list.length()) + " ADB entries.");
        });
    }
    void discover() {
        if (chosen() == null) { show("Error: Enroll a gateway to discover its tailnet peers."); return; }
        show("Reading Tailscale peer inventory…");
        requestSelected("/v1/peers", null, response -> {
            JSONArray peers = response.optJSONArray("peers");
            if (peers == null) { show("Error: " + response.optString("error", "No peer listing")); return; }
            discoveredCards.removeAllViews();
            put(discoveredCards, text("Tailnet peers • choose a phone or check for gateways", 12, MUTED, true), 7);
            int shown = 0;
            for (int i = 0; i < peers.length(); i++) {
                JSONObject peer = peers.optJSONObject(i);
                if (peer == null || !peer.optBoolean("online", true)) continue;
                String ip = peer.optString("ip"), host = peer.optString("name"), os = peer.optString("os", "");
                if (!validIp(ip)) continue;
                shown++;
                LinearLayout row = vertical(); row.setPadding(dp(9), dp(9), dp(9), dp(6));
                row.setBackground(shape(0xff182e3e, 10, BORDER));
                put(row, text(host + " • " + os, 13, WHITE, true), 4);
                put(row, text(ip, 12, MUTED, false), 8);
                action(row, "Use IP as Android target", false, v -> {
                    targetIp.setText(ip);
                    show("Target IP selected: " + host + ". Enter its active ADB pairing/connection port.");
                });
                TextView gatewayState = text("Checking for WiF HUB gateway…", 11, MUTED, false);
                put(row, gatewayState, 6);
                put(discoveredCards, row, 8);
                request(ip, 42424, null, "/v1/info", null, check -> {
                    if (!check.optBoolean("ok")) { gatewayState.setText("Gateway not responding"); return; }
                    gatewayState.setText("WiF HUB gateway available");
                    action(row, "Prepare gateway enrollment", false, v -> {
                        hostIp.setText(ip); hostName.setText(host); hostPort.setText("42424");
                        show("Found gateway " + host + ". Enter its computer approval code.");
                    });
                });
            }
            show("Found " + shown + " online tailnet peers. Probing gateways.");
        });
    }
    void pairPhone() {
        String ip = value(targetIp), code = value(pairCode); int p = port(pairPort);
        if (!validIp(ip) || p < 1 || !code.matches("[0-9]{6}")) {
            show("Error: Use valid phone IP, pairing port and six-digit pairing code."); return;
        }
        try { postSelected("/v1/pair", new JSONObject().put("host", ip).put("port", p).put("code", code), true); }
        catch (Exception e) { show("Error: " + e.getMessage()); }
    }
    void connectPhone() {
        String ip = value(targetIp); int p = port(connectPort);
        if (!validIp(ip) || p < 1) { show("Error: Enter phone IP and wireless debugging connection port."); return; }
        try { postSelected("/v1/connect", new JSONObject().put("host", ip).put("port", p), true); }
        catch (Exception e) { show("Error: " + e.getMessage()); }
    }
    void disconnectPhone() {
        String ip = value(targetIp); int p = port(connectPort);
        if (!validIp(ip) || p < 1) { show("Error: Enter phone IP and ADB connection port."); return; }
        try { postSelected("/v1/disconnect", new JSONObject().put("host", ip).put("port", p), true); }
        catch (Exception e) { show("Error: " + e.getMessage()); }
    }
    @Override protected void onDestroy() {
        alive = false; io.shutdownNow(); super.onDestroy();
    }
}
