package com.fastshare.app;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fastshare.app.databinding.ActivityRemoteBrowserBinding;
import com.fastshare.app.databinding.ItemBreadcrumbBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_DEVICE_NAME = "extra_device_name";
    public static final String EXTRA_DEVICE_IP = "extra_device_ip";
    public static final String EXTRA_DEVICE_PORT = "extra_device_port";
    public static final String EXTRA_IS_WINDOWS = "extra_is_windows";

    private ActivityRemoteBrowserBinding b;
    private String targetUrl;
    private String deviceName;
    private String deviceIp;
    private int devicePort;
    private boolean isWindows;

    // Native Explorer State
    private String currentPath = "";
    private String authToken = "";
    private boolean isGridView = true;
    private boolean isWebMode = false;

    private RemoteFileAdapter fileAdapter;
    private final List<RemoteFileItem> fullItemList = new ArrayList<>();
    private final List<RemoteFileItem> displayedItemList = new ArrayList<>();
    private final List<Breadcrumb> currentBreadcrumbs = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static class Breadcrumb {
        final String name;
        final String path;

        Breadcrumb(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableHighRefreshRate();

        b = ActivityRemoteBrowserBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        targetUrl = getIntent().getStringExtra(EXTRA_URL);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        deviceIp = getIntent().getStringExtra(EXTRA_DEVICE_IP);
        devicePort = getIntent().getIntExtra(EXTRA_DEVICE_PORT, 8000);
        isWindows = getIntent().getBooleanExtra(EXTRA_IS_WINDOWS, false);

        if ((deviceIp == null || deviceIp.isEmpty()) && targetUrl != null && !targetUrl.isEmpty()) {
            try {
                Uri u = Uri.parse(targetUrl);
                if (u.getHost() != null) deviceIp = u.getHost();
                if (u.getPort() > 0) devicePort = u.getPort();
            } catch (Exception ignored) {}
        }

        if (deviceIp == null || deviceIp.isEmpty()) {
            deviceIp = "127.0.0.1";
        }

        if (targetUrl == null || targetUrl.isEmpty()) {
            targetUrl = "http://" + deviceIp + ":" + devicePort;
        }

        setupInsets();
        setupHeader();
        setupNativeExplorer();
        setupWebView();
        setupBackNavigation();

        // Initial directory load (starts at drives root)
        loadDirectory("");
    }

    private void enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Window window = getWindow();
                if (window != null) {
                    Display display = getDisplay();
                    if (display != null) {
                        Display.Mode[] modes = display.getSupportedModes();
                        Display.Mode bestMode = null;
                        float maxRate = 60.0f;
                        for (Display.Mode mode : modes) {
                            if (mode.getRefreshRate() > maxRate) {
                                maxRate = mode.getRefreshRate();
                                bestMode = mode;
                            }
                        }
                        if (bestMode != null) {
                            WindowManager.LayoutParams params = window.getAttributes();
                            params.preferredDisplayModeId = bestMode.getModeId();
                            window.setAttributes(params);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final int origHeaderLeft = b.remoteGlassHeader.getPaddingLeft();
        final int origHeaderRight = b.remoteGlassHeader.getPaddingRight();
        final int origHeaderBottom = b.remoteGlassHeader.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(b.remoteRoot, (v, insets) -> {
            Insets barInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            b.remoteGlassHeader.setPadding(
                    origHeaderLeft,
                    barInsets.top + 8,
                    origHeaderRight,
                    origHeaderBottom
            );
            return insets;
        });
    }

    private void setupHeader() {
        b.tvDeviceTitle.setText(deviceName != null && !deviceName.isEmpty() ? deviceName : "Fast Share Device");
        b.tvDeviceIcon.setText(isWindows ? "💻" : "📱");
        b.tvDeviceSubtitle.setText("ONLINE • " + (deviceIp != null ? deviceIp : "") + ":" + devicePort);

        b.btnBack.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            handleUpOrBack();
        });

        b.btnViewMode.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            toggleViewMode();
        });

        b.btnToggleMode.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            toggleMode();
        });

        b.btnSecurity.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            showSecurityDialog();
        });

        b.btnRefresh.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            if (isWebMode) {
                b.remoteWebView.clearCache(true);
                b.remoteWebView.reload();
            } else {
                loadDirectory(currentPath);
            }
        });

        b.btnOpenExternal.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            try {
                Intent ext = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                startActivity(ext);
            } catch (Exception e) {
                toast("Could not open external browser: " + e.getMessage());
            }
        });

        b.btnRetry.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            b.layoutError.setVisibility(View.GONE);
            if (isWebMode) {
                b.remoteWebView.setVisibility(View.VISIBLE);
                b.remoteWebView.loadUrl(targetUrl);
            } else {
                b.layoutNativeExplorer.setVisibility(View.VISIBLE);
                loadDirectory(currentPath);
            }
        });
    }

    private void setupNativeExplorer() {
        fileAdapter = new RemoteFileAdapter();
        fileAdapter.setServerInfo(deviceIp, devicePort, authToken);
        fileAdapter.setViewType(RemoteFileAdapter.VIEW_TYPE_GRID);
        b.rvRemoteFiles.setLayoutManager(new GridLayoutManager(this, 2));
        b.rvRemoteFiles.setAdapter(fileAdapter);

        fileAdapter.setListener(new RemoteFileAdapter.OnRemoteItemClickListener() {
            @Override
            public void onItemClick(RemoteFileItem item) {
                if (item.isDir || item.isDrive) {
                    loadDirectory(item.path);
                } else {
                    downloadFile(item);
                }
            }

            @Override
            public void onActionClick(RemoteFileItem item) {
                if (item.isDir || item.isDrive) {
                    loadDirectory(item.path);
                } else {
                    downloadFile(item);
                }
            }
        });

        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterItems(s.toString());
                b.btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        b.btnClearSearch.setOnClickListener(v -> {
            b.etSearch.setText("");
            b.etSearch.clearFocus();
        });
    }

    private void toggleViewMode() {
        isGridView = !isGridView;
        if (isGridView) {
            b.rvRemoteFiles.setLayoutManager(new GridLayoutManager(this, 2));
            fileAdapter.setViewType(RemoteFileAdapter.VIEW_TYPE_GRID);
            b.btnViewMode.setImageResource(R.drawable.ic_list);
            b.btnViewMode.setContentDescription("Switch to List View");
        } else {
            b.rvRemoteFiles.setLayoutManager(new LinearLayoutManager(this));
            fileAdapter.setViewType(RemoteFileAdapter.VIEW_TYPE_LIST);
            b.btnViewMode.setImageResource(R.drawable.ic_grid);
            b.btnViewMode.setContentDescription("Switch to Grid View");
        }
    }

    private void toggleMode() {
        isWebMode = !isWebMode;
        if (isWebMode) {
            b.layoutNativeExplorer.setVisibility(View.GONE);
            b.remoteWebView.setVisibility(View.VISIBLE);
            b.btnViewMode.setVisibility(View.GONE);
            b.btnToggleMode.setImageResource(R.drawable.ic_folder);
            b.btnToggleMode.setContentDescription("Switch to Native Explorer");
            if (b.remoteWebView.getUrl() == null) {
                b.remoteWebView.loadUrl(targetUrl);
            }
        } else {
            b.remoteWebView.setVisibility(View.GONE);
            b.layoutNativeExplorer.setVisibility(View.VISIBLE);
            b.btnViewMode.setVisibility(View.VISIBLE);
            b.btnToggleMode.setImageResource(R.drawable.ic_globe);
            b.btnToggleMode.setContentDescription("Switch to Web Mode");
            if (fullItemList.isEmpty()) {
                loadDirectory(currentPath);
            }
        }
    }

    private void filterItems(String query) {
        displayedItemList.clear();
        if (query == null || query.trim().isEmpty()) {
            displayedItemList.addAll(fullItemList);
        } else {
            String lower = query.toLowerCase().trim();
            for (RemoteFileItem item : fullItemList) {
                if (item.name != null && item.name.toLowerCase().contains(lower)) {
                    displayedItemList.add(item);
                }
            }
        }
        fileAdapter.setItems(displayedItemList);
        b.layoutEmpty.setVisibility(displayedItemList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateBreadcrumbs(List<Breadcrumb> crumbs) {
        currentBreadcrumbs.clear();
        b.llBreadcrumbs.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        // Always have a root "Drives" chip if not present
        if (crumbs.isEmpty() || !crumbs.get(0).path.isEmpty()) {
            currentBreadcrumbs.add(new Breadcrumb(deviceName != null ? deviceName : "PC Storage", ""));
        }
        currentBreadcrumbs.addAll(crumbs);

        for (int i = 0; i < currentBreadcrumbs.size(); i++) {
            final Breadcrumb c = currentBreadcrumbs.get(i);
            final boolean isLast = (i == currentBreadcrumbs.size() - 1);

            ItemBreadcrumbBinding bb = ItemBreadcrumbBinding.inflate(inflater, b.llBreadcrumbs, false);
            bb.btnCrumb.setText(c.name);
            bb.tvCrumbSep.setVisibility(isLast ? View.GONE : View.VISIBLE);

            if (isLast) {
                bb.btnCrumb.setBackgroundResource(R.drawable.glass_chip_accent);
                bb.btnCrumb.setTextColor(getColor(R.color.text_cyan));
            } else {
                bb.btnCrumb.setBackgroundResource(R.drawable.glass_chip);
                bb.btnCrumb.setTextColor(getColor(R.color.text_primary));
            }

            bb.btnCrumb.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                loadDirectory(c.path);
            });

            b.llBreadcrumbs.addView(bb.getRoot());
        }

        b.hsvBreadcrumbs.post(() -> b.hsvBreadcrumbs.fullScroll(View.FOCUS_RIGHT));
    }

    private void loadDirectory(String path) {
        b.browserProgressBar.setVisibility(View.VISIBLE);
        b.browserProgressBar.setIndeterminate(true);
        b.layoutLoading.setVisibility(View.VISIBLE);
        b.layoutEmpty.setVisibility(View.GONE);
        b.layoutError.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                // Ensure authentication if needed
                if (authToken.isEmpty() && deviceIp != null) {
                    String savedPass = Prefs.getDevicePassword(this, deviceIp);
                    if (savedPass != null && !savedPass.isEmpty()) {
                        performLoginSync(savedPass);
                    }
                }

                String encodedPath = path != null ? URLEncoder.encode(path, "UTF-8") : "";
                String requestUrl = "http://" + deviceIp + ":" + devicePort + "/api/list?path=" + encodedPath;
                HttpURLConnection conn = (HttpURLConnection) new URL(requestUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                if (!authToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + authToken);
                }

                int code = conn.getResponseCode();
                if (code == 401) {
                    mainHandler.post(() -> {
                        b.browserProgressBar.setVisibility(View.GONE);
                        b.layoutLoading.setVisibility(View.GONE);
                        promptPasswordDialog(path);
                    });
                    return;
                }

                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray itemsArr = root.optJSONArray("items");
                    JSONArray breadcrumbsArr = root.optJSONArray("breadcrumbs");

                    List<RemoteFileItem> newItems = new ArrayList<>();
                    if (itemsArr != null) {
                        for (int i = 0; i < itemsArr.length(); i++) {
                            JSONObject obj = itemsArr.getJSONObject(i);
                            RemoteFileItem it = new RemoteFileItem();
                            it.name = obj.optString("name");
                            it.path = obj.optString("path");
                            it.isDir = obj.optBoolean("is_dir", false);
                            it.isDrive = obj.optBoolean("is_drive", false);
                            it.size = obj.optLong("size", 0);
                            it.free = obj.optLong("free", 0);
                            it.used = obj.optLong("used", 0);
                            it.mtime = obj.optLong("mtime", 0);
                            it.icon = obj.optString("icon", "");
                            newItems.add(it);
                        }
                    }

                    // Sort items: Drives & Directories first, then files alphabetically
                    Collections.sort(newItems, (o1, o2) -> {
                        if (o1.isDrive != o2.isDrive) return o1.isDrive ? -1 : 1;
                        if (o1.isDir != o2.isDir) return o1.isDir ? -1 : 1;
                        return o1.name.compareToIgnoreCase(o2.name);
                    });

                    List<Breadcrumb> newCrumbs = new ArrayList<>();
                    if (breadcrumbsArr != null) {
                        for (int i = 0; i < breadcrumbsArr.length(); i++) {
                            JSONObject obj = breadcrumbsArr.getJSONObject(i);
                            newCrumbs.add(new Breadcrumb(obj.optString("name"), obj.optString("path")));
                        }
                    }

                    mainHandler.post(() -> {
                        currentPath = path != null ? path : "";
                        if (fileAdapter != null) {
                            fileAdapter.setServerInfo(deviceIp, devicePort, authToken);
                        }
                        fullItemList.clear();
                        fullItemList.addAll(newItems);
                        updateBreadcrumbs(newCrumbs);
                        filterItems(b.etSearch.getText().toString());

                        b.browserProgressBar.setVisibility(View.GONE);
                        b.layoutLoading.setVisibility(View.GONE);
                    });
                } else {
                    mainHandler.post(() -> showErrorState("Server responded with error HTTP " + code));
                }
            } catch (Exception e) {
                Log.e("RemoteBrowser", "Failed to load directory: " + e.getMessage(), e);
                mainHandler.post(() -> showErrorState("Could not connect to " + deviceName + " (" + deviceIp + ":" + devicePort + ").\n" + e.getMessage()));
            }
        });
    }

    private void promptPasswordDialog(String retryPath) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter security password");
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_muted));

        FrameLayout container = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, 10, pad, 10);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle("🔐 Password Required")
                .setMessage("Enter the connection password shown on " + (deviceName != null ? deviceName : "PC screen") + ":")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String password = input.getText().toString().trim();
                    if (!password.isEmpty()) {
                        authenticateWithPassword(password, retryPath);
                    } else {
                        toast("Password cannot be empty");
                        promptPasswordDialog(retryPath);
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .show();
    }

    private void authenticateWithPassword(String password, String retryPath) {
        b.browserProgressBar.setVisibility(View.VISIBLE);
        b.layoutLoading.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            boolean success = performLoginSync(password);
            mainHandler.post(() -> {
                if (success) {
                    if (deviceIp != null) {
                        Prefs.setDevicePassword(this, deviceIp, password);
                    }
                    toast("🔐 Successfully authenticated!");
                    loadDirectory(retryPath);
                } else {
                    b.browserProgressBar.setVisibility(View.GONE);
                    b.layoutLoading.setVisibility(View.GONE);
                    toast("❌ Incorrect password. Please try again.");
                    promptPasswordDialog(retryPath);
                }
            });
        });
    }

    private boolean performLoginSync(String password) {
        try {
            URL url = new URL("http://" + deviceIp + ":" + devicePort + "/api/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            JSONObject body = new JSONObject();
            body.put("password", password);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject res = new JSONObject(sb.toString());
                if (res.optBoolean("ok", false)) {
                    authToken = res.optString("token", "");
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e("RemoteBrowser", "Login error: " + e.getMessage());
        }
        return false;
    }

    private void downloadFile(RemoteFileItem item) {
        try {
            String encodedPath = URLEncoder.encode(item.path, "UTF-8");
            String downloadUrl = "http://" + deviceIp + ":" + devicePort + "/api/download?path=" + encodedPath;
            if (!authToken.isEmpty()) {
                downloadUrl += "&token=" + URLEncoder.encode(authToken, "UTF-8");
            }

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(downloadUrl));
            req.setTitle(item.name);
            req.setDescription("Fast Share download from " + deviceName);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.name);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                toast("⬇ Downloading " + item.name + " (" + RemoteFileAdapter.formatSize(item.size) + ")…");
            }
        } catch (Exception e) {
            toast("Download failed: " + e.getMessage());
        }
    }

    private void showSecurityDialog() {
        String savedPass = deviceIp != null ? Prefs.getDevicePassword(this, deviceIp) : null;
        boolean hasSaved = savedPass != null && !savedPass.isEmpty();

        new MaterialAlertDialogBuilder(this)
                .setTitle("Fast Share Security")
                .setMessage("Target Device: " + (deviceName != null ? deviceName : "PC") + " (" + (deviceIp != null ? deviceIp : "") + ")"
                        + "\nSession Token: " + (authToken.isEmpty() ? "None" : (authToken.substring(0, Math.min(8, authToken.length())) + "..."))
                        + "\nSaved Password: " + (hasSaved ? "Active (Auto-connect)" : "None")
                        + "\n\nWould you like to clear the saved password and log out?")
                .setPositiveButton("Log Out & Clear", (dialog, which) -> {
                    if (deviceIp != null) {
                        Prefs.setDevicePassword(RemoteBrowserActivity.this, deviceIp, null);
                    }
                    authToken = "";
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    b.remoteWebView.clearCache(true);
                    toast("🔒 Cleared saved credentials. Please re-authenticate.");
                    loadDirectory("");
                })
                .setNegativeButton("Keep Saved", null)
                .show();
    }

    private void showErrorState(String message) {
        b.browserProgressBar.setVisibility(View.GONE);
        b.layoutLoading.setVisibility(View.GONE);
        b.layoutNativeExplorer.setVisibility(View.GONE);
        b.remoteWebView.setVisibility(View.GONE);
        b.layoutError.setVisibility(View.VISIBLE);
        b.tvErrorMessage.setText(message);
    }

    private void handleUpOrBack() {
        if (isWebMode) {
            if (b.remoteWebView.canGoBack()) {
                b.remoteWebView.goBack();
                return;
            }
            toggleMode(); // return to native explorer
            return;
        }

        // Native mode navigation
        if (b.etSearch.getText().length() > 0) {
            b.etSearch.setText("");
            return;
        }

        if (currentBreadcrumbs.size() > 1) {
            // Navigate up to parent breadcrumb
            Breadcrumb parent = currentBreadcrumbs.get(currentBreadcrumbs.size() - 2);
            loadDirectory(parent.path);
        } else if (!currentPath.isEmpty()) {
            // Navigate to root drives
            loadDirectory("");
        } else {
            finish();
        }
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleUpOrBack();
            }
        });
    }

    private void setupWebView() {
        WebSettings ws = b.remoteWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(b.remoteWebView, true);

        b.remoteWebView.setBackgroundColor(0x00000000);

        b.remoteWebView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onAuthSuccess(String password) {
                if (deviceIp != null && password != null && !password.isEmpty()) {
                    Prefs.setDevicePassword(RemoteBrowserActivity.this, deviceIp, password);
                }
            }
        }, "AndroidBridge");

        b.remoteWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    b.browserProgressBar.setVisibility(View.VISIBLE);
                    b.browserProgressBar.setProgress(newProgress);
                } else {
                    b.browserProgressBar.setVisibility(View.GONE);
                }
            }
        });

        b.remoteWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                b.layoutError.setVisibility(View.GONE);
                b.remoteWebView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                b.browserProgressBar.setVisibility(View.GONE);
                if (deviceIp != null) {
                    String savedPass = Prefs.getDevicePassword(RemoteBrowserActivity.this, deviceIp);
                    if (savedPass != null && !savedPass.isEmpty()) {
                        String js = "try {"
                                + "let pw = document.getElementById('authpwd') || document.getElementById('password');"
                                + "if (pw) { pw.value = " + JSONObject.quote(savedPass) + ";"
                                + "if (typeof submitLogin === 'function') submitLogin();"
                                + "else if (typeof login === 'function') login(); }"
                                + "} catch(e){}";
                        view.evaluateJavascript(js, null);
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    b.browserProgressBar.setVisibility(View.GONE);
                    showErrorState("Could not reach " + (deviceName != null ? deviceName : "device")
                            + " at " + targetUrl + ".\nEnsure both devices are on Wi-Fi.");
                }
            }
        });

        b.remoteWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    request.addRequestHeader("cookie", cookie);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setDescription("Fast Share download from " + deviceName);
                    String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(fileName);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        toast("Downloading " + fileName + "…");
                    }
                } catch (Exception e) {
                    toast("Download failed: " + e.getMessage());
                }
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (b.remoteWebView != null) {
            b.remoteWebView.stopLoading();
            b.remoteWebView.destroy();
        }
        super.onDestroy();
    }
}
