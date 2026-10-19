package com.fastshare.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fastshare.app.databinding.ItemBreadcrumbBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BreadcrumbAdapter extends RecyclerView.Adapter<BreadcrumbAdapter.CrumbViewHolder> {

    public interface OnCrumbClickListener {
        void onCrumbClick(File folder);
    }

    public static class CrumbItem {
        public final String name;
        public final File folder;

        public CrumbItem(String name, File folder) {
            this.name = name;
            this.folder = folder;
        }
    }

    private final List<CrumbItem> crumbs = new ArrayList<>();
    private OnCrumbClickListener listener;

    public void setListener(OnCrumbClickListener listener) {
        this.listener = listener;
    }

    public void setCrumbs(List<CrumbItem> newCrumbs) {
        crumbs.clear();
        if (newCrumbs != null) {
            crumbs.addAll(newCrumbs);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CrumbViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBreadcrumbBinding b = ItemBreadcrumbBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new CrumbViewHolder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull CrumbViewHolder holder, int position) {
        CrumbItem item = crumbs.get(position);
        holder.b.btnCrumb.setText(item.name);
        holder.b.tvCrumbSep.setVisibility(position == crumbs.size() - 1 ? View.GONE : View.VISIBLE);
        holder.b.btnCrumb.setOnClickListener(v -> {
            if (listener != null) listener.onCrumbClick(item.folder);
        });
    }

    @Override
    public int getItemCount() {
        return crumbs.size();
    }

    static class CrumbViewHolder extends RecyclerView.ViewHolder {
        final ItemBreadcrumbBinding b;
        CrumbViewHolder(ItemBreadcrumbBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}
