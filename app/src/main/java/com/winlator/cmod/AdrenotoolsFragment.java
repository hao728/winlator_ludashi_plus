package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.contentdialog.RepositoryManagerDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.Downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class AdrenotoolsFragment extends Fragment {
    private AdrenotoolsManager adrenotoolsManager;
    private RecyclerView recyclerView;
    private LinearLayout categoryTabs;
    private String selectedCategory = "已安装";
    private ReleaseAdapter releaseAdapter;
    private List<DriverRepo> driverRepos;

    // Release数据结构
    private static class ReleaseItem {
        String name, tagName, description, repoName;
        long publishedAt;
        List<AssetItem> assets = new ArrayList<>();
    }

    private static class AssetItem {
        String name, url;
        long size;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.adrenotoolsManager = new AdrenotoolsManager(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.adrenotools_fragment, container, false);

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        releaseAdapter = new ReleaseAdapter(new ArrayList<>());
        recyclerView.setAdapter(releaseAdapter);

        categoryTabs = layout.findViewById(R.id.LLCategoryTabs);

        // 仓库管理按钮
        layout.findViewById(R.id.BTManageRepos).setOnClickListener(v -> {
            RepositoryManagerDialog dialog = new RepositoryManagerDialog(getContext());
            dialog.setOnDismissCallback(() -> {
                driverRepos = RepositoryManagerDialog.loadDriverRepos(getContext(), 0);
                buildCategoryTabs();
            });
            dialog.show();
        });

        // 刷新按钮
        layout.findViewById(R.id.BTRefresh).setOnClickListener(v -> loadCategory(selectedCategory));

        // 从文件安装驱动
        layout.findViewById(R.id.BTInstallDriver).setOnClickListener(v -> {
            ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            });
        });

        driverRepos = RepositoryManagerDialog.loadDriverRepos(getContext(), 0);
        buildCategoryTabs();
        loadCategory("已安装");

        return layout;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.adrenotools_gpu_drivers);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            String driver = adrenotoolsManager.installDriver(uri);
            if (!driver.isEmpty()) {
                Toast.makeText(getContext(), "已安装: " + driver, Toast.LENGTH_SHORT).show();
                if (selectedCategory.equals("已安装")) loadCategory("已安装");
            }
        }
    }

    // 构建作者分类Tab
    private void buildCategoryTabs() {
        categoryTabs.removeAllViews();
        List<String> categories = new ArrayList<>();
        categories.add("已安装");
        for (DriverRepo repo : driverRepos) {
            // 简化仓库名作为分类名
            String shortName = repo.name.replace(" Turnip Drivers", "").replace(" Drivers", "");
            if (!categories.contains(shortName)) categories.add(shortName);
        }

        for (String cat : categories) {
            TextView tab = createTab(cat, cat.equals(selectedCategory));
            tab.setOnClickListener(v -> {
                selectedCategory = cat;
                updateTabStyles();
                loadCategory(cat);
            });
            categoryTabs.addView(tab);
        }
    }

    private TextView createTab(String text, boolean selected) {
        TextView tab = new TextView(getContext());
        tab.setText(text);
        tab.setTextSize(14sp);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(20, 10, 20, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8, 0);
        tab.setLayoutParams(lp);
        if (selected) {
            tab.setBackgroundColor(0xFF1A73E8);
            tab.setTextColor(Color.WHITE);
            tab.setTypeface(null, Typeface.BOLD);
        } else {
            tab.setBackgroundColor(0xFF2A2A2A);
            tab.setTextColor(0xFFB0B0B0);
        }
        return tab;
    }

    private void updateTabStyles() {
        for (int i = 0; i < categoryTabs.getChildCount(); i++) {
            View child = categoryTabs.getChildAt(i);
            if (child instanceof TextView) {
                TextView tab = (TextView) child;
                boolean selected = tab.getText().equals(selectedCategory);
                if (selected) {
                    tab.setBackgroundColor(0xFF1A73E8);
                    tab.setTextColor(Color.WHITE);
                    tab.setTypeface(null, Typeface.BOLD);
                } else {
                    tab.setBackgroundColor(0xFF2A2A2A);
                    tab.setTextColor(0xFFB0B0B0);
                    tab.setTypeface(null, Typeface.NORMAL);
                }
            }
        }
    }

    // 加载分类内容
    private void loadCategory(String category) {
        if (category.equals("已安装")) {
            List<ReleaseItem> installed = new ArrayList<>();
            for (String name : adrenotoolsManager.enumarateInstalledDrivers()) {
                ReleaseItem item = new ReleaseItem();
                item.name = name;
                item.tagName = "已安装";
                item.publishedAt = 0;
                item.repoName = "本地";
                installed.add(item);
            }
            releaseAdapter.setData(installed);
            return;
        }

        // 找到对应仓库
        DriverRepo targetRepo = null;
        for (DriverRepo repo : driverRepos) {
            String shortName = repo.name.replace(" Turnip Drivers", "").replace(" Drivers", "");
            if (shortName.equals(category)) {
                targetRepo = repo;
                break;
            }
        }
        if (targetRepo == null) return;

        releaseAdapter.setData(new ArrayList<>());
        Toast.makeText(getContext(), "加载 " + category + " 驱动...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            List<ReleaseItem> releases = fetchReleases(targetRepo);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> releaseAdapter.setData(releases));
            }
        });
    }

    // 从GitHub API获取release列表
    private List<ReleaseItem> fetchReleases(DriverRepo repo) {
        List<ReleaseItem> result = new ArrayList<>();
        String jsonStr = Downloader.downloadString(repo.apiUrl);
        if (jsonStr == null) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "连接失败!", Toast.LENGTH_SHORT).show());
            }
            return result;
        }

        try {
            JSONArray array = new JSONArray(jsonStr);
            for (int i = 0; i < array.length() && result.size() < 30; i++) {
                JSONObject releaseObj = array.getJSONObject(i);
                ReleaseItem item = new ReleaseItem();
                item.name = releaseObj.optString("name", releaseObj.optString("tag_name", "Unknown"));
                item.tagName = releaseObj.optString("tag_name", "");
                item.description = releaseObj.optString("body", "");
                item.repoName = repo.name;

                // 解析发布时间
                try {
                    String published = releaseObj.optString("published_at", "");
                    if (!published.isEmpty()) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        item.publishedAt = sdf.parse(published).getTime();
                    }
                } catch (Exception ignored) {}

                // 解析assets
                JSONArray assets = releaseObj.optJSONArray("assets");
                if (assets != null) {
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.getJSONObject(j);
                        String url = asset.optString("browser_download_url", "");
                        String name = asset.optString("name", "");
                        if (url.endsWith(".zip") || url.endsWith(".tzst")) {
                            AssetItem ai = new AssetItem();
                            ai.name = name;
                            ai.url = url;
                            ai.size = asset.optLong("size", 0);
                            item.assets.add(ai);
                        }
                    }
                }
                if (!item.assets.isEmpty()) result.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 按发布时间降序
        Collections.sort(result, (a, b) -> Long.compare(b.publishedAt, a.publishedAt));
        return result;
    }

    // 格式化时间
    private String formatTime(long timestamp) {
        if (timestamp <= 0) return "";
        long diff = System.currentTimeMillis() - timestamp;
        long days = diff / (1000 * 60 * 60 * 24);
        if (days < 1) return "今天";
        if (days < 2) return "昨天";
        if (days < 30) return days + "天前";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    // 格式化文件大小
    private String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    // 下载并安装驱动
    private void installDriver(AssetItem asset) {
        Toast.makeText(getContext(), "下载 " + asset.name + "...", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File tmpFile = new File(getContext().getCacheDir(), "driver_temp.zip");
                if (tmpFile.exists()) tmpFile.delete();
                boolean success = Downloader.downloadFile(asset.url, tmpFile);
                if (success && getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        String installedName = adrenotoolsManager.installDriver(Uri.fromFile(tmpFile));
                        if (!installedName.isEmpty()) {
                            Toast.makeText(getContext(), "已安装: " + installedName, Toast.LENGTH_SHORT).show();
                            tmpFile.delete();
                        } else {
                            Toast.makeText(getContext(), "安装失败! 无效的ZIP文件.", Toast.LENGTH_LONG).show();
                        }
                    });
                } else if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "下载失败!", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // Release列表Adapter
    private class ReleaseAdapter extends RecyclerView.Adapter<ReleaseAdapter.ViewHolder> {
        private List<ReleaseItem> data;

        ReleaseAdapter(List<ReleaseItem> data) { this.data = data; }

        void setData(List<ReleaseItem> data) {
            this.data = data;
            notifyDataSetChanged();
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.driver_release_card, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ReleaseItem item = data.get(position);
            holder.tvName.setText(item.name);

            // Meta信息：时间 + tag + 仓库
            StringBuilder meta = new StringBuilder();
            if (item.publishedAt > 0) meta.append(formatTime(item.publishedAt));
            if (item.tagName != null && !item.tagName.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(item.tagName);
            }
            if (item.assets.size() > 1) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(item.assets.size()).append(" 个变体");
            }
            holder.tvMeta.setText(meta.toString());

            // 描述
            boolean hasDesc = item.description != null && !item.description.trim().isEmpty();
            holder.tvDesc.setVisibility(hasDesc ? View.GONE : View.GONE); // 默认隐藏，点击展开

            // Assets列表
            holder.llAssets.removeAllViews();
            for (AssetItem asset : item.assets) {
                View assetView = LayoutInflater.from(getContext()).inflate(R.layout.driver_asset_item, holder.llAssets, false);
                ((TextView) assetView.findViewById(R.id.TVAssetName)).setText(asset.name);
                ((TextView) assetView.findViewById(R.id.TVAssetSize)).setText(formatSize(asset.size));
                assetView.findViewById(R.id.BTAssetDownload).setOnClickListener(v -> installDriver(asset));
                holder.llAssets.addView(assetView);
            }

            // 展开/收起
            final boolean[] expanded = {false};
            holder.btExpand.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                holder.llAssets.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                holder.tvDesc.setVisibility(expanded[0] && hasDesc ? View.VISIBLE : View.GONE);
                ((TextView) v).setText(expanded[0] ? "收起" : "查看详情");
                if (hasDesc && expanded[0]) {
                    String desc = item.description.replace("\n", " ").trim();
                    if (desc.length() > 200) desc = desc.substring(0, 200) + "...";
                    holder.tvDesc.setText(desc);
                }
            });

            // 主下载按钮：如果只有一个asset直接下载，多个则展开
            holder.btDownload.setOnClickListener(v -> {
                if (item.assets.size() == 1) {
                    installDriver(item.assets.get(0));
                } else {
                    expanded[0] = true;
                    holder.llAssets.setVisibility(View.VISIBLE);
                    holder.btExpand.callOnClick();
                }
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvMeta, tvDesc, btExpand;
            ImageButton btDownload;
            LinearLayout llAssets;
            ViewHolder(View v) {
                super(v);
                tvName = v.findViewById(R.id.TVReleaseName);
                tvMeta = v.findViewById(R.id.TVReleaseMeta);
                tvDesc = v.findViewById(R.id.TVReleaseDesc);
                btExpand = v.findViewById(R.id.BTExpand);
                btDownload = v.findViewById(R.id.BTDownload);
                llAssets = v.findViewById(R.id.LLAssets);
            }
        }
    }
}
