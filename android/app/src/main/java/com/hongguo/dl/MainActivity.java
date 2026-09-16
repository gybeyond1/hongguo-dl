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
        public void mergeVideos(String dirPath, String outPath, String callbackId) {
            executor.execute(() -> {
                try {
                    File dir = new File(dirPath);
                    File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4") && !name.contains("_merged") && !name.equals(new File(outPath).getName()));
                    if (files == null || files.length == 0) throw new Exception("无mp4文件");
                    java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                    dbg("Merging " + files.length + " files to " + outPath);

                    // Delete old merged file if exists
                    new File(outPath).delete();

                    android.media.MediaMuxer muxer = new android.media.MediaMuxer(outPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    int videoMuxTrack = -1, audioMuxTrack = -1;
                    long totalDuration = 0;
                    boolean muxerStarted = false;
                    boolean firstFile = true;
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024);

                    for (int f = 0; f < files.length; f++) {
                        dbg("Processing " + (f+1) + "/" + files.length + ": " + files[f].getName());
                        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                        extractor.setDataSource(files[f].getAbsolutePath());
                        int videoTrack = -1, audioTrack = -1;
                        for (int i = 0; i < extractor.getTrackCount(); i++) {
                            android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                            if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i;
                            else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i;
                        }
                        dbg("  videoTrack=" + videoTrack + " audioTrack=" + audioTrack);

                        if (!muxerStarted) {
                            if (videoTrack >= 0) videoMuxTrack = muxer.addTrack(extractor.getTrackFormat(videoTrack));
                            if (audioTrack >= 0) audioMuxTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack));
                            muxer.start();
                            muxerStarted = true;
                        }
                        if (videoTrack >= 0) extractor.selectTrack(videoTrack);
                        if (audioTrack >= 0) extractor.selectTrack(audioTrack);
                        // Seek to first keyframe
                        if (videoTrack >= 0) extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                        long lastPts = 0;
                        boolean firstVideoSample = true;
                        while (true) {
                            int sampleSize = extractor.readSampleData(buffer, 0);
                            if (sampleSize < 0) break;
                            info.offset = 0;
                            info.size = sampleSize;
                            info.presentationTimeUs = extractor.getSampleTime() + totalDuration;
                            info.flags = extractor.getSampleFlags();
                            int trackIdx = extractor.getSampleTrackIndex();
                            int muxTrack = -1;
                            if (trackIdx == videoTrack) {
                                muxTrack = videoMuxTrack;
                                // For non-first files, skip until first keyframe
                                if (!firstFile && (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                                    extractor.advance();
                                    continue;
                                }
                            }
                            else if (trackIdx == audioTrack) muxTrack = audioMuxTrack;
                            if (muxTrack >= 0 && info.size > 0) {
                                muxer.writeSampleData(muxTrack, buffer, info);
                            }
                            if (info.presentationTimeUs > lastPts) lastPts = info.presentationTimeUs;
                            extractor.advance();
                        }
                        totalDuration = lastPts;
                        firstFile = false;
                        extractor.release();
                        dbg("  done, totalDuration=" + totalDuration);
                    }
                    muxer.stop();
                    muxer.release();

                    // Verify output
                    File outFile = new File(outPath);
                    dbg("Output size: " + outFile.length() + " bytes");
                    if (outFile.length() < 100000) throw new Exception("合并文件过小(" + outFile.length() + "字节)，可能出错");

                    // Delete individual episode files on success
                    for (File f : files) f.delete();
                    dbg("Deleted " + files.length + " individual files");

                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onMergeDone && onMergeDone('" + callbackId + "',0,'')", null));
                } catch (Exception e) {
                    dbg("Merge error: " + e.getMessage());
                    new File(outPath).delete();
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
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
