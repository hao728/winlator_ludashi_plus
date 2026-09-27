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
        menu.add(0, 3003, 2, "导入容器配置");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 3001) {
            showExportDialog();
            return true;
        } else if (item.getItemId() == 3002) {
            exportContainerConfig();
            return true;
        } else if (item.getItemId() == 3003) {
            showImportConfigConfirmDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exportContainerConfig() {
        final Context context = getContext();
        if (context == null || shortcut == null || shortcut.container == null) return;

        try {
            File configFile = shortcut.container.getConfigFile();
            File exportDir = new File("/storage/emulated/0/Download/Winlator/Configs/");
            if (!exportDir.exists()) exportDir.mkdirs();
            String timeStamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
            File destFile = new File(exportDir, "container_config_" + shortcut.container.id + "_" + timeStamp + ".json");
            com.winlator.cmod.core.FileUtils.copy(configFile, destFile);
            Toast.makeText(context, "容器配置已导出: " + destFile.getPath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(context, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * v3：导入容器配置前显示确认对话框，说明影响范围
     */
    private void showImportConfigConfirmDialog() {
        final Context context = getContext();
        if (context == null || shortcut == null || shortcut.container == null) return;

        new AlertDialog.Builder(context)
                .setTitle("导入容器配置")
                .setMessage("将覆盖当前容器（" + shortcut.container.getName() + "）的配置：\n" +
                        "• Wine版本\n" +
                        "• 图形驱动 / DXWrapper\n" +
                        "• 组件 / 模拟器设置\n\n" +
                        "注意：此操作不影响快捷方式文件。配置将在下次启动该游戏时生效。")
                .setPositiveButton("选择配置文件", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(Intent.createChooser(intent, "选择容器配置文件"), 9002);
                    } catch (Exception e) {
                        Toast.makeText(context, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * v3：导出选项对话框（仅配置 / 包含游戏本体）
     */
    /**
     * v5：增强版导出确认窗口（隐私保护：不显示任何本地文件路径）
     * 展示：游戏信息卡 + 容器配置差异对比 + 打包内容清单
     * 三选项：仅配置 / 含游戏本体 / 完整数据包（含Wine运行环境）
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

        // 容器配置差异对比
        String configDiff = GameRestorePackageManager.getConfigDiffSummary(shortcut.container);

        // 构建完整信息文本（不含任何本地路径）
        StringBuilder message = new StringBuilder();
        message.append("━━━ 游戏信息 ━━━\n");
        message.append("游戏名称: ").append(shortcut.name).append("\n");
        message.append("exe文件: ").append(exeFileName).append("\n");
        message.append("游戏目录大小: ").append(gameSizeStr).append("\n");
        message.append("Wine运行环境大小: ").append(wineSizeStr).append("\n");
        message.append("关联容器: ").append(shortcut.container.getName())
               .append("（ID: ").append(shortcut.container.id).append("）\n");

        message.append("\n━━━ 容器配置（与默认值对比）━━━\n");
        message.append(configDiff);

        message.append("\n━━━ 请选择导出类型 ━━━\n");
        message.append("1. 仅配置：容器配置+注册表+快捷方式（最小）\n");
        message.append("2. 含游戏本体：上述 + 游戏文件目录（约").append(gameSizeStr).append("）\n");
        message.append("3. 完整数据包：上述 + Wine运行环境（约").append(wineSizeStr).append("，可在任意设备还原）\n");

        final String[] options = {
            "仅导出配置",
            "含游戏本体",
            "完整数据包（含Wine运行环境）"
        };

        new AlertDialog.Builder(context)
                .setTitle("导出游戏数据包")
                .setMessage(message.toString())
                .setItems(options, (dialog, which) -> {
                    boolean includeGameFiles = (which >= 1);
                    boolean includeWineRuntime = (which == 2);
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

    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9002 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri != null && shortcut != null && shortcut.container != null) {
                try {
                    java.io.File tempFile = new java.io.File(getContext().getCacheDir(), "import_config.json");
                    try (java.io.InputStream is = getContext().getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream os = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                    }

                    // v2修复：通过loadData/saveData正确导入，不再raw copy
                    String configContent = FileUtils.readString(tempFile);
                    JSONObject importedConfig = new JSONObject(configContent);
                    importedConfig.put("id", shortcut.container.id);
                    shortcut.container.loadData(importedConfig);
                    shortcut.container.saveData();

                    tempFile.delete();
                    // v3：提示改为"下次启动该游戏时生效"
                    Toast.makeText(getContext(), "容器配置已导入，下次启动该游戏时生效", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(getContext(), "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
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
