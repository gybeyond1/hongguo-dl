package com.hongguo.dl;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String UA = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final String HG_API = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String WEB_UA = "Mozilla/5.0 (Linux; Android 16; 25053RT47C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static String hgDeviceId = null;
    private static String hgInstallId = null;
    private static final String[] HG_QUERY_KEYS = {
        "aid","app_name","version_code","version_name","manifest_version_code","update_version_code",
        "channel","device_platform","os","ssmix","device_type","device_brand","language","os_api",
        "os_version","resolution","dpi","ac","device_id","iid"
    };
    private static final String[] HG_QUERY_VALS = {
        "8662","novelread","73532","7.3.5.32","73532","73532","update_64",
        "android","android","a","25053RT47C","Redmi","zh","36","16",
        "1280*2772","520","wifi"
    };
    private WebView webView;
    private SharedPreferences prefs;
    private volatile String authToken = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String pendingShareText = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
        webView.setWebChromeClient(new android.webkit.WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pushSettings();
                if (pendingShareText != null) {
                    final String txt = pendingShareText;
                    pendingShareText = null;
                    webView.postDelayed(() -> webView.evaluateJavascript(
                        "window.onSharedText && onSharedText('" +
                            txt.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") +
                        "')", null), 500);
                }
            }
        });

        checkStoragePermission();
        handleIntent(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) pendingShareText = text;
        }
    }

    private void pushSettings() {
        String host = prefs.getString("cloud_host", "");
        String port = prefs.getString("cloud_port", "8800");
        String pwd = prefs.getString("cloud_password", "");
        webView.evaluateJavascript(
            "window.onSettingsLoaded && onSettingsLoaded(" +
                "'" + host + "','" + port + "','" + pwd + "'" +
            ")", null);
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {}
            }
        } else {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    public class JsBridge {

        @JavascriptInterface
        public void dbg(String msg) {
            runOnUiThread(() -> webView.evaluateJavascript(
                "dbg && dbg('[java] '+" + "\"" + msg.replace("\"", "\\\"") + "\")", null));
        }

        @JavascriptInterface
        public void saveSettings(String host, String port, String password) {
            prefs.edit()
                .putString("cloud_host", host)
                .putString("cloud_port", port)
                .putString("cloud_password", password)
                .apply();
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "设置已保存", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getDownloadDir() {
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "HG_Download");
            dir.mkdirs();
            return dir.getAbsolutePath();
        }

        @JavascriptInterface
        public void deleteDrama(String path) {
            try {
                File f = new File(path);
                if (f.isDirectory()) {
                    File[] files = f.listFiles();
                    if (files != null) for (File c : files) c.delete();
                    f.delete();
                } else {
                    f.delete();
                }
            } catch (Exception e) {}
        }

        @JavascriptInterface
        public void downloadFile(String url, String pathAndCid) {
            executor.execute(() -> {
                String path = pathAndCid;
                String cid = "";
                int idx = pathAndCid.lastIndexOf("|");
                if (idx > 0) {
                    path = pathAndCid.substring(0, idx);
                    cid = pathAndCid.substring(idx + 1);
                }
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    File outFile = new File(path);
                    outFile.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close(); is.close(); conn.disconnect();
                    final String fpath = path;
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileDownloaded && onFileDownloaded('" + fpath + "')", null));
                } catch (Exception e) {
                    final String fcid = cid;
                    final String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileError && onFileError('" + fcid + "','" + msg + "')", null));
                }
            });
        }

        @JavascriptInterface
        public void downloadDecryptFile(String url, String spadeA, String pathAndCid) {
            executor.execute(() -> {
                String path = pathAndCid;
                String cid = "";
                int idx = pathAndCid.lastIndexOf("|");
                if (idx > 0) {
                    path = pathAndCid.substring(0, idx);
                    cid = pathAndCid.substring(idx + 1);
                }
                try {
                    File outFile = new File(path);
                    outFile.getParentFile().mkdirs();
                    // Download to temp
                    File tmpFile = new File(getCacheDir(), "enc_" + System.currentTimeMillis() + ".mp4");
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);
                    conn.connect();
                    int respCode = conn.getResponseCode();
                    if (respCode != 200) throw new Exception("HTTP " + respCode);
                    InputStream is = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(tmpFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close(); is.close(); conn.disconnect();
                    // Decrypt
                    byte[] key = HongguoDecrypt.deriveKey(spadeA);
                    if (key == null) throw new Exception("无法解密：密钥派生失败");
                    HongguoDecrypt.decryptMp4File(tmpFile.getAbsolutePath(), path, key);
                    tmpFile.delete();
                    final String fpath = path;
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileDownloaded && onFileDownloaded('" + fpath + "')", null));
                } catch (Exception e) {
                    final String fcid = cid;
                    final String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileError && onFileError('" + fcid + "','" + msg + "')", null));
                }
            });
        }

        @JavascriptInterface
        public void runCommand(String cmd, String callbackId) {
            executor.execute(() -> {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    InputStream is = p.getInputStream();
                    InputStream es = p.getErrorStream();
                    StringBuilder out = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) out.append(new String(buf, 0, n));
                    while ((n = es.read(buf)) != -1) out.append(new String(buf, 0, n));
                    p.waitFor();
                    final int code = p.exitValue();
                    final String output = out.toString();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onCommandDone && onCommandDone('" + callbackId + "'," + code + ",'" +
                            output.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "')", null));
                } catch (Exception e) {
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onCommandDone && onCommandDone('" + callbackId + "',-1,'" +
                            e.getMessage().replace("'", "\\'") + "')", null));
                }
            });
        }

        @JavascriptInterface
        public String fileExists(String path) {
            return new File(path).exists() ? "yes" : "no";
        }

        @JavascriptInterface
        public long fileSize(String path) {
            File f = new File(path);
            return f.exists() ? f.length() : 0;
        }

        @JavascriptInterface
        public void deleteFile(String path) {
            new File(path).delete();
        }

        @JavascriptInterface
        public String readFile(String path) {
            try {
            File f = new File(path);
            if (!f.exists()) return "";
            byte[] data = new byte[(int) f.length()];
            FileInputStream fis = new FileInputStream(f);
            fis.read(data); fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void writeFile(String path, String content) {
            try {
                File f = new File(path);
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(content.getBytes("UTF-8"));
                fos.close();
            } catch (Exception e) {}
        }

        @JavascriptInterface
        public void setAuthToken(String token) {
            authToken = token;
        }

        @JavascriptInterface
        public String runCommandSync(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                p.waitFor();
                InputStream is = p.getInputStream();
                byte[] buf = new byte[4096];
                int n; StringBuilder sb = new StringBuilder();
                while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
                return sb.toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String listDir(String path) {
            try {
                File dir = new File(path);
                if (!dir.exists()) return "[]";
                File[] files = dir.listFiles();
                if (files == null) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < files.length; i++) {
                    if (i > 0) sb.append(",");
                    File f = files[i];
                    sb.append("{\"name\":\"").append(f.getName().replace("\"", "\\\""));
                    sb.append("\",\"isDir\":").append(f.isDirectory());
                    sb.append(",\"size\":").append(f.length()).append("}");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String getFfmpegPath() {
            try {
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                File ffmpeg = new File(nativeDir, "libffmpeg.so");
                dbg("getFfmpegPath: " + ffmpeg.getAbsolutePath() + " exists=" + ffmpeg.exists());
                return ffmpeg.exists() ? ffmpeg.getAbsolutePath() : "";
            } catch (Exception e) { return ""; }
        }

        private void copyFile(File src, File dst) throws Exception {
            FileInputStream fis = new FileInputStream(src);
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close(); fis.close();
        }

        private String getSoname(File soFile) {
            // Read ELF to find DT_SONAME
            try {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(soFile, "r");
                raf.seek(28); // e_phoff for 64-bit
                long phoff = raf.readLong();
                raf.seek(54); // e_phentsize
                int phentsize = raf.readShort();
                int phnum = raf.readShort();
                for (int i = 0; i < phnum; i++) {
                    raf.seek(phoff + i * phentsize);
                    int p_type = raf.readInt();
                    if (p_type == 2) { // PT_DYNAMIC
                        raf.seek(phoff + i * phentsize + 16);
                        long dynoff = raf.readLong();
                        raf.seek(phoff + i * phentsize + 32);
                        long dynsize = raf.readLong();
                        raf.seek(dynoff);
                        long strtab = 0;
                        java.util.List<long[]> entries = new java.util.ArrayList<>();
                        for (long off = 0; off < dynsize; off += 16) {
                            raf.seek(dynoff + off);
                            long tag = raf.readLong();
                            long val = raf.readLong();
                            if (tag == 5) strtab = val;
                            entries.add(new long[]{tag, val});
                            if (tag == 0) break;
                        }
                        for (long[] e : entries) {
                            if (e[0] == 14) { // DT_SONAME
                                raf.seek(strtab + e[1]);
                                StringBuilder sb = new StringBuilder();
                                int b;
                                while ((b = raf.read()) > 0) sb.append((char)b);
                                raf.close();
                                return sb.toString();
                            }
                        }
                    }
                }
                raf.close();
            } catch (Exception ignored) {}
            return null;
        }

        @JavascriptInterface
        public void downloadFfmpeg(String callbackId) {
            String path = getFfmpegPath();
            if (!path.isEmpty()) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFfmpegDownloaded && onFfmpegDownloaded('" + path + "')", null));
            } else {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFfmpegError && onFfmpegError('ffmpeg未找到')", null));
            }
        }

        @JavascriptInterface
        public void hgApiCall(String path, String bodyJson, String callbackId) {
            executor.execute(() -> {
                try {
                    if (hgDeviceId == null) {
                        java.util.Random rnd = new java.util.Random();
                        long d = 1_000_000_000_000_000_000L + (long)(rnd.nextDouble() * 8_000_000_000_000_000_000L);
                        long i = 1_000_000_000_000_000_000L + (long)(rnd.nextDouble() * 8_000_000_000_000_000_000L);
                        hgDeviceId = String.valueOf(d);
                        hgInstallId = String.valueOf(i);
                    }
                    // Build query in fixed order (insertion order matters for signature)
                    java.util.LinkedHashMap<String, String> q = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < HG_QUERY_KEYS.length; i++) q.put(HG_QUERY_KEYS[i], HG_QUERY_VALS[i]);
                    q.put("device_id", hgDeviceId);
                    q.put("iid", hgInstallId);
                    long nowMs = System.currentTimeMillis();
                    q.put("_rticket", String.valueOf(nowMs));
                    StringBuilder qs = new StringBuilder();
                    boolean first = true;
                    for (java.util.Map.Entry<String, String> e : q.entrySet()) {
                        if (!first) qs.append("&");
                        first = false;
                        qs.append(urlEncode(e.getKey())).append("=").append(urlEncode(e.getValue()));
                    }
                    byte[] bodyBytes = (bodyJson == null || bodyJson.isEmpty()) ? new byte[0] : bodyJson.getBytes("UTF-8");
                    // Sign
                    long tsSec = nowMs / 1000;
                    byte[] qHash = md5(qs.toString().getBytes("UTF-8"));
                    byte[] payload = new byte[20];
                    System.arraycopy(qHash, 0, payload, 0, 4);
                    String stub = "";
                    if (bodyBytes.length > 0) {
                        byte[] bHash = md5(bodyBytes);
                        System.arraycopy(bHash, 0, payload, 4, 4);
                        stub = toHexUpper(bHash);
                    }
                    payload[12] = 0; payload[13] = 6; payload[14] = 11; payload[15] = 28;
                    writeBE32(payload, 16, (int) tsSec);
                    byte[] key = new byte[]{(byte)0x44,(byte)0xb9,(byte)0xb9,(byte)0xd9,(byte)0xa4,(byte)0xae,(byte)0xf9,(byte)0xfc,(byte)0xa4,(byte)0x93,(byte)0xaa,(byte)0x75,(byte)0x7c,(byte)0xa3,(byte)0xc2,(byte)0xc4,(byte)0xa4,(byte)0x96,(byte)0x93,(byte)0x8f};
                    for (int i = 0; i < 20; i++) payload[i] ^= key[i];
                    for (int i = 0; i < 20; i++) {
                        int b = payload[i] & 0xff;
                        int mixed = (rotl8(b, 4) ^ (payload[(i + 1) % 20] & 0xff)) & 0xff;
                        payload[i] = (byte) (reverse8(mixed) ^ 0xff ^ 20);
                    }
                    byte[] signature = new byte[]{0x84,0x04,0x40,0x1c,0,0,(byte)payload[0],(byte)payload[1],(byte)payload[2],(byte)payload[3],(byte)payload[4],(byte)payload[5],(byte)payload[6],(byte)payload[7],(byte)payload[8],(byte)payload[9],(byte)payload[10],(byte)payload[11],(byte)payload[12],(byte)payload[13],(byte)payload[14],(byte)payload[15],(byte)payload[16],(byte)payload[17],(byte)payload[18],(byte)payload[19]};
                    // Request
                    URL u = new URL(HG_API + path + "?" + qs.toString());
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("X-XS-From-Web", "0");
                    conn.setRequestProperty("Sdk-Version", "2");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("X-Khronos", String.valueOf(tsSec));
                    conn.setRequestProperty("X-Gorgon", toHexLower(signature));
                    conn.setRequestProperty("X-SS-Req-Ticket", String.valueOf(nowMs));
                    if (!stub.isEmpty()) conn.setRequestProperty("X-SS-STUB", stub);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setDoOutput(true);
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(bodyBytes);
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
                    conn.disconnect();
                    final String resp = sb.toString();
                    final int rcode = code;
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onHttpResponse && onHttpResponse('" + callbackId + "'," + rcode + ",'" + u.toString().replace("\\","\\\\").replace("'","\\'") + "','" + resp.replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","") + "')", null));
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? "error" : e.getMessage().replace("\\","\\\\").replace("'","\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onHttpError && onHttpError('" + callbackId + "','" + msg + "')", null));
                }
            });
        }

        @JavascriptInterface
        public void fetchWebMedia(String seriesId, String vid, String callbackId) {
            executor.execute(() -> {
                try {
                    URL u = new URL("https://novelquickapp.com/player/" + seriesId + "/" + vid);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestProperty("User-Agent", WEB_UA);
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    conn.disconnect();
                    String text = bos.toString("UTF-8");
                    int idx = text.indexOf("video_player_info");
                    String mainUrl = "";
                    if (idx >= 0) {
                        String seg = text.substring(idx, Math.min(idx + 6000, text.length()));
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"main_url\"\\s*:\\s*\"([^\"]+)\"").matcher(seg);
                        if (m.find()) {
                            mainUrl = m.group(1).replace("\\u002f", "/").replace("\\u002F", "/").replace("\\/", "/");
                        }
                    }
                    final String url = mainUrl;
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onWebMedia && onWebMedia('" + callbackId + "','" + url.replace("\\","\\\\").replace("'","\\'") + "')", null));
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? "error" : e.getMessage().replace("'","\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onHttpError && onHttpError('" + callbackId + "','" + msg + "')", null));
                }
            });
        }

        private static byte[] md5(byte[] data) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                return md.digest(data);
            } catch (Exception e) { return new byte[16]; }
        }

        private static String toHexUpper(byte[] data) {
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02X", b));
            return sb.toString();
        }

        private static String toHexLower(byte[] data) {
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02x", b));
            return sb.toString();
        }

        private static void writeBE32(byte[] arr, int off, int val) {
            arr[off] = (byte)((val >> 24) & 0xff);
            arr[off+1] = (byte)((val >> 16) & 0xff);
            arr[off+2] = (byte)((val >> 8) & 0xff);
            arr[off+3] = (byte)(val & 0xff);
        }

        private static int rotl8(int b, int n) {
            return ((b << n) | (b >> (8 - n))) & 0xff;
        }

        private static int reverse8(int b) {
            int r = 0;
            for (int i = 0; i < 8; i++) {
                r = (r << 1) | (b & 1);
                b >>= 1;
            }
            return r & 0xff;
        }

        private static String urlEncode(String s) {
            try {
                // 与 Python urllib.parse.urlencode(quote_plus, safe='') 完全一致：
                // 空格→+，*→%2A，~→%7E（Java URLEncoder 保留 * 和 ~，需手动替换）
                return java.net.URLEncoder.encode(s, "UTF-8").replace("*", "%2A").replace("~", "%7E");
            } catch (Exception e) { return s; }
        }

        @JavascriptInterface
        public void httpRequest(String url, String method, String body, String callbackId) {
            executor.execute(() -> {
                try {
                    URL u = new URL(url);
                    HttpURLConnection conn;
                    if (u.getProtocol().equals("https")) {
                        javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) u.openConnection();
                        javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                            new javax.net.ssl.X509TrustManager() {
                                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String t) {}
                                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String t) {}
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                            }
                        };
                        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                        sc.init(null, trustAll, new java.security.SecureRandom());
                        https.setSSLSocketFactory(sc.getSocketFactory());
                        https.setHostnameVerifier((h, s) -> true);
                        conn = https;
                    } else {
                        conn = (HttpURLConnection) u.openConnection();
                    }
                    conn.setRequestMethod(method);
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    if (!"GET".equals(method)) {
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    conn.setRequestProperty("Accept", "application/json; charset=utf-8,application/x-protobuf");
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    if (!authToken.isEmpty() && !url.contains("fqnovel.com")) {
                        conn.setRequestProperty("X-Auth-Token", authToken);
                    }
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    if ("POST".equals(method) && body != null && !body.isEmpty()) {
                        conn.setDoOutput(true);
                        java.io.OutputStream os = conn.getOutputStream();
                        os.write(body.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                    }
                    dbg("HTTP " + method + " " + url + " body=" + (body != null ? body.substring(0, Math.min(body.length(), 100)) : ""));
                    int code = conn.getResponseCode();
                    dbg("HTTP response code=" + code);
                    String finalUrl = conn.getURL().toString();
                    InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    String encoding = conn.getContentEncoding();
                    if (encoding != null && encoding.contains("gzip")) {
                        is = new java.util.zip.GZIPInputStream(is);
                    }
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
                    String resp = sb.toString();
                    dbg("HTTP response size=" + resp.length() + " body=" + resp.substring(0, Math.min(resp.length(), 200)));
                    conn.disconnect();
                    String escaped = resp.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                    String escUrl = finalUrl.replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onHttpResponse && onHttpResponse('" + callbackId + "'," + code + ",'" + escUrl + "','" + escaped + "')", null));
                } catch (Exception e) {
                    String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onHttpError && onHttpError('" + callbackId + "','" + msg + "')", null));
                }
            });
        }
    }
}
