package com.aurora.app.ui.flash;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.app.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「应用卸载冻结」页的设备应用列表适配器：只放包名，支持筛选与多选。
 *
 * <p>列表内容来自 {@code adb shell pm list packages}，是<b>目标机</b>上的包名 ——
 * 不是本机。显示名刻意只用包名：{@code pm list packages} 不返回应用显示名，
 * 要拿到它得把每个 APK 从目标机拉下来解析，几百个包就是几百次传输，代价与收益完全不成比例。
 * 玩机场景下认包名本来就是常态，筛选框也按包名过滤。
 *
 * <p>把「筛选后的列表」与「全量列表」分成两个 List：{@code position} 是相对可见列表的下标，
 * 直接拿去索引全量列表会是错的（筛选后错位）。
 */
public final class DeviceAppAdapter extends RecyclerView.Adapter<DeviceAppAdapter.Holder> {

    /** 勾选数量变化回调（主线程）。 */
    public interface SelectionListener {
        void onSelectionChanged(int count);
    }

    /** 全量包名。 */
    private final List<String> all = new ArrayList<>();
    /** 当前筛选后可见的包名。 */
    private final List<String> visible = new ArrayList<>();
    /**
     * 已勾选的包名。
     *
     * <p>用 {@link LinkedHashSet}：顺序稳定（卸载输出里包的先后跟勾选顺序一致，
     * 用户对照起来不费劲），且天然去重。
     */
    private final Set<String> selected = new LinkedHashSet<>();

    private final SelectionListener listener;

    private String filter = "";

    public DeviceAppAdapter(SelectionListener listener) {
        this.listener = listener;
    }

    /** 重新灌入列表；勾选状态一并清空（换了范围就是重新开始）。 */
    public void submitList(List<String> packages) {
        all.clear();
        if (packages != null) {
            all.addAll(packages);
        }
        selected.clear();
        rebuildVisible();
        notifyDataSetChanged();
        notifySelection();
    }

    /** 按包名筛选（大小写不敏感，空串表示不过滤）。 */
    public void setFilter(String text) {
        filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        rebuildVisible();
        notifyDataSetChanged();
    }

    /** 当前勾选的包名（有序副本）。 */
    public List<String> selectedPackages() {
        return new ArrayList<>(selected);
    }

    /** 已勾选数量。 */
    public int selectedCount() {
        return selected.size();
    }

    private void rebuildVisible() {
        visible.clear();
        for (String pkg : all) {
            if (filter.isEmpty() || pkg.toLowerCase(Locale.ROOT).contains(filter)) {
                visible.add(pkg);
            }
        }
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(selected.size());
        }
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device_app, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        String pkg = visible.get(position);
        h.tvPackage.setText(pkg);
        h.cbSelected.setChecked(selected.contains(pkg));
        // 整行点击切换勾选（勾选框自身不可点，热区覆盖整行内边距）。
        h.itemView.setOnClickListener(v -> {
            if (!selected.remove(pkg)) {
                selected.add(pkg);
            }
            h.cbSelected.setChecked(selected.contains(pkg));
            notifySelection();
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final CheckBox cbSelected;
        final TextView tvPackage;

        Holder(@NonNull View itemView) {
            super(itemView);
            cbSelected = itemView.findViewById(R.id.cbDeviceApp);
            tvPackage = itemView.findViewById(R.id.tvDeviceAppPackage);
        }
    }
}
