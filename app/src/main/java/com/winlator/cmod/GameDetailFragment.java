package com.winlator.cmod;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.ui.library.GameDetailCallbacks;
import com.winlator.cmod.ui.library.GameDetailComposeHost;
import com.winlator.cmod.ui.library.GameSavesComposeDialog;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;
import com.winlator.cmod.util.GameRestorePackageManager;

import org.json.JSONObject;

import java.io.File;

public class GameDetailFragment extends Fragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    private final String shortcutPath;
    private Shortcut shortcut;
    private ProgressDialog exportProgressDialog;

    public GameDetailFragment() {
        this("");
    }

    public GameDetailFragment(String shortcutPath) {
        this.shortcutPath = shortcutPath;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle savedInstanceState) {
        ContainerManager manager = new ContainerManager(requireContext());
        for (Shortcut candidate : manager.loadShortcuts()) {
            if (candidate != null && candidate.file != null
                    && candidate.file.getPath().equals(shortcutPath)) {
                shortcut = candidate;
                break;
            }
        }

        if (shortcut == null) {
            getParentFragmentManager().popBackStack();
            return new FrameLayout(requireContext());
        }

        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle(shortcut.name);
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File userArtwork = new File(Environment.getExternalStorageDirectory(),
                "Winlator/icons/" + baseName + ".user.png");
        File banner = new File(Environment.getExternalStorageDirectory(),
                "Winlator/banners/" + baseName + ".png");
        File cover = new File(Environment.getExternalStorageDirectory(),
                "Winlator/covers/" + baseName + ".png");
        String artworkPath = userArtwork.isFile() ? userArtwork.getPath()
                : banner.isFile() ? banner.getPath()
                : cover.isFile() ? cover.getPath() : null;
        Bitmap fallback = shortcut.icon;

        View content = GameDetailComposeHost.create(
                requireContext(),
                shortcut.name,
                buildEnvironmentSubtitle(),
                artworkPath,
                fallback,
                "1".equals(shortcut.getExtra("favorite", "0")),
                new GameDetailCallbacks() {
                    @Override
                    public void onPlay() {
                        runShortcut();
                    }

                    @Override
                    public void onConfigure() {
                        ShortcutSettingsComposeDialog.show(GameDetailFragment.this, shortcut);
                    }

                    @Override
                    public void onArguments() {
                        runContainer();
                    }

                    @Override
                    public void onSaves() {
                        GameSavesComposeDialog.show(GameDetailFragment.this, shortcut);
                    }

                    @Override
                    public void onFavorite(boolean favorite) {
                        shortcut.putExtra("favorite", favorite ? "1" : "0");
                        shortcut.saveData();
                    }

                    @Override
                    public void onRemove() {
                        ContentDialog.confirm(requireContext(), R.string.do_you_want_to_remove_this_shortcut, () -> {
                            if (shortcut.file.delete()) getParentFragmentManager().popBackStack();
                        });
                    }
                }
        );
        content.post(this::applyDetailChrome);
        return content;
    }

    private String buildEnvironmentSubtitle() {
        String runtime = shortcut.container.getWineVersion();
        try {
            ContentsManager contents = new ContentsManager(requireContext());
            contents.syncContents();
            WineInfo info = WineInfo.fromIdentifier(requireContext(), contents, runtime);
            String version = info.fullVersion();
            if (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
            runtime = ("proton".equalsIgnoreCase(info.type) ? "Proton " : "Wine ") + version + " " + info.getArch();
        } catch (Exception ignored) {}
        String renderer = shortcut.getUseDisplayX() ? "DisplayX" : shortcut.getRendererNative() ? "EGL" : "Vulkan";
        return runtime + "  •  " + renderer;
    }

    private void runShortcut() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            intent.putExtra("shortcut_path", shortcut.file.getPath());
            intent.putExtra("shortcut_name", shortcut.name);
            intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
            intent.putExtra("native_rendering", shortcut.getRendererNative());
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }
    }

    private void runContainer() {
        Activity activity = requireActivity();
        if (!XrActivity.isEnabled(requireContext())) {
            Intent intent = new Intent(activity, XServerDisplayActivity.class);
            intent.putExtra("container_id", shortcut.container.id);
            activity.startActivity(intent);
        } else {
            XrActivity.openIntent(activity, shortcut.container.id, null);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyDetailChrome() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        activity.setDetailMode(true);
        if (isLandscape()) {
            activity.setBottomNavigationVisible(false);
            activity.setMainToolbarVisible(false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDetailChrome();
    }

    @Override
    public void onPause() {
        if (!isLandscape() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(false);
        }
        super.onPause();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.add(0, 3001, 0, "导出游戏数据包");
        menu.add(0, 3002, 1, "导出容器配置");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 3001) {
            showExportDialog();
            return true;
        } else if (item.getItemId() == 3002) {
            exportContainerConfig();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exportContainerConfig() {
        final Context context = getContext();
        if (context == null || shortcut == null || shortcut.container == null) return;

        try {
            // v6：分类存储 - 检测游戏来源（本地/Steam）
            String source = "local";
            String pathLower = (shortcut.path != null) ? shortcut.path.toLowerCase() : "";
            String nameLower = shortcut.name.toLowerCase();
            if (pathLower.contains("steam") || nameLower.contains("steam")) {
                source = "steam";
            }

            // 读取配置并添加gameName/source字段
            File configFile = shortcut.container.getConfigFile();
            String configContent = com.winlator.cmod.core.FileUtils.readString(configFile);
            JSONObject configJson = new JSONObject(configContent);
            configJson.put("gameName", shortcut.name);
            configJson.put("source", source);

            // 分类目录：Configs/local/ 或 Configs/steam/
            File exportDir = new File("/storage/emulated/0/Download/Winlator/Configs/" + source + "/");
            if (!exportDir.exists()) exportDir.mkdirs();

            // 文件名：本地用游戏名，Steam用AppID（从名称中提取数字或用游戏名）
            String fileName;
            if ("steam".equals(source)) {
                // 尝试从名称提取AppID（纯数字），否则用游戏名
                String appId = shortcut.name.replaceAll("[^0-9]", "");
                fileName = (appId.isEmpty() ? shortcut.name : appId) + ".json";
            } else {
                fileName = shortcut.name + ".json";
            }
            // 清理文件名中的非法字符
            fileName = fileName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]", "_");

            File destFile = new File(exportDir, fileName);

            // 导出游戏图标：复制到配置同目录，文件名与JSON同名不同后缀（.png）
            String iconFileName = fileName.substring(0, fileName.length() - ".json".length()) + ".png";
            if (shortcut.iconFile != null && shortcut.iconFile.isFile()) {
                try {
                    File iconDest = new File(exportDir, iconFileName);
                    com.winlator.cmod.core.FileUtils.copy(shortcut.iconFile, iconDest);
                    configJson.put("iconFile", iconFileName);
                } catch (Exception ignored) {}
            }

            com.winlator.cmod.core.FileUtils.writeString(destFile, configJson.toString(2));
            Toast.makeText(context, "容器配置已导出: " + source + "/" + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * v3：导出选项对话框（仅配置 / 包含游戏本体）
     */
    /**
     * v6：导出确认窗口（RadioButton单选组 + 确认按钮）
     * 展示：游戏信息卡 + 容器配置差异 + 快捷方式独立配置 + 导出类型单选
     */
    private void showExportDialog() {
        final Context context = getContext();
        if (context == null || shortcut == null || shortcut.container == null) return;

        // 收集游戏信息（隐私保护：只取文件名和大小，不取路径）
        File gameExe = GameRestorePackageManager.getGameExeFile(shortcut);
        String exeFileName = (gameExe != null) ? gameExe.getName() : "（未找到exe文件）";
        long gameSize = GameRestorePackageManager.getGameDirectorySize(shortcut);
        String gameSizeStr = (gameSize > 0) ? formatSizeMB(gameSize) : "（未找到游戏目录）";
        long wineSize = GameRestorePackageManager.getWineRuntimeSize(shortcut);
        String wineSizeStr = (wineSize > 0) ? formatSizeMB(wineSize) : "（未知）";

        // 容器配置差异 + 快捷方式独立配置
        String configDiff = GameRestorePackageManager.getConfigDiffSummary(shortcut.container);
        String shortcutDiff = GameRestorePackageManager.getShortcutConfigDiff(shortcut);

        // 构建信息文本
        StringBuilder info = new StringBuilder();
        info.append("━━━ 游戏信息 ━━━\n");
        info.append("游戏名称: ").append(shortcut.name).append("\n");
        info.append("exe文件: ").append(exeFileName).append("\n");
        info.append("游戏目录大小: ").append(gameSizeStr).append("\n");
        info.append("Wine运行环境大小: ").append(wineSizeStr).append("\n");
        info.append("关联容器: ").append(shortcut.container.getName())
            .append("（ID: ").append(shortcut.container.id).append("）\n");

        info.append("\n━━━ 容器配置（与默认值对比）━━━\n");
        info.append(configDiff);

        info.append("\n━━━ 快捷方式独立配置 ━━━\n");
        info.append(shortcutDiff);

        // 自定义View：ScrollView + TextView + RadioGroup
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        android.widget.TextView infoText = new android.widget.TextView(context);
        infoText.setText(info.toString());
        infoText.setTextSize(13);
        layout.addView(infoText);

        android.widget.TextView typeLabel = new android.widget.TextView(context);
        typeLabel.setText("\n请选择导出类型：");
        typeLabel.setTextSize(14);
        typeLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(typeLabel);

        final android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(context);
        radioGroup.setOrientation(android.widget.LinearLayout.VERTICAL);

        android.widget.RadioButton rb1 = new android.widget.RadioButton(context);
        rb1.setText("仅配置（容器配置+注册表+快捷方式，最小）");
        rb1.setId(android.view.View.generateViewId());

        android.widget.RadioButton rb2 = new android.widget.RadioButton(context);
        rb2.setText("含游戏本体（+游戏文件目录，约" + gameSizeStr + "）");
        rb2.setId(android.view.View.generateViewId());

        android.widget.RadioButton rb3 = new android.widget.RadioButton(context);
        rb3.setText("完整数据包（+Wine运行环境，约" + wineSizeStr + "，可任意设备还原）");
        rb3.setId(android.view.View.generateViewId());

        radioGroup.addView(rb1);
        radioGroup.addView(rb2);
        radioGroup.addView(rb3);
        radioGroup.check(rb2.getId()); // 默认选中"含游戏本体"
        layout.addView(radioGroup);

        scrollView.addView(layout);

        new AlertDialog.Builder(context)
                .setTitle("导出游戏数据包")
                .setView(scrollView)
                .setPositiveButton("确认导出", (dialog, which) -> {
                    int checkedId = radioGroup.getCheckedRadioButtonId();
                    boolean includeGameFiles = (checkedId == rb2.getId() || checkedId == rb3.getId());
                    boolean includeWineRuntime = (checkedId == rb3.getId());
                    doExport(includeGameFiles, true, includeWineRuntime);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatSizeMB(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * v5：导出时显示ProgressDialog，onProgress实时更新，支持Wine运行环境打包
     */
    private void doExport(boolean includeGameFiles, boolean includeRegistry, boolean includeWineRuntime) {
        final Context context = getContext();
        if (context == null || shortcut == null) return;

        exportProgressDialog = new ProgressDialog(context);
        exportProgressDialog.setTitle("正在导出游戏数据包");
        exportProgressDialog.setMessage("准备中...");
        exportProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        exportProgressDialog.setMax(100);
        exportProgressDialog.setCancelable(false);
        exportProgressDialog.show();

        GameRestorePackageManager.exportPackageAsync(context, shortcut,
                includeGameFiles, includeRegistry, includeWineRuntime, "", "",
                new GameRestorePackageManager.ExportCallback() {
                    @Override
                    public void onProgress(int percent, String message) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (exportProgressDialog != null && exportProgressDialog.isShowing()) {
                                    exportProgressDialog.setProgress(percent);
                                    exportProgressDialog.setMessage(message);
                                }
                            });
                        }
                    }

                    @Override
                    public void onComplete(String packagePath) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (exportProgressDialog != null && exportProgressDialog.isShowing()) {
                                    exportProgressDialog.dismiss();
                                }
                                Toast.makeText(context, "导出完成: " + packagePath, Toast.LENGTH_LONG).show();
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (exportProgressDialog != null && exportProgressDialog.isShowing()) {
                                    exportProgressDialog.dismiss();
                                }
                                Toast.makeText(context, "导出失败: " + error, Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                });
    }

}
