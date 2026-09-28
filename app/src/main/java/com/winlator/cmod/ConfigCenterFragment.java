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
import android.widget.Button;
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
            // BUG1：渲染器与Vulkan Wrapper
            info.rendererNative = json.optBoolean("rendererNative", false);
            info.graphicsWrapper = json.optString("graphicsWrapper", "wrapper");
            info.graphicsDriverConfig = json.optString("graphicsDriverConfig", "");
            info.dxwrapperConfig = json.optString("dxwrapperConfig", "");
            info.containsZDrive = json.optBoolean("containsZDrive", false);
            // BUG7：检测shortcutConfig是否存在
            info.hasShortcutConfig = json.has("shortcutConfig");
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

        // 数据包卡片 -> 走数据包详情（含包含内容/设置/依赖检查）
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
            final JSONObject json = new JSONObject(text);

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(16), dp(20), dp(12));

            // ── 顶部：游戏图标 + 名称 + 来源标签 ──
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            FrameLayout iconWrap = buildDetailIcon(ctx, e);
            header.addView(iconWrap, new LinearLayout.LayoutParams(dp(56), dp(56)));

            LinearLayout nameCol = new LinearLayout(ctx);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams nclp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nclp.leftMargin = dp(14);
            nameCol.setLayoutParams(nclp);

            TextView nameView = new TextView(ctx);
            nameView.setText(e.gameName != null && !e.gameName.isEmpty() ? e.gameName : "未命名配置");
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setTextSize(19);
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameCol.addView(nameView);

            TextView sourceTag = new TextView(ctx);
            sourceTag.setText(sourceLabel(e.source, e.isPackage));
            sourceTag.setTextSize(11);
            sourceTag.setTextColor(0xFF4FC3F7);
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setShape(GradientDrawable.RECTANGLE);
            tagBg.setCornerRadius(dp(4));
            tagBg.setColor(0x224FC3F7);
            sourceTag.setBackground(tagBg);
            sourceTag.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams stagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stagLp.topMargin = dp(6);
            sourceTag.setLayoutParams(stagLp);
            nameCol.addView(sourceTag);

            header.addView(nameCol);
            root.addView(header);

            // ── 基本信息行：修改时间 · 文件大小 · 包含图标 ──
            String iconMark = json.optString("iconFile", "").isEmpty() ? "" : "  ·  包含图标";
            TextView metaLine = new TextView(ctx);
            metaLine.setText(formatTime(e.modifiedAt) + "  ·  " + formatSize(e.size) + iconMark);
            metaLine.setTextSize(12);
            metaLine.setTextColor(0xFF8A8F98);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(10);
            metaLine.setLayoutParams(mlp);
            root.addView(metaLine);

            // ── 配置摘要（只显示非默认/非空关键项）──
            root.addView(sectionTitle(ctx, "配置摘要"), topLp(16));
            String summary = buildConfigSummary(json);
            root.addView(bodyText(ctx, summary.isEmpty() ? "（全部为默认设置）" : summary));

            // ── 测试环境（设备参考信息，非配置差异；无device信息则不显示该区域）──
            String testEnv = buildTestEnvironmentInfo(json);
            if (!testEnv.isEmpty()) {
                root.addView(sectionTitle(ctx, "测试环境"), topLp(16));
                root.addView(bodyText(ctx, testEnv));
            }

            // ── 使用说明（notes/description）──
            String notes = json.optString("notes", "");
            if (notes.isEmpty()) notes = json.optString("description", "");
            if (!notes.isEmpty()) {
                root.addView(sectionTitle(ctx, "使用说明"), topLp(16));
                root.addView(bodyText(ctx, notes));
            }

            ScrollView sv = new ScrollView(ctx);
            sv.addView(root);

            new AlertDialog.Builder(ctx)
                    .setView(sv)
                    .setPositiveButton("应用到游戏", (d, w) -> pickGameToApply(e))
                    .setNeutralButton("删除", (d, w) -> confirmDelete(e))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception ex) {
            Toast.makeText(ctx, "解析配置失败: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 详情页/数据包详情页的游戏图标：有真实图标用 Bitmap，否则首字母圆形底色。 */
    private FrameLayout buildDetailIcon(Context ctx, ConfigEntry e) {
        FrameLayout wrap = new FrameLayout(ctx);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        TextView tv = new TextView(ctx);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        wrap.addView(iv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrap.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        boolean hasIcon = false;
        if (e.iconPath != null && !e.iconPath.isEmpty()) {
            File iconFile = new File(e.iconPath);
            if (iconFile.exists()) {
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(e.iconPath);
                    if (bmp != null) {
                        iv.setImageBitmap(bmp);
                        iv.setVisibility(View.VISIBLE);
                        tv.setVisibility(View.GONE);
                        GradientDrawable oval = new GradientDrawable();
                        oval.setShape(GradientDrawable.OVAL);
                        iv.setBackground(oval);
                        hasIcon = true;
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!hasIcon) {
            iv.setVisibility(View.GONE);
            tv.setVisibility(View.VISIBLE);
            String letter = (e.gameName == null || e.gameName.isEmpty()) ? "?"
                    : e.gameName.substring(0, 1).toUpperCase(Locale.getDefault());
            tv.setText(letter);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(colorFor(e.gameName));
            tv.setBackground(bg);
        }
        return wrap;
    }

    private static String sourceLabel(String source, boolean isPackage) {
        if (isPackage) return "数据包";
        return "steam".equals(source) ? "Steam游戏" : "本地游戏";
    }

    /** 区块标题（蓝色加粗）。 */
    private TextView sectionTitle(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF4FC3F7);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** 区块正文。 */
    private TextView bodyText(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFE6E9EF);
        tv.setTextSize(13);
        tv.setLineSpacing(dp(2), 1.25f);
        return tv;
    }

    /** LinearLayout.LayoutParams，仅设置顶部 margin。 */
    private LinearLayout.LayoutParams topLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /**
     * 配置摘要：对比 Container.DEFAULT_* 常量，只提炼非默认/非空的关键项。
     * 用户看到的是「这个配置能做什么」，而非全部技术字段。
     */
    private String buildConfigSummary(JSONObject json) {
        try {
            Container tmp = new Container(0);
            tmp.loadData(json);
            StringBuilder sb = new StringBuilder();
            String defaultWine = WineInfo.MAIN_WINE_VERSION.identifier();

            if (isDiff(tmp.getWineVersion(), defaultWine)) {
                sb.append("• Wine版本: ").append(tmp.getWineVersion()).append('\n');
            }
            // BUG1：渲染器（rendererNative=true显示Native，默认false=Vulkan不显示）
            if (tmp.getRendererNative()) {
                sb.append("• 渲染器: Native\n");
            }
            // OpenGL驱动（graphicsDriver，附Vulkan API版本）
            boolean gpuDriverDiff = isDiff(tmp.getGraphicsDriver(), Container.DEFAULT_GRAPHICS_DRIVER);
            String vkApiVer = kvs(tmp.getGraphicsDriverConfig(), "vulkanVersion");
            String vkDriverVer = kvs(tmp.getGraphicsDriverConfig(), "version");
            if (gpuDriverDiff || !vkDriverVer.isEmpty()) {
                sb.append("• OpenGL驱动: ").append(notEmpty(tmp.getGraphicsDriver()) ? tmp.getGraphicsDriver() : Container.DEFAULT_GRAPHICS_DRIVER);
                if (!vkApiVer.isEmpty()) sb.append(" (Vulkan ").append(vkApiVer).append(')');
                sb.append('\n');
            }
            // BUG1：Vulkan驱动版本（如turnip26.2.0）
            if (!vkDriverVer.isEmpty()) {
                sb.append("• Vulkan驱动: ").append(vkDriverVer).append('\n');
            }
            // BUG1：Vulkan Wrapper（graphicsWrapper，默认wrapper不显示）
            if (isDiff(tmp.getGraphicsWrapper(), Container.DEFAULT_GRAPHICS_WRAPPER)) {
                sb.append("• Vulkan Wrapper: ").append(tmp.getGraphicsWrapper()).append('\n');
            }
            // BUG2：DXWrapper（附DXVK和VKD3D版本）
            String dxvkVer = kvs(tmp.getDXWrapperConfig(), "version");
            String vkd3dVer = kvs(tmp.getDXWrapperConfig(), "vkd3dVersion");
            boolean dxwrapperDiff = isDiff(tmp.getDXWrapper(), Container.DEFAULT_DXWRAPPER);
            if (dxwrapperDiff || !dxvkVer.isEmpty() || !vkd3dVer.isEmpty()) {
                sb.append("• DXWrapper: ").append(notEmpty(tmp.getDXWrapper()) ? tmp.getDXWrapper() : Container.DEFAULT_DXWRAPPER);
                if (!dxvkVer.isEmpty() || !vkd3dVer.isEmpty()) {
                    sb.append("（");
                    if (!dxvkVer.isEmpty()) sb.append("DXVK ").append(dxvkVer);
                    if (!dxvkVer.isEmpty() && !vkd3dVer.isEmpty()) sb.append(" / ");
                    if (!vkd3dVer.isEmpty()) sb.append("VKD3D ").append(vkd3dVer);
                    sb.append("）");
                }
                sb.append('\n');
            }
            // BUG3：转译器：Box64 / FEXCore（版本+预设），确保不同时显示
            String emu = tmp.getEmulator();
            boolean isBox64 = emu != null && emu.toLowerCase(Locale.US).contains("box64");
            boolean isFex = emu != null && emu.toLowerCase(Locale.US).contains("fex");
            if (isBox64) {
                sb.append("• 转译器: Box64");
                if (notEmpty(tmp.getBox64Version())) sb.append(' ').append(tmp.getBox64Version());
                if (notEmpty(tmp.getBox64Preset())) sb.append("（预设: ").append(tmp.getBox64Preset()).append('）');
                sb.append('\n');
            } else if (isFex) {
                sb.append("• 转译器: FEXCore");
                if (notEmpty(tmp.getFEXCoreVersion())) sb.append(' ').append(tmp.getFEXCoreVersion());
                if (notEmpty(tmp.getFEXCorePreset())) sb.append("（预设: ").append(tmp.getFEXCorePreset()).append('）');
                sb.append('\n');
            } else if (notEmpty(tmp.getBox64Version())) {
                sb.append("• 转译器: Box64 ").append(tmp.getBox64Version());
                if (notEmpty(tmp.getBox64Preset())) sb.append("（预设: ").append(tmp.getBox64Preset()).append('）');
                sb.append('\n');
            } else if (notEmpty(tmp.getFEXCoreVersion())) {
                sb.append("• 转译器: FEXCore ").append(tmp.getFEXCoreVersion());
                if (notEmpty(tmp.getFEXCorePreset())) sb.append("（预设: ").append(tmp.getFEXCorePreset()).append('）');
                sb.append('\n');
            }
            if (isDiff(tmp.getScreenSize(), Container.DEFAULT_SCREEN_SIZE)) {
                sb.append("• 屏幕分辨率: ").append(tmp.getScreenSize()).append('\n');
            }
            if (isDiff(tmp.getAudioDriver(), Container.DEFAULT_AUDIO_DRIVER)) {
                sb.append("• 音频驱动: ").append(tmp.getAudioDriver()).append('\n');
            }
            if (notEmpty(tmp.getEnvVars()) && !tmp.getEnvVars().equals(Container.DEFAULT_ENV_VARS)) {
                sb.append("• 环境变量: 已自定义\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isDiff(String value, String def) {
        return value != null && !value.isEmpty() && !value.equals(def);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static String kvs(String config, String key) {
        if (config == null || config.isEmpty()) return "";
        try {
            String v = new com.winlator.cmod.core.KeyValueSet(config).get(key);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 测试环境：设备参考信息（GPU/型号/SOC），非配置差异。
     * 读取配置 JSON 的 meta.device 或顶层 device 对象；无则返回空串（详情页不显示该区域）。
     * 格式："GPU型号 / 设备型号（SOC: xxx）"，由调用方加"测试环境"标题。
     */
    private String buildTestEnvironmentInfo(JSONObject json) {
        try {
            if (json == null) return "";
            JSONObject device = json.optJSONObject("device");
            JSONObject meta = json.optJSONObject("meta");
            if (device == null && meta != null) device = meta.optJSONObject("device");
            if (device == null) return "";

            String gpu = device.optString("gpu", "");
            String model = device.optString("model", "");
            if (model.isEmpty()) model = device.optString("deviceModel", "");
            String soc = device.optString("soc", "");

            StringBuilder sb = new StringBuilder();
            if (!gpu.isEmpty()) sb.append(gpu);
            if (!model.isEmpty()) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(model);
            }
            if (sb.length() == 0) return "";
            if (!soc.isEmpty()) sb.append("（SOC: ").append(soc).append('）');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 数据包详情：游戏信息 + 包含内容 + 将应用的设置 + 依赖检查（pre-apply diff）。 */
    private void showPackageDetail(ConfigEntry e, final Context ctx) {
        try {
            final GameRestorePackageManager.PackageInfo info = e.packageInfo;

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(16), dp(20), dp(12));

            // ── 顶部：图标 + 名称 + 数据包标签 ──
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            header.addView(buildDetailIcon(ctx, e), new LinearLayout.LayoutParams(dp(56), dp(56)));
            LinearLayout nameCol = new LinearLayout(ctx);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams nclp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nclp.leftMargin = dp(14);
            nameCol.setLayoutParams(nclp);
            TextView nameView = new TextView(ctx);
            nameView.setText(e.gameName != null && !e.gameName.isEmpty() ? e.gameName : "未命名数据包");
            nameView.setTextColor(0xFFFFFFFF);
            nameView.setTextSize(19);
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameCol.addView(nameView);
            TextView sourceTag = new TextView(ctx);
            sourceTag.setText("数据包");
            sourceTag.setTextSize(11);
            sourceTag.setTextColor(0xFFFFB74D);
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setShape(GradientDrawable.RECTANGLE);
            tagBg.setCornerRadius(dp(4));
            tagBg.setColor(0x22FFB74D);
            sourceTag.setBackground(tagBg);
            sourceTag.setPadding(dp(8), dp(2), dp(8), dp(2));
            LinearLayout.LayoutParams stagLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stagLp.topMargin = dp(6);
            sourceTag.setLayoutParams(stagLp);
            nameCol.addView(sourceTag);
            header.addView(nameCol);
            root.addView(header);

            // ── 基本信息行 ──
            TextView metaLine = new TextView(ctx);
            metaLine.setText(formatTime(e.modifiedAt) + "  ·  " + formatSize(e.size));
            metaLine.setTextSize(12);
            metaLine.setTextColor(0xFF8A8F98);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(10);
            metaLine.setLayoutParams(mlp);
            root.addView(metaLine);

            // ── 包含内容 ──
            root.addView(sectionTitle(ctx, "包含内容"), topLp(16));
            StringBuilder ci = new StringBuilder();
            ci.append("游戏文件 ").append(info != null && info.containsGameFiles ? "✓" : "✗").append('\n');
            ci.append("Wine运行环境 ").append(info != null && info.containsWineRuntime ? "✓" : "✗").append('\n');
            ci.append("快捷方式独立配置 ").append(info != null && info.hasShortcutConfig ? "✓" : "✗");
            root.addView(bodyText(ctx, ci.toString()));

            // ── 将应用的设置 ──
            root.addView(sectionTitle(ctx, "将应用的设置"), topLp(14));
            String diff = info == null ? "" : GameRestorePackageManager.getConfigDiffFromMetadata(info, ctx);
            root.addView(bodyText(ctx, diff.isEmpty() ? "（全部为默认设置）" : diff));

            // ── 依赖检查 ──
            root.addView(sectionTitle(ctx, "依赖检查"), topLp(14));
            boolean wineBundled = info != null && info.containsWineRuntime;
            boolean wineInstalled = wineBundled || (info != null
                    && GameRestorePackageManager.isWineVersionInstalled(ctx, info.wineVersion));
            TextView depView = new TextView(ctx);
            StringBuilder dep = new StringBuilder();
            dep.append("Wine版本: ");
            if (wineBundled) dep.append("数据包内置 ✓");
            else if (wineInstalled) dep.append("已安装 ✓");
            else dep.append("未安装 ✗");
            depView.setText(dep.toString());
            depView.setTextSize(13);
            depView.setLineSpacing(dp(2), 1.25f);
            depView.setTextColor(wineInstalled ? 0xFFE6E9EF : 0xFFFF7043);
            root.addView(depView);
            if (!wineInstalled && info != null && info.wineVersion != null && !info.wineVersion.isEmpty()) {
                TextView warn = new TextView(ctx);
                warn.setText("⚠ 导入前请先在「设置 → 组件管理」安装 " + info.wineVersion);
                warn.setTextColor(0xFFFF7043);
                warn.setTextSize(12);
                LinearLayout.LayoutParams wlp = topLp(6);
                warn.setLayoutParams(wlp);
                root.addView(warn);
            }
            // BUG4：Wine运行环境完整性警告
            if (info != null && info.wineRuntimeWarning != null && !info.wineRuntimeWarning.isEmpty()) {
                TextView rtWarn = new TextView(ctx);
                rtWarn.setText("⚠ " + info.wineRuntimeWarning);
                rtWarn.setTextColor(0xFFFF7043);
                rtWarn.setTextSize(12);
                rtWarn.setLayoutParams(topLp(6));
                root.addView(rtWarn);
            }

            ScrollView sv = new ScrollView(ctx);
            sv.addView(root);

            final File pkgFile = e.file;
            new AlertDialog.Builder(ctx)
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
                    boolean deleted = e.file.delete();
                    // BUG6：同时删除同目录同名.png图标
                    boolean iconDeleted = false;
                    if (e.iconPath != null && !e.iconPath.isEmpty()) {
                        File iconFile = new File(e.iconPath);
                        if (iconFile.exists()) iconDeleted = iconFile.delete();
                    } else {
                        // 兜底：根据配置文件名推导同名.png
                        String base = basename(e.file.getName());
                        File icon = new File(e.file.getParentFile(), base + ".png");
                        if (icon.exists()) iconDeleted = icon.delete();
                    }
                    if (deleted) {
                        Toast.makeText(ctx, iconDeleted ? "已删除配置和图标" : "已删除", Toast.LENGTH_SHORT).show();
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
     * 导入前的确认对话框（pre-apply diff）。
     * 分区展示：游戏信息 / 包含内容 / 将应用的设置 / 依赖检查。
     * Wine 缺失时不阻断弹窗，而是禁用确认按钮并给出警告。
     */
    private void confirmAndImportPackage(final Context ctx, final File tempFile,
                                         final GameRestorePackageManager.PackageInfo pkgInfo) {
        final boolean wineBundled = pkgInfo.containsWineRuntime;
        final boolean wineInstalled = wineBundled
                || GameRestorePackageManager.isWineVersionInstalled(ctx, pkgInfo.wineVersion);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));

        // ── 游戏信息 ──
        root.addView(sectionTitle(ctx, "游戏信息"), topLp(0));
        StringBuilder gi = new StringBuilder();
        gi.append("游戏名称: ").append(pkgInfo.gameName).append('\n');
        // 隐私保护：exe 只显示文件名，不显示路径
        String exeName = pkgInfo.executableName != null ? pkgInfo.executableName : "";
        if (!exeName.isEmpty()) {
            int slash = Math.max(exeName.lastIndexOf('/'), exeName.lastIndexOf(File.separatorChar));
            if (slash >= 0 && slash < exeName.length() - 1) exeName = exeName.substring(slash + 1);
            gi.append("exe文件: ").append(exeName);
        }
        root.addView(bodyText(ctx, gi.toString().trim()));

        // ── 包含内容 ──
        root.addView(sectionTitle(ctx, "包含内容"), topLp(14));
        StringBuilder ci = new StringBuilder();
        ci.append("游戏文件 ").append(pkgInfo.containsGameFiles ? "✓" : "✗").append('\n');
        ci.append("Wine运行环境 ").append(pkgInfo.containsWineRuntime ? "✓" : "✗").append('\n');
        ci.append("快捷方式独立配置 ").append(pkgInfo.hasShortcutConfig ? "✓" : "✗");
        root.addView(bodyText(ctx, ci.toString()));

        // ── 将应用的设置（只显示非默认/非空关键项）──
        root.addView(sectionTitle(ctx, "将应用的设置"), topLp(14));
        String diff = GameRestorePackageManager.getConfigDiffFromMetadata(pkgInfo, ctx);
        root.addView(bodyText(ctx, diff.isEmpty() ? "（全部为默认设置）" : diff));

        // ── 依赖检查 ──
        root.addView(sectionTitle(ctx, "依赖检查"), topLp(14));
        TextView depView = new TextView(ctx);
        StringBuilder dep = new StringBuilder();
        dep.append("Wine版本: ");
        if (wineBundled) dep.append("数据包内置 ✓");
        else if (wineInstalled) dep.append("已安装 ✓");
        else dep.append("未安装 ✗");
        depView.setText(dep.toString());
        depView.setTextSize(13);
        depView.setLineSpacing(dp(2), 1.25f);
        depView.setTextColor(wineInstalled ? 0xFFE6E9EF : 0xFFFF7043);
        root.addView(depView);
        if (!wineInstalled) {
            TextView warn = new TextView(ctx);
            warn.setText("⚠ 请先在「设置 → 组件管理」安装 " + pkgInfo.wineVersion + " 后再导入");
            warn.setTextColor(0xFFFF7043);
            warn.setTextSize(12);
            warn.setLayoutParams(topLp(6));
            root.addView(warn);
        }
        // BUG4：Wine运行环境完整性警告
        if (pkgInfo.wineRuntimeWarning != null && !pkgInfo.wineRuntimeWarning.isEmpty()) {
            TextView rtWarn = new TextView(ctx);
            rtWarn.setText("⚠ " + pkgInfo.wineRuntimeWarning);
            rtWarn.setTextColor(0xFFFF7043);
            rtWarn.setTextSize(12);
            rtWarn.setLayoutParams(topLp(6));
            root.addView(rtWarn);
        }

        ScrollView sv = new ScrollView(ctx);
        sv.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("导入游戏数据包")
                .setView(sv)
                .setPositiveButton("确认导入", null)
                .setNegativeButton("取消", (d, w) -> tempFile.delete())
                .create();
        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (!wineInstalled) {
                // 依赖缺失：禁用确认按钮，点击提示先安装
                positive.setEnabled(false);
                positive.setOnClickListener(v -> Toast.makeText(ctx,
                        "请先安装 " + pkgInfo.wineVersion + " 后再导入",
                        Toast.LENGTH_LONG).show());
            } else {
                positive.setOnClickListener(v -> {
                    dialog.dismiss();
                    startPackageImport(ctx, tempFile);
                });
            }
        });
        dialog.show();
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
