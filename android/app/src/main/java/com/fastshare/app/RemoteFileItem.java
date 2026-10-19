package com.fastshare.app;

import org.json.JSONObject;

public class RemoteFileItem {
    public String name;
    public String path;
    public boolean isDir;
    public boolean isDrive;
    public long size;
    public long free;
    public long used;
    public String icon;
    public long mtime;

    public static RemoteFileItem fromJson(JSONObject obj) {
        RemoteFileItem item = new RemoteFileItem();
        item.name = obj.optString("name", "Unknown");
        item.path = obj.optString("path", "");
        item.isDir = obj.optBoolean("is_dir", false);
        item.isDrive = obj.optBoolean("is_drive", false);
        item.size = obj.optLong("size", 0);
        item.free = obj.optLong("free", 0);
        item.used = obj.optLong("used", 0);
        item.icon = obj.optString("icon", item.isDrive ? "💽" : (item.isDir ? "📁" : "📄"));
        item.mtime = obj.optLong("mtime", 0);
        return item;
    }

    public boolean isMedia() {
        if (isDir || isDrive || name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".mp4")
                || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".webm")
                || lower.endsWith(".3gp");
    }

    public boolean isVideo() {
        if (isDir || isDrive || name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov")
                || lower.endsWith(".webm") || lower.endsWith(".3gp");
    }
}
