package com.winlator.cmod;

import android.app.Activity;
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

import java.io.File;

public class GameDetailFragment extends Fragment {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    private final String shortcutPath;
    private Shortcut shortcut;

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
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 3001) {
            showExportDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showExportDialog() {
        final Context context = getContext();
        if (context == null) return;

        String[] options = {"仅配置（小，推荐分享）", "包含游戏本体（大，完整移植）"};
        new androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("导出游戏数据包")
                .setItems(options, (dialog, which) -> {
                    boolean includeGameFiles = (which == 1);
                    doExport(includeGameFiles, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExport(boolean includeGameFiles, boolean includeRegistry) {
        final Context context = getContext();
        if (context == null || shortcut == null) return;

        Toast.makeText(context, "开始导出...", Toast.LENGTH_SHORT).show();

        com.winlator.cmod.util.GameRestorePackageManager.exportPackageAsync(context, shortcut,
                includeGameFiles, includeRegistry, "", "",
                new com.winlator.cmod.util.GameRestorePackageManager.ExportCallback() {
                    @Override
                    public void onProgress(int percent, String message) {}

                    @Override
                    public void onComplete(String packagePath) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() ->
                                Toast.makeText(context, "导出完成: " + packagePath, Toast.LENGTH_LONG).show()
                            );
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() ->
                                Toast.makeText(context, "导出失败: " + error, Toast.LENGTH_LONG).show()
                            );
                        }
                    }
                });
    }

}
