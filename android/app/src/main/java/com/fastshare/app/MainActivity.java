package com.fastshare.app;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.webkit.MimeTypeMap;
import android.widget.MediaController;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fastshare.app.databinding.ActivityMainBinding;
import com.fastshare.app.databinding.DialogMediaPreviewBinding;
import com.fastshare.app.databinding.ItemDeviceCardBinding;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fast Share Android - Pure Native Material 3 UI.
 * High-performance 120Hz local storage browser, instant hardware thumbnail engine,
 * Ecosystem Radar for PC discovery, session telemetry, and zero lag.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding b;
    private FileListAdapter fileAdapter;
    private BreadcrumbAdapter breadcrumbAdapter;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private File rootDir;
    private File currentDirectory;
    private final List<File> currentFiles = new ArrayList<>();
    private final List<File> displayedFiles = new ArrayList<>();

    private boolean isGridMode = true;
    private int sortMode = SORT_DATE_DESC;

    private static final int SORT_DATE_DESC = 0;
    private static final int SORT_NAME_ASC = 1;
    private static final int SORT_SIZE_DESC = 2;

    private static final int TAB_FILES = 0;
    private static final int TAB_RADAR = 1;
    private static final int TAB_TRANSFERS = 2;
    private int currentTab = TAB_FILES;

    private ActivityResultLauncher<String> notifPerm;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private DiscoveryManager discoveryManager;
    private TransferManager transferManager;
    private String pendingTargetIp;
    private int pendingTargetPort;
    private String pendingTargetName;
    private String lastRenderedDevicesKey = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableHighRefreshRate();

        NativeThumbnailLoader.init(getApplicationContext());

        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        rootDir = Environment.getExternalStorageDirectory();
        currentDirectory = rootDir;

        setupInsets();
        setupActivityLaunchers();
        transferManager = new TransferManager(this);
        setupRecyclerViews();
        setupTabNavigation();
        setupTopBar();
        setupSearchAndControls();
        setupServerSettingsSheet();
        setupBackNavigation();
        setupMicroInteractions();

        setupRadar();

        // Check storage permissions on start
        checkPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionGate();
        updateServerStatusUi();
        if (currentTab == TAB_RADAR) {
            startRadarPulse();
        }
        if (hasStoragePermission()) {
            if (currentFiles.isEmpty()) {
                loadDirectory(currentDirectory != null ? currentDirectory : rootDir);
            }
            if (!ShareService.isUp()) {
                ShareService.start(this);
            }
            mainHandler.postDelayed(this::attachServiceDiscovery, 500);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRadarPulse();
    }

    @Override
    protected void onDestroy() {
        stopRadarPulse();
        super.onDestroy();
        ioExecutor.shutdown();
    }

    // ---------------------------------------------------------------- High Refresh Rate & Insets

    private void enableHighRefreshRate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Display display = getDisplay();
                if (display != null) {
                    Display.Mode[] modes = display.getSupportedModes();
                    Display.Mode maxMode = null;
                    float maxRate = 60.0f;
                    for (Display.Mode m : modes) {
                        if (m.getRefreshRate() > maxRate) {
                            maxRate = m.getRefreshRate();
                            maxMode = m;
                        }
                    }
                    if (maxMode != null) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = maxMode.getModeId();
                        getWindow().setAttributes(lp);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final int origHeaderLeft = b.glassHeader.getPaddingLeft();
        final int origHeaderRight = b.glassHeader.getPaddingRight();
        final int origHeaderBottom = b.glassHeader.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(b.rootCoordinator, (v, insets) -> {
            Insets barInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            b.glassHeader.setPadding(
                    origHeaderLeft,
                    barInsets.top + 8,
                    origHeaderRight,
                    origHeaderBottom
            );
            return insets;
        });
    }

    // ---------------------------------------------------------------- Launchers & Permissions

    private void setupActivityLaunchers() {
        notifPerm = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updatePermissionGate()
        );

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        List<Uri> uris = new ArrayList<>();
                        if (data.getClipData() != null) {
                            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                                uris.add(data.getClipData().getItemAt(i).getUri());
                            }
                        } else if (data.getData() != null) {
                            uris.add(data.getData());
                        }
                        if (!uris.isEmpty() && pendingTargetIp != null) {
                            String targetIp = pendingTargetIp;
                            int targetPort = pendingTargetPort;
                            String targetName = pendingTargetName;
                            pendingTargetIp = null;
                            pendingTargetName = null;
                            promptOrSendFiles(uris, targetIp, targetPort, targetName);
                        } else {
                            toast("Files selected for sharing");
                        }
                    }
                }
        );
    }

    private void promptOrSendFiles(List<Uri> uris, String targetIp, int targetPort, String targetName) {
        String savedPass = Prefs.getDevicePassword(this, targetIp);
        if (savedPass == null || savedPass.isEmpty()) {
            showDevicePasswordDialog(targetName, targetIp, targetPort, uris);
        } else {
            executeFileTransfer(uris, targetIp, targetPort, targetName, savedPass);
        }
    }

    private void showDevicePasswordDialog(String targetName, String targetIp, int targetPort, List<Uri> uris) {
        String label = (targetName != null && !targetName.isEmpty()) ? targetName : targetIp;
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Password (leave empty if none)");
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_muted));

        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Connect to " + label)
                .setMessage("Enter the password configured on " + label + " to transfer files:")
                .setView(container)
                .setPositiveButton("Send", (dialog, which) -> {
                    String pass = input.getText().toString().trim();
                    Prefs.setDevicePassword(this, targetIp, pass);
                    executeFileTransfer(uris, targetIp, targetPort, targetName, pass);
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void executeFileTransfer(List<Uri> uris, String targetIp, int targetPort, String targetName, String pass) {
        String user = Prefs.getUser(this);
        transferManager.sendFiles(uris, targetIp, targetPort, user, pass, new TransferManager.TransferListener() {
            @Override
            public void onTransferStart(String name, int current, int total, long bytes) {
                toast("Sending " + name);
            }

            @Override
            public void onTransferProgress(String name, int current, int total, long sent, long size, float speed, int eta) {}

            @Override
            public void onFileCompleted(String name, int current, int total) {
                toast("Sent " + name);
            }

            @Override
            public void onAllCompleted(int success, int failed) {
                toast("Transfers complete: " + success + " sent, " + failed + " failed");
            }

            @Override
            public void onTransferError(String name, String error) {
                if ("AUTH_REQUIRED".equals(error)) {
                    toast("Password required for " + (targetName != null ? targetName : targetIp));
                    showDevicePasswordDialog(targetName, targetIp, targetPort, uris);
                } else {
                    toast("Failed to send " + name + ": " + error);
                }
            }
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissions() {
        if (!hasStoragePermission()) {
            b.gate.setVisibility(View.VISIBLE);
            b.filesTabContent.setVisibility(View.GONE);
        } else {
            b.gate.setVisibility(View.GONE);
            b.filesTabContent.setVisibility(View.VISIBLE);
            loadDirectory(rootDir);
            ShareService.start(this);
        }
        updatePermissionGate();
    }

    private void updatePermissionGate() {
        boolean storage = hasStoragePermission();
        if (storage) {
            b.gate.setVisibility(View.GONE);
            if (currentTab == TAB_FILES) {
                b.filesTabContent.setVisibility(View.VISIBLE);
            }
            b.filesState.setText("✓ GRANTED");
            b.filesState.setBackgroundResource(R.drawable.glass_badge_emerald);
            b.filesState.setTextColor(ContextCompat.getColor(this, R.color.text_emerald));
        } else {
            b.gate.setVisibility(View.VISIBLE);
            b.filesTabContent.setVisibility(View.GONE);
            b.filesState.setText("⚡ TAP TO ALLOW");
            b.filesState.setBackgroundResource(R.drawable.glass_badge_amber);
            b.filesState.setTextColor(ContextCompat.getColor(this, R.color.text_amber));
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        b.battState.setText(batt ? "✓ UNRESTRICTED" : "TAP TO OPTIMIZE");
        b.battState.setBackgroundResource(batt ? R.drawable.glass_badge_emerald : R.drawable.glass_badge_amber);
        b.battState.setTextColor(ContextCompat.getColor(this, batt ? R.color.text_emerald : R.color.text_amber));

        boolean notif = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        b.notifState.setText(notif ? "✓ ACTIVE" : "OPTIONAL");
        b.notifState.setBackgroundResource(notif ? R.drawable.glass_badge_emerald : R.drawable.glass_badge_secondary);
        b.notifState.setTextColor(ContextCompat.getColor(this, notif ? R.color.text_emerald : R.color.text_secondary));

        b.btnGrant.setOnClickListener(v -> openStorageSettings());
        b.rowFiles.setOnClickListener(v -> openStorageSettings());
        b.rowBattery.setOnClickListener(v -> requestBatteryOptimization());
        b.rowNotif.setOnClickListener(v -> askNotificationPermission());
    }

    private void openStorageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivity(intent);
        }
    }

    private void requestBatteryOptimization() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ---------------------------------------------------------------- RecyclerViews & File Adapter

    private void setupRecyclerViews() {
        // Files RecyclerView
        fileAdapter = new FileListAdapter();
        fileAdapter.setViewType(isGridMode ? FileListAdapter.VIEW_TYPE_GRID : FileListAdapter.VIEW_TYPE_LIST);
        b.rvFiles.setLayoutManager(isGridMode ? new GridLayoutManager(this, 2) : new LinearLayoutManager(this));
        b.rvFiles.setAdapter(fileAdapter);

        fileAdapter.setListener(new FileListAdapter.OnItemClickListener() {
            @Override
            public void onFolderClick(File file) {
                loadDirectory(file);
            }

            @Override
            public void onMediaClick(File file) {
                showMediaPreview(file);
            }

            @Override
            public void onFileClick(File file) {
                openFileWithSystem(file);
            }
        });

        // Breadcrumbs RecyclerView
        breadcrumbAdapter = new BreadcrumbAdapter();
        breadcrumbAdapter.setListener(this::loadDirectory);
        b.rvBreadcrumbs.setAdapter(breadcrumbAdapter);

        b.btnUp.setOnClickListener(v -> navigateUp());
    }

    private void loadDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        currentDirectory = dir;

        ioExecutor.execute(() -> {
            File[] files = dir.listFiles();
            List<File> list = new ArrayList<>();
            if (files != null) {
                for (File f : files) {
                    if (!f.getName().startsWith(".")) {
                        list.add(f);
                    }
                }
            }

            sortFileList(list);

            mainHandler.post(() -> {
                currentFiles.clear();
                currentFiles.addAll(list);
                applyFilter(b.etSearch.getText().toString());
                updateBreadcrumbs(dir);

                String name = dir.equals(rootDir) ? "Internal Storage" : dir.getName();
                b.tvFolderTitle.setText(name);
                b.tvFolderMeta.setText(dir.getAbsolutePath());

                // Update Up button visibility
                b.btnUp.setEnabled(!dir.equals(rootDir));
                b.btnUp.setAlpha(dir.equals(rootDir) ? 0.4f : 1.0f);
            });
        });
    }

    private void navigateUp() {
        if (currentDirectory != null && !currentDirectory.equals(rootDir)) {
            File parent = currentDirectory.getParentFile();
            if (parent != null && parent.canRead()) {
                loadDirectory(parent);
            }
        }
    }

    private void sortFileList(List<File> list) {
        Collections.sort(list, (f1, f2) -> {
            // Folders first
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;

            if (sortMode == SORT_DATE_DESC) {
                return Long.compare(f2.lastModified(), f1.lastModified());
            } else if (sortMode == SORT_NAME_ASC) {
                return f1.getName().compareToIgnoreCase(f2.getName());
            } else if (sortMode == SORT_SIZE_DESC) {
                return Long.compare(f2.length(), f1.length());
            }
            return 0;
        });
    }

    private void applyFilter(String query) {
        displayedFiles.clear();
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        if (q.isEmpty()) {
            displayedFiles.addAll(currentFiles);
        } else {
            for (File f : currentFiles) {
                if (f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    displayedFiles.add(f);
                }
            }
        }

        fileAdapter.setItems(displayedFiles);
        b.tvItemCountBadge.setText(displayedFiles.size() + " ITEMS");

        if (displayedFiles.isEmpty()) {
            b.emptyFilesView.setVisibility(View.VISIBLE);
            b.rvFiles.setVisibility(View.GONE);
        } else {
            b.emptyFilesView.setVisibility(View.GONE);
            b.rvFiles.setVisibility(View.VISIBLE);
        }
    }

    private void updateBreadcrumbs(File current) {
        List<BreadcrumbAdapter.CrumbItem> crumbs = new ArrayList<>();
        crumbs.add(new BreadcrumbAdapter.CrumbItem("📁 Storage", rootDir));

        String rootPath = rootDir.getAbsolutePath();
        String curPath = current.getAbsolutePath();

        if (curPath.startsWith(rootPath) && curPath.length() > rootPath.length()) {
            String rel = curPath.substring(rootPath.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            String[] segments = rel.split(File.separator);
            File walk = rootDir;
            for (String seg : segments) {
                if (!seg.isEmpty()) {
                    walk = new File(walk, seg);
                    crumbs.add(new BreadcrumbAdapter.CrumbItem(seg, walk));
                }
            }
        }
        breadcrumbAdapter.setCrumbs(crumbs);
        if (!crumbs.isEmpty()) {
            b.rvBreadcrumbs.scrollToPosition(crumbs.size() - 1);
        }
    }

    // ---------------------------------------------------------------- Controls & Search

    private void setupSearchAndControls() {
        b.etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // View Mode Toggle (Grid vs List)
        b.btnViewMode.setOnClickListener(v -> {
            isGridMode = !isGridMode;
            b.btnViewMode.setText(isGridMode ? "⊞" : "☰");
            fileAdapter.setViewType(isGridMode ? FileListAdapter.VIEW_TYPE_GRID : FileListAdapter.VIEW_TYPE_LIST);
            b.rvFiles.setLayoutManager(isGridMode ? new GridLayoutManager(this, 2) : new LinearLayoutManager(this));
        });

        // Sort Button
        b.btnSort.setOnClickListener(v -> showSortDialog());
    }

    private void showSortDialog() {
        String[] options = {"Date (Newest first)", "Name (A to Z)", "Size (Largest first)"};
        new AlertDialog.Builder(this)
                .setTitle("Sort files by")
                .setSingleChoiceItems(options, sortMode, (dialog, which) -> {
                    sortMode = which;
                    if (sortMode == SORT_DATE_DESC) b.btnSort.setText("⇅ Date");
                    else if (sortMode == SORT_NAME_ASC) b.btnSort.setText("⇅ Name");
                    else if (sortMode == SORT_SIZE_DESC) b.btnSort.setText("⇅ Size");

                    sortFileList(currentFiles);
                    applyFilter(b.etSearch.getText().toString());
                    dialog.dismiss();
                })
                .show();
    }

    // ---------------------------------------------------------------- Media Preview Lightbox

    private void showMediaPreview(File file) {
        if (file == null || !file.exists()) return;

        Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xFA04060C));
        }

        DialogMediaPreviewBinding db = DialogMediaPreviewBinding.inflate(LayoutInflater.from(this));
        dialog.setContentView(db.getRoot());

        String name = file.getName();
        db.tvPreviewName.setText(name);
        String sizeStr = formatFileSize(file.length());
        boolean isVid = NativeThumbnailLoader.isVideo(name.toLowerCase(Locale.ROOT));

        db.tvPreviewMeta.setText(sizeStr + " • " + (isVid ? "Video" : "Photo"));
        db.tvPreviewIcon.setText(isVid ? "🎬" : "🖼️");

        if (isVid) {
            db.ivPreviewFull.setVisibility(View.GONE);
            db.vvPreviewFull.setVisibility(View.VISIBLE);
            db.pbPreviewLoading.setVisibility(View.VISIBLE);

            MediaController mc = new MediaController(this);
            mc.setAnchorView(db.vvPreviewFull);
            db.vvPreviewFull.setMediaController(mc);
            db.vvPreviewFull.setVideoPath(file.getAbsolutePath());
            db.vvPreviewFull.setOnPreparedListener(mp -> {
                db.pbPreviewLoading.setVisibility(View.GONE);
                db.vvPreviewFull.start();
            });
            db.vvPreviewFull.setOnErrorListener((mp, what, extra) -> {
                db.pbPreviewLoading.setVisibility(View.GONE);
                toast("Can't play video directly — use Share to open");
                return true;
            });
        } else {
            db.vvPreviewFull.setVisibility(View.GONE);
            db.ivPreviewFull.setVisibility(View.VISIBLE);
            db.pbPreviewLoading.setVisibility(View.VISIBLE);

            ioExecutor.execute(() -> {
                Bitmap bmp = decodeSampledBitmap(file.getAbsolutePath(), 1200, 1200);
                mainHandler.post(() -> {
                    db.pbPreviewLoading.setVisibility(View.GONE);
                    if (bmp != null) {
                        db.ivPreviewFull.setImageBitmap(bmp);
                    } else {
                        toast("Failed to preview image");
                    }
                });
            });
        }

        db.btnClosePreview.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (isVid && db.vvPreviewFull.isPlaying()) {
                db.vvPreviewFull.stopPlayback();
            }
        });

        db.btnShareFile.setOnClickListener(v -> shareFileWithSystem(file));
        dialog.show();
    }

    private Bitmap decodeSampledBitmap(String path, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void openFileWithSystem(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            String mime = getMimeType(file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open with"));
        } catch (Exception e) {
            shareFileWithSystem(file);
        }
    }

    private void shareFileWithSystem(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            String mime = getMimeType(file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime != null ? mime : "*/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share " + file.getName()));
        } catch (Exception e) {
            toast("Error sharing file: " + e.getMessage());
        }
    }

    private String getMimeType(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String ext = name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return "*/*";
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // ---------------------------------------------------------------- Ecosystem Radar

    private void setupRadar() {
        b.btnRefreshRadar.setOnClickListener(v -> {
            if (discoveryManager != null) {
                lastRenderedDevicesKey = "";
                discoveryManager.triggerScan();
                toast("Rescanning Wi-Fi network for Fast Share devices…");
            }
        });
    }

    private void attachServiceDiscovery() {
        DiscoveryManager serviceDiscovery = ShareService.getDiscovery();
        if (serviceDiscovery == null) return;
        discoveryManager = serviceDiscovery;
        discoveryManager.setCallback(this::onRadarDevicesUpdated);
    }

    private void onRadarDevicesUpdated(List<DiscoveryManager.Device> devices) {
        runOnUiThread(() -> {
            int count = (devices == null) ? 0 : devices.size();
            b.tvNearbySummary.setText(count + " nearby");

            // Build fingerprint key to avoid destructive removeAllViews when devices are unchanged
            StringBuilder sb = new StringBuilder();
            if (devices != null) {
                for (DiscoveryManager.Device d : devices) {
                    sb.append(d.ip).append(":").append(d.port).append(":").append(d.name).append(";");
                }
            }
            String currentKey = sb.toString();
            if (currentKey.equals(lastRenderedDevicesKey)) {
                return; // Unchanged, keep existing views and touch listeners intact
            }
            lastRenderedDevicesKey = currentKey;

            b.devicesContainer.removeAllViews();
            if (devices == null || devices.isEmpty()) {
                b.radarSearchingView.setVisibility(View.VISIBLE);
                b.devicesContainer.addView(b.radarSearchingView);
                b.tvDeviceCountBadge.setText("SCANNING");
                b.tvDeviceCountBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            } else {
                b.radarSearchingView.setVisibility(View.GONE);
                b.tvDeviceCountBadge.setText(devices.size() + " FOUND");
                b.tvDeviceCountBadge.setTextColor(ContextCompat.getColor(this, R.color.text_emerald));

                for (DiscoveryManager.Device device : devices) {
                    ItemDeviceCardBinding db = ItemDeviceCardBinding.inflate(
                            getLayoutInflater(), b.devicesContainer, false
                    );
                    db.tvDeviceName.setText(device.name);
                    db.tvDeviceIp.setText(device.ip + ":" + device.port);
                    db.tvDeviceIcon.setText(device.isWindows() ? "💻" : "📱");

                    db.btnBrowseDevice.setOnClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        Intent intent = new Intent(this, RemoteBrowserActivity.class);
                        intent.putExtra(RemoteBrowserActivity.EXTRA_URL, "http://" + device.ip + ":" + device.port);
                        intent.putExtra(RemoteBrowserActivity.EXTRA_DEVICE_NAME, device.name);
                        intent.putExtra(RemoteBrowserActivity.EXTRA_DEVICE_IP, device.ip);
                        intent.putExtra(RemoteBrowserActivity.EXTRA_DEVICE_PORT, device.port);
                        intent.putExtra(RemoteBrowserActivity.EXTRA_IS_WINDOWS, device.isWindows());
                        startActivity(intent);
                    });

                    db.btnSendToDevice.setOnClickListener(v -> {
                        pendingTargetIp = device.ip;
                        pendingTargetPort = device.port;
                        pendingTargetName = device.name;
                        fileChooserLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT)
                                .setType("*/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
                    });

                    b.devicesContainer.addView(db.getRoot());
                }
            }
        });
    }

    // ---------------------------------------------------------------- Bottom Navigation Dock

    private void setupTabNavigation() {
        b.btnNavFiles.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); switchTab(TAB_FILES); });
        b.btnNavRadar.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); switchTab(TAB_RADAR); });
        b.btnNavTransfers.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); switchTab(TAB_TRANSFERS); });
        b.btnNavUpload.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            fileChooserLauncher.launch(new Intent(Intent.ACTION_GET_CONTENT)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true));
        });
    }

    private void switchTab(int tab) {
        currentTab = tab;

        b.filesTabContent.setVisibility(tab == TAB_FILES && hasStoragePermission() ? View.VISIBLE : View.GONE);
        b.radarTabContent.setVisibility(tab == TAB_RADAR ? View.VISIBLE : View.GONE);
        b.transfersTabContent.setVisibility(tab == TAB_TRANSFERS ? View.VISIBLE : View.GONE);

        // Spring reveal on the incoming tab (M3 Expressive motion physics)
        View incoming = tab == TAB_FILES ? b.filesTabContent
                : tab == TAB_RADAR ? b.radarTabContent : b.transfersTabContent;
        if (incoming.getVisibility() == View.VISIBLE) {
            incoming.setAlpha(0f);
            incoming.setTranslationY(14f);
            incoming.animate().alpha(1f).translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(0.9f))
                    .start();
        }

        // Update tab styling (labels + icons)
        int active = ContextCompat.getColor(this, R.color.text_cyan);
        int inactive = ContextCompat.getColor(this, R.color.text_muted);

        b.btnNavFiles.setBackgroundResource(tab == TAB_FILES ? R.drawable.glass_tab_active_bg : R.drawable.glass_tab_inactive_bg);
        b.tvNavFilesLabel.setTextColor(ContextCompat.getColor(this, tab == TAB_FILES ? R.color.accent_cyan : R.color.text_secondary));
        b.ivNavFiles.setColorFilter(tab == TAB_FILES ? active : inactive);

        b.btnNavRadar.setBackgroundResource(tab == TAB_RADAR ? R.drawable.glass_tab_active_bg : R.drawable.glass_tab_inactive_bg);
        b.tvNavRadarLabel.setTextColor(ContextCompat.getColor(this, tab == TAB_RADAR ? R.color.accent_cyan : R.color.text_secondary));
        b.ivNavRadar.setColorFilter(tab == TAB_RADAR ? active : inactive);

        b.btnNavTransfers.setBackgroundResource(tab == TAB_TRANSFERS ? R.drawable.glass_tab_active_bg : R.drawable.glass_tab_inactive_bg);
        b.tvNavTransfersLabel.setTextColor(ContextCompat.getColor(this, tab == TAB_TRANSFERS ? R.color.accent_cyan : R.color.text_secondary));
        b.ivNavTransfers.setColorFilter(tab == TAB_TRANSFERS ? active : inactive);

        // Radar breathing pulse only while the tab is visible
        if (tab == TAB_RADAR) startRadarPulse(); else stopRadarPulse();

        if (tab == TAB_TRANSFERS) {
            updateTransfersTelemetry();
        }
    }

    private void updateTransfersTelemetry() {
        boolean up = ShareService.isUp();
        String url = ShareService.getServerUrl(this);
        b.tvServerStatusSummary.setText(up ? ("Server running at " + url + " • Ready for PC transfers")
                : "Server stopped • Tap ⚡ on top bar to start");

        ShareServer s = ShareService.getServer();
        long down = s != null ? s.getDownloadedBytes() : 0;
        long uld = s != null ? s.getUploadedBytes() : 0;
        b.tvSessionDown.setText(formatFileSize(down));
        b.tvSessionUp.setText(formatFileSize(uld));
    }

    // ---------------------------------------------------------------- Top Bar & Server Controls

    private void setupTopBar() {
        b.btnHome.setOnClickListener(v -> {
            hideServerSheet();
            loadDirectory(rootDir);
        });

        b.btnPower.setOnClickListener(v -> {
            if (ShareService.isUp()) {
                ShareService.stop(this);
                toast("Fast Share server stopped");
            } else {
                ShareService.start(this);
                toast("Starting Fast Share gigabit server…");
            }
            b.getRoot().postDelayed(this::updateServerStatusUi, 500);
        });

        View.OnClickListener copyUrl = v -> {
            String url = ShareService.getServerUrl(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                toast("Address copied: " + url);
            }
        };

        b.btnHost.setOnClickListener(copyUrl);
        b.statusText.setOnClickListener(copyUrl);

        b.btnServerSheet.setOnClickListener(v -> toggleServerSheet());
        b.tvNearbySummary.setOnClickListener(v -> switchTab(TAB_RADAR));
    }

    private void updateServerStatusUi() {
        boolean up = ShareService.isUp();
        String ip = ShareService.getLanIp();
        int port = Prefs.getPort(this);
        String label = up ? ((ip != null ? ip : "127.0.0.1") + ":" + port) : "SERVER OFFLINE";

        b.statusText.setText(label);
        b.tvAccessSummary.setText("User " + Prefs.getUser(this) + " • Password protected");
        b.tvNearbySummary.setText(discoveryManager == null ? "Nearby" : (discoveryManager.getActiveDevices().size() + " nearby"));
        b.statusDot.setBackgroundResource(up ? R.drawable.dot_on : R.drawable.dot_off);
        b.btnPower.setColorFilter(ContextCompat.getColor(this, up ? R.color.accent_rose : R.color.accent_cyan));
    }

    // ---------------------------------------------------------------- Server Settings Sheet

    private void setupServerSettingsSheet() {
        b.etPort.setText(String.valueOf(Prefs.getPort(this)));
        b.etUser.setText(Prefs.getUser(this));
        b.etPass.setText(Prefs.getPassword(this));
        b.cbBoot.setChecked(Prefs.getBoot(this));

        b.btnSaveServer.setOnClickListener(v -> {
            String portStr = b.etPort.getText().toString().trim();
            String userStr = b.etUser.getText().toString().trim();
            String passStr = b.etPass.getText().toString().trim();

            int port = 8000;
            try {
                port = Integer.parseInt(portStr);
                if (port < 1024 || port > 65535) throw new NumberFormatException();
            } catch (Exception e) {
                toast("Port must be between 1024 and 65535");
                return;
            }

            if (userStr.isEmpty() || userStr.contains(":") || userStr.contains(" ")) {
                toast("User name cannot be empty or contain : / spaces");
                return;
            }

            Prefs.setPort(this, port);
            Prefs.setUser(this, userStr);
            Prefs.setPassword(this, passStr);
            Prefs.setBoot(this, b.cbBoot.isChecked());

            toast("Settings saved • Restarting server…");
            hideServerSheet();

            if (ShareService.isUp()) {
                ShareService.stop(this);
                b.getRoot().postDelayed(() -> {
                    ShareService.start(this);
                    updateServerStatusUi();
                }, 600);
            }
        });
    }

    private void toggleServerSheet() {
        if (b.serverSheet.getVisibility() == View.VISIBLE) {
            hideServerSheet();
        } else {
            // Sheet springs up and settles — clay, not cardboard
            b.serverSheet.setVisibility(View.VISIBLE);
            b.serverSheet.setAlpha(0f);
            b.serverSheet.setTranslationY(60f);
            b.serverSheet.animate().alpha(1f).translationY(0f)
                    .setDuration(280)
                    .setInterpolator(new OvershootInterpolator(0.8f))
                    .start();
        }
    }

    private void hideServerSheet() {
        if (b.serverSheet.getVisibility() != View.VISIBLE) return;
        b.serverSheet.animate().cancel();
        b.serverSheet.animate().alpha(0f).translationY(40f)
                .setDuration(160)
                .withEndAction(() -> {
                    b.serverSheet.setVisibility(View.GONE);
                    b.serverSheet.setAlpha(1f);
                    b.serverSheet.setTranslationY(0f);
                })
                .start();
    }

    // ---------------------------------------------------------------- Micro-interactions (spring physics)

    private void setupMicroInteractions() {
        attachSpringPress(b.btnNavUpload);
        attachSpringPress(b.btnHome);
        attachSpringPress(b.btnHost);
        attachSpringPress(b.btnPower);
        attachSpringPress(b.btnServerSheet);
        attachSpringPress(b.btnUp);
        attachSpringPress(b.btnGrant);
        attachSpringPress(b.btnRefreshRadar);
        attachSpringPress(b.btnSaveServer);
    }

    /** Press = compress, release = spring back with a soft overshoot. */
    private void attachSpringPress(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(300)
                            .setInterpolator(new OvershootInterpolator(2.4f))
                            .start();
                    break;
            }
            return false; // never consume — click listeners still fire
        });
    }

    // ---------------------------------------------------------------- Radar ambient pulse

    private void startRadarPulse() {
        pulseRing(b.radarRing1, 0);
        pulseRing(b.radarRing2, 450);
    }

    private void pulseRing(View ring, long delay) {
        ring.animate().cancel();
        ring.setScaleX(1f);
        ring.setScaleY(1f);
        ring.setAlpha(0.85f);
        ring.animate().scaleX(1.28f).scaleY(1.28f).alpha(0.08f)
                .setDuration(1700)
                .setStartDelay(delay)
                .withEndAction(() -> {
                    if (currentTab == TAB_RADAR) pulseRing(ring, 0);
                })
                .start();
    }

    private void stopRadarPulse() {
        b.radarRing1.animate().cancel();
        b.radarRing2.animate().cancel();
    }

    // ---------------------------------------------------------------- Back Navigation

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (b.serverSheet.getVisibility() == View.VISIBLE) {
                    hideServerSheet();
                } else if (currentTab != TAB_FILES) {
                    switchTab(TAB_FILES);
                } else if (currentDirectory != null && !currentDirectory.equals(rootDir)) {
                    navigateUp();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
