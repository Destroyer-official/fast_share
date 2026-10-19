package com.fastshare.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fastshare.app.databinding.ItemFileGridBinding;
import com.fastshare.app.databinding.ItemFileListBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RemoteFileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_GRID = 0;
    public static final int VIEW_TYPE_LIST = 1;

    public interface OnRemoteItemClickListener {
        void onItemClick(RemoteFileItem item);
        void onActionClick(RemoteFileItem item);
    }

    private final List<RemoteFileItem> items = new ArrayList<>();
    private int currentViewType = VIEW_TYPE_GRID;
    private OnRemoteItemClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d/M/yyyy", Locale.getDefault());

    private String serverIp = "";
    private int serverPort = 8000;
    private String authToken = "";

    public void setServerInfo(String ip, int port, String token) {
        this.serverIp = ip;
        this.serverPort = port;
        this.authToken = token;
    }

    public void setListener(OnRemoteItemClickListener listener) {
        this.listener = listener;
    }

    public void setViewType(int viewType) {
        if (this.currentViewType != viewType) {
            this.currentViewType = viewType;
            notifyDataSetChanged();
        }
    }

    public int getViewType() {
        return currentViewType;
    }

    public void setItems(List<RemoteFileItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return currentViewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_GRID) {
            ItemFileGridBinding b = ItemFileGridBinding.inflate(inflater, parent, false);
            return new GridViewHolder(b);
        } else {
            ItemFileListBinding b = ItemFileListBinding.inflate(inflater, parent, false);
            return new ListViewHolder(b);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        RemoteFileItem item = items.get(position);
        if (holder instanceof GridViewHolder) {
            ((GridViewHolder) holder).bind(item);
        } else if (holder instanceof ListViewHolder) {
            ((ListViewHolder) holder).bind(item);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static String getFileIcon(RemoteFileItem item) {
        if (item.isDrive) return item.icon != null && !item.icon.isEmpty() ? item.icon : "💽";
        if (item.isDir) return "📁";
        String lower = item.name != null ? item.name.toLowerCase() : "";
        if (lower.endsWith(".apk") || lower.endsWith(".apks")) return "📦";
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")) return "🗜️";
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".avi")) return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".ogg")) return "🎵";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")) return "🖼️";
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) return "📝";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv")) return "📊";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📽️";
        if (lower.endsWith(".exe") || lower.endsWith(".msi")) return "⚙️";
        if (lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".json")) return "🌐";
        return "📄";
    }

    class GridViewHolder extends RecyclerView.ViewHolder {
        private final ItemFileGridBinding b;

        GridViewHolder(ItemFileGridBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(RemoteFileItem item) {
            b.tvFileName.setText(item.name);
            b.tvFolderIcon.setText(getFileIcon(item));
            b.videoBadge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

            if (item.isMedia() && serverIp != null && !serverIp.isEmpty()) {
                String thumbUrl;
                try {
                    String enc = java.net.URLEncoder.encode(item.path, "UTF-8");
                    thumbUrl = "http://" + serverIp + ":" + serverPort + "/api/thumb?path=" + enc;
                    if (authToken != null && !authToken.isEmpty()) {
                        thumbUrl += "&token=" + java.net.URLEncoder.encode(authToken, "UTF-8");
                    }
                } catch (Exception e) {
                    thumbUrl = null;
                }
                if (thumbUrl != null) {
                    NativeThumbnailLoader.get().loadRemoteThumbnail(thumbUrl, b.ivThumb, b.tvFolderIcon, 250, 250);
                } else {
                    b.ivThumb.setVisibility(View.GONE);
                    b.tvFolderIcon.setVisibility(View.VISIBLE);
                }
            } else {
                b.ivThumb.setVisibility(View.GONE);
                b.tvFolderIcon.setVisibility(View.VISIBLE);
            }

            if (item.isDrive) {
                b.tvFileMeta.setText(item.size > 0 ? (formatSize(item.free) + " free of " + formatSize(item.size)) : "STORAGE DRIVE");
                b.btnAction.setText("Open Drive");
            } else if (item.isDir) {
                b.tvFileMeta.setText("DIRECTORY");
                b.btnAction.setText("Open");
            } else {
                String dateStr = item.mtime > 0 ? dateFormat.format(new Date(item.mtime * 1000L)) : "";
                b.tvFileMeta.setText(formatSize(item.size) + (dateStr.isEmpty() ? "" : " • " + dateStr));
                b.btnAction.setText("⬇ Download");
            }

            View.OnClickListener clickAction = v -> {
                if (listener != null) listener.onItemClick(item);
            };
            itemView.setOnClickListener(clickAction);
            b.thumbContainer.setOnClickListener(clickAction);
            b.btnAction.setOnClickListener(v -> {
                if (listener != null) listener.onActionClick(item);
            });
        }
    }

    class ListViewHolder extends RecyclerView.ViewHolder {
        private final ItemFileListBinding b;

        ListViewHolder(ItemFileListBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(RemoteFileItem item) {
            b.tvFileName.setText(item.name);
            b.tvFolderIcon.setText(getFileIcon(item));
            b.videoBadge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);

            if (item.isMedia() && serverIp != null && !serverIp.isEmpty()) {
                String thumbUrl;
                try {
                    String enc = java.net.URLEncoder.encode(item.path, "UTF-8");
                    thumbUrl = "http://" + serverIp + ":" + serverPort + "/api/thumb?path=" + enc;
                    if (authToken != null && !authToken.isEmpty()) {
                        thumbUrl += "&token=" + java.net.URLEncoder.encode(authToken, "UTF-8");
                    }
                } catch (Exception e) {
                    thumbUrl = null;
                }
                if (thumbUrl != null) {
                    NativeThumbnailLoader.get().loadRemoteThumbnail(thumbUrl, b.ivThumb, b.tvFolderIcon, 120, 120);
                } else {
                    b.ivThumb.setVisibility(View.GONE);
                    b.tvFolderIcon.setVisibility(View.VISIBLE);
                }
            } else {
                b.ivThumb.setVisibility(View.GONE);
                b.tvFolderIcon.setVisibility(View.VISIBLE);
            }

            if (item.isDrive) {
                b.tvFileMeta.setText(item.size > 0 ? (formatSize(item.free) + " free of " + formatSize(item.size)) : "STORAGE DRIVE");
                b.btnAction.setText("Open Drive");
            } else if (item.isDir) {
                b.tvFileMeta.setText("DIRECTORY");
                b.btnAction.setText("Open");
            } else {
                String dateStr = item.mtime > 0 ? dateFormat.format(new Date(item.mtime * 1000L)) : "";
                b.tvFileMeta.setText(formatSize(item.size) + (dateStr.isEmpty() ? "" : " • " + dateStr));
                b.btnAction.setText("⬇ Download");
            }

            View.OnClickListener clickAction = v -> {
                if (listener != null) listener.onItemClick(item);
            };
            itemView.setOnClickListener(clickAction);
            b.thumbContainer.setOnClickListener(clickAction);
            b.btnAction.setOnClickListener(v -> {
                if (listener != null) listener.onActionClick(item);
            });
        }
    }
}
