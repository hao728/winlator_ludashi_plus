package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置中心：扫描 /Download/Winlator/Configs/ 下的 JSON 配置文件，
 * 支持本地/Steam 分类过滤、预览配置差异、将配置应用到已有游戏容器、删除与导入。
 */
public class ConfigCenterFragment extends Fragment {
    private static final String TAG = "ConfigCenter";
    private static final File CONFIGS_ROOT =
            new File(Environment.getExternalStorageDirectory(), "Download/Winlator/Configs");

    private static final int TAB_ALL = 0;
    private static final int TAB_LOCAL = 1;
    private static final int TAB_STEAM = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<ConfigEntry> allEntries = new ArrayList<>();
    private final ArrayList<ConfigEntry> filteredEntries = new ArrayList<>();

    private ContainerManager containerManager;
    private ConfigAdapter adapter;
    private TextView emptyView;
    private int currentTab = TAB_ALL;
    private ActivityResultLauncher<String> importLauncher;

    /** 一条配置文件的解析结果。 */
    private static class ConfigEntry {
        final File file;
        String gameName;
        String source;      // local / steam
        String wineVersion;
        String graphicsDriver;
        String dxwrapper;
        String emulator;
        long modifiedAt;
        long size;

        ConfigEntry(File file) {
            this.file = file;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        importLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImportConfigPicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        containerManager = new ContainerManager(requireContext());

        int pad = dp(12);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0D12);
        root.setPadding(pad, dp(8), pad, pad);

