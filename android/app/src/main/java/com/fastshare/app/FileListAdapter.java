package com.fastshare.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fastshare.app.databinding.ItemFileGridBinding;
import com.fastshare.app.databinding.ItemFileListBinding;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_GRID = 0;
    public static final int VIEW_TYPE_LIST = 1;

    public interface OnItemClickListener {
        void onFolderClick(File file);
        void onMediaClick(File file);
        void onFileClick(File file);
    }

    private final List<File> items = new ArrayList<>();
    private int currentViewType = VIEW_TYPE_GRID;
    private OnItemClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d/M/yyyy", Locale.getDefault());

    public void setListener(OnItemClickListener listener) {
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

    public void setItems(List<File> newItems) {
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
        File file = items.get(position);
        if (holder instanceof GridViewHolder) {
            ((GridViewHolder) holder).bind(file);
        } else if (holder instanceof ListViewHolder) {
            ((ListViewHolder) holder).bind(file);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String getFileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "📕";
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz")) return "🗜️";
        if (lower.endsWith(".apk")) return "🤖";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") || lower.endsWith(".aac")) return "🎵";
        if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md") || lower.endsWith(".json")) return "📄";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "📘";
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "📊";
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📙";
        return "📄";
    }

    class GridViewHolder extends RecyclerView.ViewHolder {
        private final ItemFileGridBinding b;

        GridViewHolder(ItemFileGridBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(File file) {
            final boolean isDir = file.isDirectory();
            final String name = file.getName();
            b.tvFileName.setText(name);
            b.thumbContainer.setContentDescription(isDir ? "Folder " + name : "Preview " + name);

            String dateStr = dateFormat.format(new Date(file.lastModified()));
            if (isDir) {
                b.tvFileMeta.setText("DIRECTORY • " + dateStr);
                b.tvFolderIcon.setVisibility(View.VISIBLE);
                b.tvFolderIcon.setText("📁");
                b.ivThumb.setVisibility(View.GONE);
                b.videoBadge.setVisibility(View.GONE);
                b.btnAction.setText("Open");
            } else {
                b.tvFileMeta.setText(formatSize(file.length()) + " • " + dateStr);
                boolean isImg = NativeThumbnailLoader.isImage(name.toLowerCase());
                boolean isVid = NativeThumbnailLoader.isVideo(name.toLowerCase());

                if (isImg || isVid) {
                    b.tvFolderIcon.setVisibility(View.GONE);
                    b.ivThumb.setVisibility(View.VISIBLE);
                    b.ivThumb.setImageDrawable(null);
                    b.ivThumb.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    b.videoBadge.setVisibility(isVid ? View.VISIBLE : View.GONE);
                    NativeThumbnailLoader.get().loadThumbnail(file, b.ivThumb, 220, 200, 0);
                    b.btnAction.setText("View");
                } else {
                    b.tvFolderIcon.setVisibility(View.VISIBLE);
                    b.tvFolderIcon.setText(getFileIcon(name));
                    b.ivThumb.setVisibility(View.GONE);
                    b.videoBadge.setVisibility(View.GONE);
                    b.btnAction.setText("Share");
                }
            }

            View.OnClickListener click = v -> {
                if (listener == null) return;
                if (isDir) listener.onFolderClick(file);
                else if (NativeThumbnailLoader.isMedia(name.toLowerCase())) listener.onMediaClick(file);
                else listener.onFileClick(file);
            };

            itemView.setOnClickListener(click);
            b.btnAction.setOnClickListener(click);
        }
    }

    class ListViewHolder extends RecyclerView.ViewHolder {
        private final ItemFileListBinding b;

        ListViewHolder(ItemFileListBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(File file) {
            final boolean isDir = file.isDirectory();
            final String name = file.getName();
            b.tvFileName.setText(name);
            b.thumbContainer.setContentDescription(isDir ? "Folder " + name : "Preview " + name);

            String dateStr = dateFormat.format(new Date(file.lastModified()));
            if (isDir) {
                b.tvFileMeta.setText("DIRECTORY • " + dateStr);
                b.tvFolderIcon.setVisibility(View.VISIBLE);
                b.tvFolderIcon.setText("📁");
                b.ivThumb.setVisibility(View.GONE);
                b.videoBadge.setVisibility(View.GONE);
                b.btnAction.setText("Open");
            } else {
                b.tvFileMeta.setText(formatSize(file.length()) + " • " + dateStr);
                boolean isImg = NativeThumbnailLoader.isImage(name.toLowerCase());
                boolean isVid = NativeThumbnailLoader.isVideo(name.toLowerCase());

                if (isImg || isVid) {
                    b.tvFolderIcon.setVisibility(View.GONE);
                    b.ivThumb.setVisibility(View.VISIBLE);
                    b.ivThumb.setImageDrawable(null);
                    b.ivThumb.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    b.videoBadge.setVisibility(isVid ? View.VISIBLE : View.GONE);
                    NativeThumbnailLoader.get().loadThumbnail(file, b.ivThumb, 96, 96, 0);
                    b.btnAction.setText("View");
                } else {
                    b.tvFolderIcon.setVisibility(View.VISIBLE);
                    b.tvFolderIcon.setText(getFileIcon(name));
                    b.ivThumb.setVisibility(View.GONE);
                    b.videoBadge.setVisibility(View.GONE);
                    b.btnAction.setText("Share");
                }
            }

            View.OnClickListener click = v -> {
                if (listener == null) return;
                if (isDir) listener.onFolderClick(file);
                else if (NativeThumbnailLoader.isMedia(name.toLowerCase())) listener.onMediaClick(file);
                else listener.onFileClick(file);
            };

            itemView.setOnClickListener(click);
            b.btnAction.setOnClickListener(click);
        }
    }
}
