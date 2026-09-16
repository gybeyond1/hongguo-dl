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

    private static final String UA = "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)";
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
                    final String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileError && onFileError('" + msg + "')", null));
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
                    final String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileError && onFileError('" + msg + "')", null));
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
            File ffmpeg = new File(getFilesDir(), "bin/ffmpeg");
            if (ffmpeg.exists()) return ffmpeg.getAbsolutePath();
            try {
                File binDir = new File(getFilesDir(), "bin");
                File libDir = new File(binDir, "lib");
                binDir.mkdirs(); libDir.mkdirs();
                extractAsset("bin/ffmpeg", ffmpeg);
                ffmpeg.setExecutable(true, false);
                String[] libs = getAssets().list("bin/lib");
                if (libs != null) {
                    for (String lib : libs) {
                        extractAsset("bin/lib/" + lib, new File(libDir, lib));
                    }
                }
                return ffmpeg.getAbsolutePath();
            } catch (Exception e) { return ""; }
        }

        private void extractAsset(String assetPath, File outFile) throws Exception {
            InputStream is = getAssets().open(assetPath);
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close(); is.close();
        }

        @JavascriptInterface
        public void downloadFfmpeg(String callbackId) {
            executor.execute(() -> {
                String path = getFfmpegPath();
                if (!path.isEmpty()) {
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFfmpegDownloaded && onFfmpegDownloaded('" + path + "')", null));
                } else {
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFfmpegError && onFfmpegError('内置ffmpeg解压失败')", null));
                }
            });
        }

        @JavascriptInterface
        public void mergeVideos(String dirPath, String outPath, String callbackId) {
            executor.execute(() -> {
                try {
                    String ffmpeg = getFfmpegPath();
                    if (ffmpeg.isEmpty()) throw new Exception("ffmpeg未下载");
                    File dir = new File(dirPath);
                    File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
                    if (files == null || files.length == 0) throw new Exception("无mp4文件");
                    java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                    StringBuilder list = new StringBuilder();
                    for (File f : files) list.append("file '").append(f.getAbsolutePath()).append("'\n");
                    File listFile = new File(dir, "filelist.txt");
                    FileOutputStream lfos = new FileOutputStream(listFile);
                    lfos.write(list.toString().getBytes());
                    lfos.close();
                    File libDir = new File(getFilesDir(), "bin/lib");
                    // Create symlinks: libavcodec.so.62.28.102 -> libavcodec.so.62
                    File[] libs = libDir.listFiles();
                    if (libs != null) {
                        for (File lib : libs) {
                            String name = lib.getName();
                            // libavcodec.so.62.28.102 -> libavcodec.so.62
                            String[] parts = name.split("\\.");
                            if (parts.length >= 4) {
                                String linkName = parts[0] + "." + parts[1] + "." + parts[2];
                                File link = new File(libDir, linkName);
                                if (!link.exists()) {
                                    try {
                                        Runtime.getRuntime().exec(new String[]{"ln", "-sf", name, link.getAbsolutePath()}).waitFor();
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                    String ldLibPath = libDir.getAbsolutePath();
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "LD_LIBRARY_PATH=" + ldLibPath + " " +
                        ffmpeg + " -y -f concat -safe 0 -i '" + listFile.getAbsolutePath() + "' -c copy '" + outPath + "' 2>&1"});
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    StringBuilder errOut = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) errOut.append(line).append("\n");
                    int code = p.waitFor();
                    listFile.delete();
                    final int fc = code;
                    final String errMsg = errOut.toString().length() > 200 ? errOut.toString().substring(errOut.toString().length() - 200) : errOut.toString();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onMergeDone && onMergeDone('" + callbackId + "'," + fc + ",'" +
                            errMsg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "')", null));
                } catch (Exception e) {
                    final String msg = e.getMessage().replace("\\", "\\\\").replace("'", "\\'");
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onMergeDone && onMergeDone('" + callbackId + "',-1,'" + msg + "')", null));
                }
            });
        }

        @JavascriptInterface
        public void httpRequest(String url, String method, String body, String callbackId) {
            executor.execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod(method);
                    conn.setRequestProperty("User-Agent", UA);
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json; charset=utf-8,application/x-protobuf");
                    if (!authToken.isEmpty() && !url.contains("fqnovel.com")) {
                        conn.setRequestProperty("X-Auth-Token", authToken);
                    }
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    if ("POST".equals(method) && body != null && !body.isEmpty()) {
                        conn.setDoOutput(true);
                        conn.getOutputStream().write(body.getBytes("UTF-8"));
                    }
                    int code = conn.getResponseCode();
                    String finalUrl = conn.getURL().toString();
                    InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                    StringBuilder sb = new StringBuilder();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
                    String resp = sb.toString();
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