        // 分类 Tab
        TabLayout tabLayout = new TabLayout(requireContext(), null, com.google.android.material.R.style.Widget_Material3_TabLayout);
        tabLayout.setTabMode(TabLayout.MODE_FIXED);
        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
        tabLayout.addTab(tabLayout.newTab().setText("全部"));
        tabLayout.addTab(tabLayout.newTab().setText("本地游戏"));
        tabLayout.addTab(tabLayout.newTab().setText("Steam游戏"));
        tabLayout.setSelectedTabIndicatorColor(0xFF4FC3F7);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.bottomMargin = dp(8);
        root.addView(tabLayout, tabLp);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                applyFilter();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 列表 + 空状态
        FrameLayout content = new FrameLayout(requireContext());
        RecyclerView recyclerView = new RecyclerView(requireContext());
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setPadding(0, dp(4), 0, dp(4));
        adapter = new ConfigAdapter();
        recyclerView.setAdapter(adapter);
        content.addView(recyclerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = new TextView(requireContext());
        emptyView.setText("暂无配置文件\n点击右上角「导入配置文件」添加");
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setTextColor(0xFF8A8F98);
        emptyView.setTextSize(15);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Activity activity = getActivity();
        if (activity instanceof AppCompatActivity && ((AppCompatActivity) activity).getSupportActionBar() != null) {
            ((AppCompatActivity) activity).getSupportActionBar().setTitle("配置中心");
        }
        refreshList();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        MenuItem importItem = menu.add(0, 101, 0, "导入配置文件");
        importItem.setIcon(android.R.drawable.ic_menu_upload);
        importItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 101) {
            try {
                importLauncher.launch("application/json");
            } catch (Exception e) {
                Toast.makeText(getContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ==================== 扫描 ====================

    private void refreshList() {
        executor.execute(() -> {
            List<ConfigEntry> scanned = scanConfigs();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                allEntries.clear();
                allEntries.addAll(scanned);
                applyFilter();
            });
        });
    }

    private List<ConfigEntry> scanConfigs() {
        List<ConfigEntry> result = new ArrayList<>();
        if (!CONFIGS_ROOT.exists()) return result;

        // 1) local/ 与 steam/ 子目录
        File localDir = new File(CONFIGS_ROOT, "local");
        File steamDir = new File(CONFIGS_ROOT, "steam");
        collectJson(localDir, "local", result);
        collectJson(steamDir, "steam", result);

        // 2) Configs/ 根目录下旧格式 container_config_*.json
        File[] rootFiles = CONFIGS_ROOT.listFiles();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".json")) {
                    collectOne(f, "local", result);
                }
            }
        }
        // 按修改时间倒序
        Collections.sort(result, (a, b) -> Long.compare(b.modifiedAt, a.modifiedAt));
        return result;
    }

    private void collectJson(File dir, String defaultSource, List<ConfigEntry> out) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".json")) {
                collectOne(f, defaultSource, out);
            }
        }
    }

    private void collectOne(File file, String defaultSource, List<ConfigEntry> out) {
        try {
            String text = FileUtils.readString(file);
            if (text == null || text.trim().isEmpty()) return;
            JSONObject json = new JSONObject(text);

            ConfigEntry e = new ConfigEntry(file);
            e.gameName = json.optString("gameName", "");
            if (e.gameName.isEmpty()) e.gameName = json.optString("name", "");
            if (e.gameName.isEmpty()) e.gameName = basename(file.getName());
            e.source = json.optString("source", defaultSource);
            if (e.source.isEmpty()) e.source = defaultSource;
            e.wineVersion = json.optString("wineVersion", "");
            e.graphicsDriver = json.optString("graphicsDriver", "");
            e.dxwrapper = json.optString("dxwrapper", "");
            e.emulator = json.optString("emulator", "");
            e.modifiedAt = file.lastModified();
            e.size = file.length();
            out.add(e);
        } catch (Exception ex) {
            // 解析失败跳过
        }
    }

    private void applyFilter() {
        filteredEntries.clear();
        for (ConfigEntry e : allEntries) {
            if (currentTab == TAB_ALL) {
                filteredEntries.add(e);
            } else if (currentTab == TAB_LOCAL && "local".equals(e.source)) {
                filteredEntries.add(e);
            } else if (currentTab == TAB_STEAM && "steam".equals(e.source)) {
                filteredEntries.add(e);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) {
            emptyView.setVisibility(filteredEntries.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== 列表 Adapter ====================

    private class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(ctx);
            int m = dp(8);
            card.setUseCompatPadding(true);
            card.setRadius(dp(12));
            card.setCardElevation(dp(2));
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int pad = dp(14);
            row.setPadding(pad, pad, pad, pad);

            // 图标占位
            FrameLayout iconWrap = new FrameLayout(ctx);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(48), dp(48));
            ilp.setMargins(0, 0, dp(12), 0);
            iconWrap.setLayoutParams(ilp);
            TextView iconText = new TextView(ctx);
            iconText.setGravity(android.view.Gravity.CENTER);
            iconText.setTextColor(Color.WHITE);
            iconText.setTextSize(20);
            iconText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            iconWrap.addView(iconText, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(iconWrap);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(ctx);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(title);

            TextView summary = new TextView(ctx);
            summary.setTextColor(0xFFB0B6C0);
            summary.setTextSize(12);
            summary.setMaxLines(1);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(4);
            summary.setLayoutParams(slp);
            col.addView(summary);

            TextView meta = new TextView(ctx);
            meta.setTextSize(11);
            meta.setTextColor(0xFF8A8F98);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(4);
            meta.setLayoutParams(mlp);
            col.addView(meta);

            row.addView(col);
            card.addView(row);

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, m / 2, 0, m / 2);
            card.setLayoutParams(lp);
            return new VH(card, iconText, title, summary, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ConfigEntry e = filteredEntries.get(position);
            h.title.setText(e.gameName);
            h.summary.setText(buildSummary(e));
            h.meta.setText(formatTime(e.modifiedAt) + "  ·  " + formatSize(e.size) + "  ·  "
                    + ("steam".equals(e.source) ? "Steam" : "本地"));

            // 首字母彩色圆形图标
            String letter = e.gameName.isEmpty() ? "?" : e.gameName.substring(0, 1).toUpperCase(Locale.getDefault());
            h.iconText.setText(letter);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(colorFor(e.gameName));
            h.iconText.setBackground(bg);

            h.itemView.setOnClickListener(v -> showDetail(e));
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(e);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return filteredEntries.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView iconText;
            final TextView title;
            final TextView summary;
            final TextView meta;
            VH(@androidx.annotation.NonNull View itemView, TextView iconText, TextView title,
               TextView summary, TextView meta) {
                super(itemView);
                this.iconText = iconText;
                this.title = title;
                this.summary = summary;
                this.meta = meta;
            }
        }
    }

    private String buildSummary(ConfigEntry e) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, "Wine", e.wineVersion);
        appendPart(sb, "驱动", e.graphicsDriver);
        appendPart(sb, "DXWrapper", e.dxwrapper);
        appendPart(sb, "模拟器", e.emulator);
        return sb.length() == 0 ? "无关键配置" : sb.toString();
    }

    private void appendPart(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) return;
        if (sb.length() > 0) sb.append("  /  ");
        sb.append(label).append(": ").append(value);
    }

    // ==================== 详情 / 应用 / 删除 ====================

    private void showDetail(ConfigEntry e) {
        try {
            String text = FileUtils.readString(e.file);
            if (text == null) {
                Toast.makeText(getContext(), "无法读取配置文件", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject json = new JSONObject(text);

            // 用临时 Container 解析，得到与默认值的差异摘要
            String diffSummary = buildDiffSummary(json);

            StringBuilder body = new StringBuilder();
            body.append("来源：").append("steam".equals(e.source) ? "Steam 游戏" : "本地游戏").append('\n');
            body.append("文件：").append(e.file.getName()).append('\n');
            body.append("修改时间：").append(formatTime(e.modifiedAt)).append('\n');
            body.append("大小：").append(formatSize(e.size)).append('\n');
            body.append("──────────────\n");
            body.append("与默认值对比：\n");
            body.append(diffSummary.isEmpty() ? "  （全部为默认值）" : diffSummary);

            ScrollView sv = new ScrollView(requireContext());
            TextView tv = new TextView(requireContext());
            tv.setText(body.toString());
            tv.setTextColor(0xFFE6E9EF);
            tv.setTextSize(13);
            tv.setPadding(dp(20), dp(16), dp(20), dp(16));
            sv.addView(tv);

            new AlertDialog.Builder(requireContext())
                    .setTitle(e.gameName)
                    .setView(sv)
                    .setPositiveButton("应用到游戏", (d, w) -> pickGameToApply(e))
                    .setNeutralButton("删除", (d, w) -> confirmDelete(e))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception ex) {
            Toast.makeText(getContext(), "解析配置失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 构造配置与默认值的差异摘要。 */
    private String buildDiffSummary(JSONObject json) {
        StringBuilder sb = new StringBuilder();
        try {
            Container tmp = new Container(0);
            tmp.loadData(json);

            String defaultWine = WineInfo.MAIN_WINE_VERSION.identifier();
            appendDiffLine(sb, "Wine 版本", tmp.getWineVersion(), defaultWine);
            appendDiffLine(sb, "图形驱动", tmp.getGraphicsDriver(), Container.DEFAULT_GRAPHICS_DRIVER);
            appendDiffLine(sb, "DXWrapper", tmp.getDXWrapper(), Container.DEFAULT_DXWRAPPER);
            appendDiffLine(sb, "音频驱动", tmp.getAudioDriver(), Container.DEFAULT_AUDIO_DRIVER);
            appendDiffLine(sb, "模拟器", tmp.getEmulator(), Container.DEFAULT_EMULATOR);
            appendDiffLine(sb, "屏幕分辨率", tmp.getScreenSize(), Container.DEFAULT_SCREEN_SIZE);
            if (tmp.getBox64Version() != null && !tmp.getBox64Version().isEmpty()) {
                sb.append("  · Box64: ").append(tmp.getBox64Version()).append('\n');
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    private void appendDiffLine(StringBuilder sb, String label, String actual, String def) {
        String a = actual == null ? "" : actual;
        String d = def == null ? "" : def;
        if (!a.equals(d)) {
            sb.append("  · ").append(label).append(": ").append(a.isEmpty() ? "(默认)" : a).append('\n');
        }
    }

    private void pickGameToApply(ConfigEntry e) {
        if (containerManager == null) containerManager = new ContainerManager(requireContext());
        ArrayList<Shortcut> shortcuts = containerManager.loadShortcuts();
        if (shortcuts == null || shortcuts.isEmpty()) {
            Toast.makeText(getContext(), "没有可用的游戏，请先在游戏库添加游戏", Toast.LENGTH_LONG).show();
            return;
        }
        final CharSequence[] items = new CharSequence[shortcuts.size()];
        for (int i = 0; i < shortcuts.size(); i++) items[i] = shortcuts.get(i).name;
        new AlertDialog.Builder(requireContext())
                .setTitle("选择要应用到的游戏")
                .setItems(items, (d, which) -> applyConfigToGame(e, shortcuts.get(which)))
                .show();
    }

    private void applyConfigToGame(ConfigEntry config, Shortcut shortcut) {
        Container target = shortcut.container != null ? shortcut.container
                : containerManager.getContainerById(shortcut.getContainerId());
        if (target == null) {
            Toast.makeText(getContext(), "未找到游戏对应的容器", Toast.LENGTH_SHORT).show();
            return;
        }
        final Container targetRef = target;
        executor.execute(() -> {
            try {
                String srcText = FileUtils.readString(config.file);
                if (srcText == null) throw new Exception("配置文件读取失败");
                JSONObject src = new JSONObject(srcText);

                // 读取目标容器现有配置，逐字段覆盖（保留 id/name/rootDir 等身份信息）
                File cfgFile = targetRef.getConfigFile();
                JSONObject dst = cfgFile.exists()
                        ? new JSONObject(FileUtils.readString(cfgFile))
                        : new JSONObject();

                Iterator<String> it = src.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    // 不覆盖目标容器的身份与导出元信息
                    if ("id".equals(key) || "name".equals(key)
                            || "gameName".equals(key) || "source".equals(key)) continue;
                    dst.put(key, src.get(key));
                }
                FileUtils.writeString(cfgFile, dst.toString());
                // 让内存中的容器对象重新加载
                targetRef.loadData(dst);

                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(),
                                "已将「" + config.gameName + "」的配置应用到「" + shortcut.name + "」",
                                Toast.LENGTH_LONG).show());
            } catch (Exception ex) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "应用配置失败: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmDelete(ConfigEntry e) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除配置文件")
                .setMessage("确定删除「" + e.gameName + "」的配置文件吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    if (e.file.delete()) {
                        Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else {
                        Toast.makeText(getContext(), "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 导入 ====================

    private void onImportConfigPicked(Uri uri) {
        if (uri == null) return;
        executor.execute(() -> {
            try {
                File localDir = new File(CONFIGS_ROOT, "local");
                if (!localDir.exists()) localDir.mkdirs();

                // 先读取内容解析 gameName 作为文件名
                String name = "imported_" + System.currentTimeMillis();
                try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new Exception("无法打开所选文件");
                    byte[] data = readAll(is);
                    String text = new String(data, "UTF-8");
                    JSONObject json = new JSONObject(text);
                    String gn = json.optString("gameName", "");
                    if (gn.isEmpty()) gn = json.optString("name", "");
                    if (!gn.isEmpty()) name = sanitizeFileName(gn);

                    File dest = new File(localDir, name + ".json");
                    // 重名追加序号
                    int i = 1;
                    while (dest.exists()) dest = new File(localDir, name + "_" + (i++) + ".json");

                    try (OutputStream os = new java.io.FileOutputStream(dest)) {
                        os.write(data);
                    }
                }
                if (getActivity() != null) getActivity().runOnUiThread(() -> {
                    Toast.makeText(getContext(), "导入成功", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
            } catch (Exception ex) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(getContext(), "导入失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static byte[] readAll(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String sanitizeFileName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (s.isEmpty()) s = "imported";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    // ==================== 工具 ====================

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private static String basename(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String formatTime(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static int colorFor(String seed) {
        if (seed == null) seed = "?";
        Random r = new Random(seed.hashCode());
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(0xFF4FC3F7, hsv);
        hsv[0] = (r.nextFloat() * 360f);
        hsv[1] = 0.55f;
        hsv[2] = 0.65f;
        return android.graphics.Color.HSVToColor(hsv);
    }
}
