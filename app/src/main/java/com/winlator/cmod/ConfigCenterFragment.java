package com.winlator.cmod;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.ImageView;
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
import com.winlator.cmod.util.GameRestorePackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private ActivityResultLauncher<String> importPackageLauncher;

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
        // BUG2：同目录同名 .png 图标路径
        String iconPath;
        // BUG3：是否为 .grp.zip 数据包
        boolean isPackage;
        // BUG3：数据包解析出的元信息（仅 isPackage=true 时有效）
        GameRestorePackageManager.PackageInfo packageInfo;

        ConfigEntry(File file) {
            this.file = file;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { setHasOptionsMenu(true); } catch (Exception ignored) {}
        importLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImportConfigPicked);
        importPackageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImportPackagePicked);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        final Context ctx = getContext();
        if (ctx == null) return new TextView(getActivity());
        try {
            int pad = dp(12);
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFF0B0D12);
            root.setPadding(pad, dp(8), pad, pad);

            // 分类 Tab（使用默认构造函数，避免defStyleAttr=0导致主题属性缺失）
            TabLayout tabLayout = new TabLayout(ctx);
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
            FrameLayout content = new FrameLayout(ctx);
            RecyclerView recyclerView = new RecyclerView(ctx);
            recyclerView.setLayoutManager(new LinearLayoutManager(ctx));
            recyclerView.setPadding(0, dp(4), 0, dp(4));
            adapter = new ConfigAdapter();
            recyclerView.setAdapter(adapter);
            content.addView(recyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            emptyView = new TextView(ctx);
            emptyView.setText("暂无配置文件\n点击右上角「导入配置文件」添加");
            emptyView.setGravity(android.view.Gravity.CENTER);
            emptyView.setTextColor(0xFF8A8F98);
            emptyView.setTextSize(15);
            content.addView(emptyView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return root;
        } catch (Exception e) {
            TextView err = new TextView(ctx);
            err.setText("配置中心加载失败，请重试");
            err.setGravity(android.view.Gravity.CENTER);
            err.setTextColor(0xFFE6E9EF);
            err.setTextSize(15);
            return err;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Activity activity = getActivity();
        if (activity == null) return;
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
        // BUG6/BUG7：导入数据包用下载箭头图标，导入容器配置用保存图标，文字清晰可见
        MenuItem importPkgItem = menu.add(0, 100, 0, "导入游戏数据包");
        importPkgItem.setIcon(android.R.drawable.stat_sys_download);
        importPkgItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        MenuItem importItem = menu.add(0, 101, 1, "导入容器配置");
        importItem.setIcon(android.R.drawable.ic_menu_save);
        importItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 100) {
            try {
                importPackageLauncher.launch("*/*");
            } catch (Exception e) {
                Toast.makeText(getContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
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
        // BUG3：同时扫描 local/steam 下的 .grp.zip 数据包
        collectZipPackages(localDir, "local", result);
        collectZipPackages(steamDir, "steam", result);

        // 2) Configs/ 根目录下旧格式 container_config_*.json 与 *.grp.zip
        File[] rootFiles = CONFIGS_ROOT.listFiles();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.US);
                if (name.endsWith(".json")) {
                    collectOne(f, "local", result);
                } else if (name.endsWith(".grp.zip") || name.endsWith(".zip")) {
                    collectZipPackage(f, "local", result);
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

    /** BUG3：扫描目录下的 .grp.zip / .zip 数据包。 */
    private void collectZipPackages(File dir, String defaultSource, List<ConfigEntry> out) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase(Locale.US);
            if (name.endsWith(".grp.zip") || name.endsWith(".zip")) {
                collectZipPackage(f, defaultSource, out);
            }
        }
    }

    /** BUG3：解析单个 .grp.zip 数据包，读取内部 metadata.json。 */
    private void collectZipPackage(File file, String defaultSource, List<ConfigEntry> out) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(file);
            ZipEntry metaEntry = zf.getEntry("metadata.json");
            if (metaEntry == null) return; // 不是合法数据包，跳过
            InputStream is = zf.getInputStream(metaEntry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            JSONObject json = new JSONObject(new String(bos.toByteArray(), "UTF-8"));

            ConfigEntry e = new ConfigEntry(file);
            e.isPackage = true;
            e.gameName = json.optString("gameName", "");
            if (e.gameName.isEmpty()) e.gameName = basename(file.getName());
            e.source = json.optString("source", defaultSource);
            if (e.source.isEmpty()) e.source = defaultSource;
            e.wineVersion = json.optString("wineVersion", "");
            e.graphicsDriver = json.optString("graphicsDriver", "");
            e.dxwrapper = json.optString("dxwrapper", "");
            e.emulator = json.optString("emulator", "");
            e.modifiedAt = file.lastModified();
            e.size = file.length();

            // 复用 GameRestorePackageManager 的 PackageInfo（含完整转译设置字段）
            GameRestorePackageManager.PackageInfo info = new GameRestorePackageManager.PackageInfo();
            info.gameName = e.gameName;
            info.wineVersion = e.wineVersion;
            info.graphicsDriver = e.graphicsDriver;
            info.dxwrapper = e.dxwrapper;
            info.emulator = e.emulator;
            info.containsGameFiles = json.optBoolean("containsGameFiles", false);
            info.containsWineRuntime = json.optBoolean("containsWineRuntime", false);
            info.containsRegistry = json.optBoolean("containsRegistry", false);
            info.box64Version = json.optString("box64Version", "");
            info.fexcoreVersion = json.optString("fexcoreVersion", "");
            info.box64Preset = json.optString("box64Preset", "");
            info.fexcorePreset = json.optString("fexcorePreset", "");
            info.screenSize = json.optString("screenSize", "");
            info.audioDriver = json.optString("audioDriver", "");
            info.envVars = json.optString("envVars", "");
            info.executableName = json.optString("executableName", "");
            e.packageInfo = info;

            // BUG2：同目录同名 .png 图标（WolfsDungeon.grp.zip -> WolfsDungeon.png）
            String base = basename(file.getName());
            File icon = new File(file.getParentFile(), base + ".png");
            if (icon.exists()) e.iconPath = icon.getAbsolutePath();

            out.add(e);
        } catch (Exception ex) {
            // 解析失败跳过
        } finally {
            try { if (zf != null) zf.close(); } catch (Exception ignored) {}
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

            // BUG2：检查同目录是否有同名 .png 图标（WolfsDungeon.json -> WolfsDungeon.png）
            String base = basename(file.getName());
            File icon = new File(file.getParentFile(), base + ".png");
            if (icon.exists()) e.iconPath = icon.getAbsolutePath();

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
        try {
            if (adapter != null) adapter.notifyDataSetChanged();
        } catch (Exception e) {
            // BUG1修复：notifyDataSetChanged可能因视图状态异常崩溃
        }
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
            int m = dp(8);
            int pad = dp(14);

            // 构建行内容（水平布局：图标 + 文字列）
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(pad, pad, pad, pad);

            // 图标占位：FrameLayout 包裹 ImageView（真实图标）+ TextView（首字母兜底）
            FrameLayout iconWrap = new FrameLayout(ctx);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(48), dp(48));
            ilp.setMargins(0, 0, dp(12), 0);
            iconWrap.setLayoutParams(ilp);
            ImageView iconImage = new ImageView(ctx);
            iconImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconImage.setClipToOutline(true);
            iconImage.setVisibility(View.GONE);
            iconWrap.addView(iconImage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

            // BUG1修复：MaterialCardView可能因主题缺失崩溃，try-catch包裹，失败退化为LinearLayout+圆角背景
            View itemView;
            try {
                com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(ctx);
                card.setUseCompatPadding(true);
                card.setRadius(dp(12));
                card.setCardElevation(dp(2));
                card.addView(row);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, m / 2, 0, m / 2);
                card.setLayoutParams(lp);
                itemView = card;
            } catch (Exception e) {
                // 退化方案：LinearLayout + 圆角背景
                LinearLayout fallback = new LinearLayout(ctx);
                fallback.setOrientation(LinearLayout.VERTICAL);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(12));
                bg.setColor(0xFF1A1D26);
                fallback.setBackground(bg);
                fallback.addView(row);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, m / 2, 0, m / 2);
                fallback.setLayoutParams(lp);
                itemView = fallback;
            }
            return new VH(itemView, iconImage, iconText, title, summary, meta);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            try {
                ConfigEntry e = filteredEntries.get(position);
                h.title.setText(e.gameName != null ? e.gameName : "未命名配置");
                h.summary.setText(buildSummary(e));
                // BUG3：数据包在 meta 前加「数据包」标记
                String tag = e.isPackage ? "数据包 · " : "";
                h.meta.setText(tag + formatTime(e.modifiedAt) + "  ·  " + formatSize(e.size) + "  ·  "
                        + ("steam".equals(e.source) ? "Steam" : "本地"));

                // BUG2：有真实图标文件时用 Bitmap 显示，否则首字母圆形图标
                boolean hasIcon = false;
                if (e.iconPath != null && !e.iconPath.isEmpty()) {
                    File iconFile = new File(e.iconPath);
                    if (iconFile.exists()) {
                        try {
                            Bitmap bmp = BitmapFactory.decodeFile(e.iconPath);
                            if (bmp != null) {
                                h.iconImage.setImageBitmap(bmp);
                                h.iconImage.setVisibility(View.VISIBLE);
                                h.iconText.setVisibility(View.GONE);
                                GradientDrawable oval = new GradientDrawable();
                                oval.setShape(GradientDrawable.OVAL);
                                h.iconImage.setBackground(oval);
                                hasIcon = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (!hasIcon) {
                    h.iconImage.setVisibility(View.GONE);
                    h.iconText.setVisibility(View.VISIBLE);
                    String letter = (e.gameName == null || e.gameName.isEmpty()) ? "?"
                            : e.gameName.substring(0, 1).toUpperCase(Locale.getDefault());
                    h.iconText.setText(letter);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    bg.setColor(colorFor(e.gameName));
                    h.iconText.setBackground(bg);
                }

                h.itemView.setOnClickListener(v -> showDetail(e));
                h.itemView.setOnLongClickListener(v -> {
                    confirmDelete(e);
                    return true;
                });
            } catch (Exception ex) {
                // BUG1修复：绑定异常时设置默认文本，绝不崩溃
                try {
                    h.title.setText("配置文件");
                    h.summary.setText("");
                    h.meta.setText("");
                    h.iconImage.setVisibility(View.GONE);
                    h.iconText.setVisibility(View.VISIBLE);
                    h.iconText.setText("?");
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    bg.setColor(0xFF4FC3F7);
                    h.iconText.setBackground(bg);
                } catch (Exception ignored) {}
            }
        }

        @Override
        public int getItemCount() {
            return filteredEntries.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView iconImage;
            final TextView iconText;
            final TextView title;
            final TextView summary;
            final TextView meta;
            VH(@androidx.annotation.NonNull View itemView, ImageView iconImage, TextView iconText,
               TextView title, TextView summary, TextView meta) {
                super(itemView);
                this.iconImage = iconImage;
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
        final Context ctx = getContext();
        if (ctx == null) return;

        // BUG3：数据包卡片 -> 显示数据包信息 + 导入按钮
        if (e.isPackage) {
            showPackageDetail(e, ctx);
            return;
        }

        try {
            String text = FileUtils.readString(e.file);
            if (text == null) {
                Toast.makeText(ctx, "无法读取配置文件", Toast.LENGTH_SHORT).show();
                return;
            }
            JSONObject json = new JSONObject(text);

            // BUG1：用临时 Container 解析，显示所有非空配置项（与列表摘要一致）
            String diffSummary = buildDiffSummary(json);

            // 功能优化2：增强详情页展示
            StringBuilder body = new StringBuilder();
            body.append("游戏名称：").append(e.gameName != null && !e.gameName.isEmpty() ? e.gameName : "未命名").append('\n');
            body.append("来源：").append("steam".equals(e.source) ? "Steam 游戏" : "本地游戏").append('\n');
            body.append("修改时间：").append(formatTime(e.modifiedAt)).append('\n');
            body.append("文件大小：").append(formatSize(e.size)).append('\n');

            // 检查是否包含游戏图标
            String iconFile = json.optString("iconFile", "");
            if (!iconFile.isEmpty()) {
                body.append("包含游戏图标：").append(iconFile).append('\n');
            }

            body.append("──────────────\n");
            body.append("配置项：\n");
            body.append(diffSummary.isEmpty() ? "  （无）" : diffSummary);

            body.append("\n──────────────\n");
            body.append("说明：此配置可一键应用到选中游戏的容器，\n");
            body.append("覆盖Wine版本、驱动、组件等设置。");

            ScrollView sv = new ScrollView(ctx);
            TextView tv = new TextView(ctx);
            tv.setText(body.toString());
            tv.setTextColor(0xFFE6E9EF);
            tv.setTextSize(13);
            tv.setLineSpacing(dp(2), 1.2f);
            tv.setPadding(dp(20), dp(16), dp(20), dp(16));
            sv.addView(tv);

            new AlertDialog.Builder(ctx)
                    .setTitle(e.gameName)
                    .setView(sv)
                    .setPositiveButton("应用到游戏", (d, w) -> pickGameToApply(e))
                    .setNeutralButton("删除", (d, w) -> confirmDelete(e))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception ex) {
            Toast.makeText(ctx, "解析配置失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** BUG3：数据包详情弹窗，显示游戏名/包含内容/Wine版本，并提供导入按钮。 */
    private void showPackageDetail(ConfigEntry e, final Context ctx) {
        try {
            GameRestorePackageManager.PackageInfo info = e.packageInfo;
            StringBuilder body = new StringBuilder();
            body.append("━━━ 游戏数据包 ━━━\n");
            body.append("游戏名称: ").append(e.gameName).append("\n");
            body.append("文件大小: ").append(formatSize(e.size)).append("\n");
            body.append("修改时间: ").append(formatTime(e.modifiedAt)).append("\n\n");
            body.append("包含内容:\n");
            body.append("  · 游戏文件: ").append(info != null && info.containsGameFiles ? "是" : "否").append("\n");
            body.append("  · Wine运行环境: ").append(info != null && info.containsWineRuntime ? "是" : "否").append("\n");
            body.append("  · 注册表: ").append(info != null && info.containsRegistry ? "是" : "否").append("\n\n");
            if (info != null) {
                body.append("━━━ 转译设置 ━━━\n");
                String diff = GameRestorePackageManager.getConfigDiffFromMetadata(info, ctx);
                body.append(diff.isEmpty() ? "（默认配置）" : diff);
            }

            ScrollView sv = new ScrollView(ctx);
            TextView tv = new TextView(ctx);
            tv.setText(body.toString());
            tv.setTextColor(0xFFE6E9EF);
            tv.setTextSize(13);
            tv.setLineSpacing(dp(2), 1.2f);
            tv.setPadding(dp(20), dp(16), dp(20), dp(16));
            sv.addView(tv);

            final File pkgFile = e.file;
            new AlertDialog.Builder(ctx)
                    .setTitle(e.gameName + "（数据包）")
                    .setView(sv)
                    .setPositiveButton("导入此数据包", (d, w) -> importLocalPackageFile(pkgFile))
                    .setNeutralButton("删除", (d, w) -> confirmDelete(e))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception ex) {
            Toast.makeText(ctx, "打开数据包失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** BUG3：从配置中心列表直接导入本地数据包文件。 */
    private void importLocalPackageFile(final File pkgFile) {
        final Context ctx = getContext();
        if (ctx == null || pkgFile == null || !pkgFile.exists()) return;
        // 复制到临时文件后走与选择器一致的流程
        executor.execute(() -> {
            try {
                final File tempFile = new File(ctx.getCacheDir(),
                        "import_" + System.currentTimeMillis() + ".grp.zip");
                try (java.io.FileInputStream is = new java.io.FileInputStream(pkgFile);
                     java.io.FileOutputStream os = new java.io.FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                }
                final GameRestorePackageManager.PackageInfo pkgInfo =
                        GameRestorePackageManager.parsePackageInfo(tempFile);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (pkgInfo == null) {
                        Toast.makeText(ctx, "无法解析数据包", Toast.LENGTH_LONG).show();
                        tempFile.delete();
                        return;
                    }
                    confirmAndImportPackage(ctx, tempFile, pkgInfo);
                });
            } catch (Exception ex) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(ctx, "导入失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 构造配置摘要：显示所有非空配置项（与列表项一致），并补充 FEXCore/环境变量/CPU亲和性/DXVK/Vulkan。 */
    private String buildDiffSummary(JSONObject json) {
        try {
            StringBuilder sb = new StringBuilder();
            Container tmp = new Container(0);
            tmp.loadData(json);

            // 与列表项一致：显示所有非空项，而非只显示非默认项
            appendSummaryPart(sb, "Wine版本", tmp.getWineVersion());
            appendSummaryPart(sb, "图形驱动", tmp.getGraphicsDriver());
            appendSummaryPart(sb, "DXWrapper", tmp.getDXWrapper());
            appendSummaryPart(sb, "音频驱动", tmp.getAudioDriver());
            appendSummaryPart(sb, "模拟器", tmp.getEmulator());
            appendSummaryPart(sb, "屏幕分辨率", tmp.getScreenSize());
            if (tmp.getBox64Version() != null && !tmp.getBox64Version().isEmpty()) {
                String preset = tmp.getBox64Preset();
                sb.append("  · Box64: ").append(tmp.getBox64Version());
                if (preset != null && !preset.isEmpty()) sb.append("（预设: ").append(preset).append('）');
                sb.append('\n');
            }
            // BUG1：补充 FEXCore 版本+预设
            if (tmp.getFEXCoreVersion() != null && !tmp.getFEXCoreVersion().isEmpty()) {
                String preset = tmp.getFEXCorePreset();
                sb.append("  · FEXCore: ").append(tmp.getFEXCoreVersion());
                if (preset != null && !preset.isEmpty()) sb.append("（预设: ").append(preset).append('）');
                sb.append('\n');
            }
            // BUG1：环境变量（非默认时显示"已自定义"）
            if (tmp.getEnvVars() != null && !tmp.getEnvVars().isEmpty()
                    && !tmp.getEnvVars().equals(Container.DEFAULT_ENV_VARS)) {
                sb.append("  · 环境变量: 已自定义（").append(tmp.getEnvVars().length()).append("字符）\n");
            }
            // BUG1：CPU亲和性
            if (tmp.getCPUList() != null && !tmp.getCPUList().isEmpty()) {
                sb.append("  · CPU亲和性: ").append(tmp.getCPUList()).append('\n');
            }
            // BUG1：DXVK 版本 / Vulkan 版本
            try {
                if (tmp.getDXWrapperConfig() != null && !tmp.getDXWrapperConfig().isEmpty()) {
                    com.winlator.cmod.core.KeyValueSet dxCfg =
                            new com.winlator.cmod.core.KeyValueSet(tmp.getDXWrapperConfig());
                    String dxvkVer = dxCfg.get("version");
                    if (dxvkVer != null && !dxvkVer.isEmpty()) {
                        sb.append("  · DXVK版本: ").append(dxvkVer).append('\n');
                    }
                }
                if (tmp.getGraphicsDriverConfig() != null && !tmp.getGraphicsDriverConfig().isEmpty()) {
                    com.winlator.cmod.core.KeyValueSet gpuCfg =
                            new com.winlator.cmod.core.KeyValueSet(tmp.getGraphicsDriverConfig());
                    String vkVer = gpuCfg.get("vulkanVersion");
                    if (vkVer != null && !vkVer.isEmpty()) {
                        sb.append("  · Vulkan版本: ").append(vkVer).append('\n');
                    }
                }
            } catch (Exception ignored) {}

            return sb.toString().trim();
        } catch (Exception e) {
            // BUG1修复：Container.loadData可能崩溃，返回错误提示而不闪退
            return "（配置解析失败）";
        }
    }

    /** BUG1：追加非空配置项（与列表摘要逻辑一致）。 */
    private void appendSummaryPart(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append("  · ").append(label).append(": ").append(value).append('\n');
    }

    private void pickGameToApply(ConfigEntry e) {
        final Context ctx = getContext();
        if (ctx == null) return;
        if (containerManager == null) {
            try { containerManager = new ContainerManager(ctx); }
            catch (Exception ex) {
                Toast.makeText(ctx, "容器管理器初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ArrayList<Shortcut> shortcuts = containerManager.loadShortcuts();
        if (shortcuts == null || shortcuts.isEmpty()) {
            Toast.makeText(ctx, "没有可用的游戏，请先在游戏库添加游戏", Toast.LENGTH_LONG).show();
            return;
        }
        final CharSequence[] items = new CharSequence[shortcuts.size()];
        for (int i = 0; i < shortcuts.size(); i++) items[i] = shortcuts.get(i).name;
        new AlertDialog.Builder(ctx)
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
        final Context ctx = getContext();
        if (ctx == null) return;
        new AlertDialog.Builder(ctx)
                .setTitle("删除配置文件")
                .setMessage("确定删除「" + e.gameName + "」的配置文件吗？此操作不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    if (e.file.delete()) {
                        Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show();
                        refreshList();
                    } else {
                        Toast.makeText(ctx, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 导入 ====================

    private void onImportConfigPicked(Uri uri) {
        if (uri == null) return;
        final Context ctx = getContext();
        if (ctx == null) return;
        executor.execute(() -> {
            try {
                File localDir = new File(CONFIGS_ROOT, "local");
                if (!localDir.exists()) localDir.mkdirs();

                // 先读取内容解析 gameName 作为文件名
                String name = "imported_" + System.currentTimeMillis();
                try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
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
                    Toast.makeText(ctx, "配置导入成功，已保存到配置中心", Toast.LENGTH_SHORT).show();
                    refreshList();
                });
            } catch (Exception ex) {
                if (getActivity() != null) getActivity().runOnUiThread(() ->
                        Toast.makeText(ctx, "导入失败: " + ex.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 导入游戏数据包（.grp.zip） */
    private void onImportPackagePicked(Uri uri) {
        if (uri == null) return;
        final Context ctx = getContext();
        if (ctx == null) return;

        try {
            final File tempFile = new File(ctx.getCacheDir(), "import_" + System.currentTimeMillis() + ".grp.zip");
            try (InputStream is = ctx.getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream os = new java.io.FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }

            // 解析数据包信息
            final GameRestorePackageManager.PackageInfo pkgInfo = GameRestorePackageManager.parsePackageInfo(tempFile);
            if (pkgInfo == null) {
                Toast.makeText(ctx, "无法解析数据包，请确认文件格式正确", Toast.LENGTH_LONG).show();
                tempFile.delete();
                return;
            }
            confirmAndImportPackage(ctx, tempFile, pkgInfo);

        } catch (Exception e) {
            Toast.makeText(ctx, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * BUG4：导入前的信息确认对话框。
     * 显示游戏基本信息 + 转译设置摘要（Wine/驱动/DXWrapper/Box64/FEXCore/分辨率/音频），
     * 只显示非空/非默认项。
     */
    private void confirmAndImportPackage(final Context ctx, final File tempFile,
                                         final GameRestorePackageManager.PackageInfo pkgInfo) {
        // 检查Wine依赖（即使数据包自带Wine运行环境，Wine二进制仍需已安装）
        final boolean wineInstalled = pkgInfo.containsWineRuntime ||
                GameRestorePackageManager.isWineVersionInstalled(ctx, pkgInfo.wineVersion);

        if (!wineInstalled) {
            new AlertDialog.Builder(ctx)
                    .setTitle("无法导入")
                    .setMessage("数据包需要Wine版本：" + pkgInfo.wineVersion + "\n\n" +
                            "当前未安装该版本。\n\n" +
                            "请先在「设置 → 组件管理」中安装 " + pkgInfo.wineVersion + " 后再导入此数据包。")
                    .setPositiveButton("确定", (dialog, which) -> tempFile.delete())
                    .setCancelable(false)
                    .show();
            return;
        }

        // 信息确认对话框
        StringBuilder infoMsg = new StringBuilder();
        infoMsg.append("━━━ 游戏信息 ━━━\n");
        infoMsg.append("游戏名称: ").append(pkgInfo.gameName).append("\n");
        if (pkgInfo.executableName != null && !pkgInfo.executableName.isEmpty()) {
            infoMsg.append("exe文件: ").append(pkgInfo.executableName).append("\n");
        }
        infoMsg.append("包含游戏文件: ").append(pkgInfo.containsGameFiles ? "是" : "否（仅配置）").append("\n");
        if (pkgInfo.containsWineRuntime) {
            infoMsg.append("包含Wine运行环境: 是 ✓\n");
        }
        if (pkgInfo.hasShortcutConfig) {
            infoMsg.append("包含快捷方式独立配置: 是 ✓\n");
        }

        // BUG4：转译设置部分
        infoMsg.append("\n━━━ 转译设置 ━━━\n");
        String diff = GameRestorePackageManager.getConfigDiffFromMetadata(pkgInfo, ctx);
        infoMsg.append(diff.isEmpty() ? "（全部使用默认配置）" : diff);

        infoMsg.append("\n是否确认导入？");

        new AlertDialog.Builder(ctx)
                .setTitle("确认导入游戏数据包")
                .setMessage(infoMsg.toString())
                .setPositiveButton("确认导入", (dialog, which) -> startPackageImport(ctx, tempFile))
                .setNegativeButton("取消", (dialog, which) -> tempFile.delete())
                .show();
    }

    private ProgressDialog importProgressDialog;

    private void startPackageImport(final Context ctx, final File tempFile) {
        importProgressDialog = new ProgressDialog(ctx);
        importProgressDialog.setTitle("正在导入游戏数据包");
        importProgressDialog.setMessage("准备中...");
        importProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        importProgressDialog.setMax(100);
        importProgressDialog.setCancelable(false);
        importProgressDialog.show();

        GameRestorePackageManager.importPackageAsync(ctx, tempFile,
                new GameRestorePackageManager.ImportCallback() {
                    @Override
                    public void onProgress(int percent, String message) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (importProgressDialog != null && importProgressDialog.isShowing()) {
                                    importProgressDialog.setProgress(percent);
                                    importProgressDialog.setMessage(message);
                                }
                            });
                        }
                    }

                    @Override
                    public void onComplete(int containerId, String shortcutName) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (importProgressDialog != null && importProgressDialog.isShowing()) {
                                    importProgressDialog.dismiss();
                                }
                                importProgressDialog = null;
                                Toast.makeText(ctx, "导入成功！游戏：" + shortcutName, Toast.LENGTH_LONG).show();
                                tempFile.delete();
                            });
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (importProgressDialog != null && importProgressDialog.isShowing()) {
                                    importProgressDialog.dismiss();
                                }
                                importProgressDialog = null;
                                Toast.makeText(ctx, "导入失败: " + message, Toast.LENGTH_LONG).show();
                                tempFile.delete();
                            });
                        }
                    }

                    @Override
                    public void onWarning(String warning) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() ->
                                    Toast.makeText(ctx, "警告: " + warning, Toast.LENGTH_SHORT).show());
                        }
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
        Context ctx = getContext();
        if (ctx == null) return v;
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
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
        try {
            if (seed == null) seed = "?";
            Random r = new Random(seed.hashCode());
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(0xFF4FC3F7, hsv);
            hsv[0] = (r.nextFloat() * 360f);
            hsv[1] = 0.55f;
            hsv[2] = 0.65f;
            return android.graphics.Color.HSVToColor(hsv);
        } catch (Exception e) {
            // BUG1修复：颜色计算异常时返回默认蓝色
            return 0xFF4FC3F7;
        }
    }
}
