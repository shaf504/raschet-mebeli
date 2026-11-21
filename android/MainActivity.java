package ru.mebelshaf.raschet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String HOST = "shaf504.github.io";
    private static final String PREFIX = "/raschet-mebeli/";
    private static final String START = "https://" + HOST + PREFIX;
    private static final int PICK_INPUT = 1;
    private static final int SAVE_OUTPUT = 2;
    private static final int MAX_BASE64_SIZE = 32 * 1024 * 1024;

    private final ArrayDeque<WebView> pages = new ArrayDeque<>();
    private FrameLayout container;
    private ValueCallback<Uri[]> filePicker;
    private byte[] pendingDownload;
    private String downloadScript;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        container = new FrameLayout(this);
        container.setBackgroundColor(Color.WHITE);
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime();
                android.graphics.Insets padding = insets.getInsets(types);
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            }
            return insets;
        });
        setContentView(container);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        downloadScript = readScript();
        WebView root = createPage();
        showPage(root);
        root.loadUrl(START);
    }

    private String readScript() {
        try (InputStream source = getAssets().open("android-downloads.js")) {
            ByteArrayOutputStream script = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) script.write(buffer, 0, count);
            return new String(script.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Missing download script", error);
        }
    }

    private WebView createPage() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        view.addJavascriptInterface(new Downloads(view), "AndroidExport");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView page, WebResourceRequest request) {
                return "GET".equals(request.getMethod()) ? localResource(request.getUrl()) : null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView page, WebResourceRequest request) {
                if (!request.isForMainFrame() || isAppUrl(request.getUrl())) return false;
                if (request.hasGesture() || page != pages.peekFirst()) openExternal(request.getUrl());
                if (page != pages.peekFirst() && !isAppUrl(Uri.parse(valueOrEmpty(page.getUrl())))) {
                    page.post(() -> closePage(page));
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView page, String url) {
                if (isAppUrl(Uri.parse(url))) page.evaluateJavascript(downloadScript, null);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView parent, boolean isDialog, boolean userGesture, Message message) {
                if (!userGesture) return false;
                WebView child = createPage();
                showPage(child);
                ((WebView.WebViewTransport) message.obj).setWebView(child);
                message.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView page) {
                closePage(page);
            }

            @Override
            public boolean onShowFileChooser(WebView page, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePicker != null) filePicker.onReceiveValue(null);
                filePicker = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_INPUT);
                    return true;
                } catch (ActivityNotFoundException unavailable) {
                    filePicker = null;
                    return false;
                }
            }
        });
        view.setDownloadListener((url, userAgent, disposition, mime, length) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                String name = URLUtil.guessFileName(url, disposition, mime);
                view.evaluateJavascript("window.androidSaveDownload && window.androidSaveDownload("
                    + JSONObject.quote(url) + "," + JSONObject.quote(name) + ")", null);
            } else {
                openExternal(Uri.parse(url));
            }
        });
        return view;
    }

    private static boolean isAppUrl(Uri uri) {
        String path = uri.getPath();
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && path != null && path.startsWith(PREFIX);
    }

    private WebResourceResponse localResource(Uri uri) {
        String asset;
        if (isAppUrl(uri)) {
            String path = uri.getPath().substring(PREFIX.length());
            if (path.isEmpty()) path = "index.html";
            if (path.indexOf('\\') >= 0) return missing();
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) return missing();
            }
            asset = "web/" + path;
        } else if (uri.toString().equals("https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js")) {
            asset = "vendor/three.min.js";
        } else if (uri.toString().equals("https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js")) {
            asset = "vendor/OrbitControls.js";
        } else {
            return null;
        }
        try {
            String type = mimeType(asset);
            String encoding = type.startsWith("text/") || type.startsWith("application/") ? "UTF-8" : null;
            return new WebResourceResponse(type, encoding, getAssets().open(asset));
        } catch (IOException unavailable) {
            return missing();
        }
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeType(String asset) {
        String ext = asset.substring(asset.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html": return "text/html";
            case "css": return "text/css";
            case "js": return "application/javascript";
            case "json": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "svg": return "image/svg+xml";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            default: return "application/octet-stream";
        }
    }

    private void showPage(WebView page) {
        if (!pages.isEmpty()) pages.peekLast().setVisibility(View.GONE);
        pages.addLast(page);
        container.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
    }

    private void closePage(WebView page) {
        if (page == pages.peekFirst() || !pages.remove(page)) return;
        container.removeView(page);
        page.destroy();
        WebView active = pages.peekLast();
        if (active != null) {
            active.setVisibility(View.VISIBLE);
            active.requestFocus();
        }
    }

    private void handleBack() {
        WebView page = pages.peekLast();
        if (page != null && page.canGoBack()) page.goBack();
        else if (pages.size() > 1) closePage(page);
        else finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBack();
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!("https".equals(scheme) || "http".equals(scheme) || "mailto".equals(scheme)
            || "tel".equals(scheme) || "whatsapp".equals(scheme))) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void downloadError() {
        Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_SHORT).show();
    }

    private final class Downloads {
        private final WebView view;

        Downloads(WebView view) {
            this.view = view;
        }

        @JavascriptInterface
        public void showError() {
            runOnUiThread(MainActivity.this::downloadError);
        }

        @JavascriptInterface
        public void save(String name, String type, String base64) {
            if (base64 == null || base64.length() > MAX_BASE64_SIZE) return;
            byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException malformed) {
                showError();
                return;
            }
            runOnUiThread(() -> {
                if (pendingDownload != null || view != pages.peekLast()
                    || !isAppUrl(Uri.parse(valueOrEmpty(view.getUrl())))) return;
                String safeName = valueOrEmpty(name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
                String mime = valueOrEmpty(type);
                if (!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) mime = "application/octet-stream";
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, safeName.isEmpty() ? "Мебельшаф" : safeName);
                pendingDownload = bytes;
                try {
                    startActivityForResult(save, SAVE_OUTPUT);
                } catch (ActivityNotFoundException unavailable) {
                    pendingDownload = null;
                    downloadError();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_INPUT && filePicker != null) {
            filePicker.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            filePicker = null;
        } else if (request == SAVE_OUTPUT) {
            byte[] bytes = pendingDownload;
            pendingDownload = null;
            if (result != RESULT_OK || data == null || data.getData() == null || bytes == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IOException("Missing output stream");
                output.write(bytes);
            } catch (IOException failure) {
                downloadError();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (filePicker != null) filePicker.onReceiveValue(null);
        while (!pages.isEmpty()) {
            WebView page = pages.removeLast();
            container.removeView(page);
            page.destroy();
        }
        super.onDestroy();
    }
}
