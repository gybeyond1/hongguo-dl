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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
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
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");
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
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/HG_Download");
            dir.mkdirs();
            return dir.getAbsolutePath();
        }

        @JavascriptInterface
        public void downloadFile(String url, String path) {
            executor.execute(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Mobile");
                    conn.setRequestProperty("Referer", "https://novelquickapp.com/");
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    FileOutputStream fos = new FileOutputStream(path);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.close(); is.close(); conn.disconnect();
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileDownloaded && onFileDownloaded('" + path + "')", null));
                } catch (Exception e) {
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onFileError && onFileError('" + e.getMessage().replace("'", "\\'") + "')", null));
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
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }
}
