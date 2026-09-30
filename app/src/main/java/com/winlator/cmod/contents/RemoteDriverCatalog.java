package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.contentdialog.RepositoryManagerDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class RemoteDriverCatalog {
    private RemoteDriverCatalog() {}

    // 内存缓存：避免重复网络请求，缓存5分钟
    private static List<Entry> cachedResult = null;
    private static long cacheTime = 0;
    private static final long CACHE_DURATION = 5 * 60 * 1000L;

    public static final class Entry {
        public final String repository;
        public final String name;
        public final String url;
        public final String tagName;
        public final long publishedAt;

        Entry(String repository, String name, String url, String tagName, long publishedAt) {
            this.repository = repository;
            this.name = name;
            this.url = url;
            this.tagName = tagName;
            this.publishedAt = publishedAt;
        }
    }

    public static List<Entry> load(Context context) {
        // 检查缓存
        long now = System.currentTimeMillis();
        if (cachedResult != null && (now - cacheTime) < CACHE_DURATION) {
            return new ArrayList<>(cachedResult);
        }

        ArrayList<Entry> result = new ArrayList<>();
        OkHttpClient http = new OkHttpClient();
        for (DriverRepo repo : RepositoryManagerDialog.loadDriverRepos(context, 0)) {
            if (repo.apiUrl == null || repo.apiUrl.isEmpty()) continue;
            try (Response response = http.newCall(new Request.Builder().url(repo.apiUrl).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) continue;
                JSONArray releases = new JSONArray(response.body().string());
                int accepted = 0;
                for (int i = 0; i < releases.length() && accepted < 50; i++) {
                    JSONObject release = releases.optJSONObject(i);
                    if (release == null) continue;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets == null || assets.length() == 0) continue;

                    String releaseName = release.optString("name", release.optString("tag_name", "")).trim();
                    String tagName = release.optString("tag_name", "").trim();
                    long publishedAt = 0;
                    try {
                        String published = release.optString("published_at", "");
                        if (!published.isEmpty()) {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                            publishedAt = sdf.parse(published).getTime();
                        }
                    } catch (Exception ignored) {}

                    // 读取所有.zip asset，每个变体作为独立条目
                    for (int j = 0; j < assets.length() && accepted < 50; j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) continue;
                        String downloadUrl = asset.optString("browser_download_url", "");
                        String assetName = asset.optString("name", "");
                        if (downloadUrl.isEmpty() || !assetName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) continue;

                        String name = assetName.replaceFirst("(?i)\\.zip$", "");
                        if (name.isEmpty()) continue;

                        result.add(new Entry(repo.name, name, downloadUrl, tagName, publishedAt));
                        accepted++;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 按发布时间降序排序（最新在前）
        Collections.sort(result, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.publishedAt, a.publishedAt);
            }
        });
        // 只有成功获取到数据才更新缓存；失败时保留旧缓存
        if (!result.isEmpty()) {
            cachedResult = new ArrayList<>(result);
            cacheTime = now;
        } else if (cachedResult != null) {
            // 网络失败但有缓存，返回缓存
            return new ArrayList<>(cachedResult);
        }
        return result;
    }

    public static String install(Context context, String url) {
        File archive = new File(context.getCacheDir(), "winz-driver-" + System.nanoTime() + ".zip");
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return new AdrenotoolsManager(context).installDriver(Uri.fromFile(archive));
        } catch (Exception ignored) {
            return "";
        } finally {
            archive.delete();
        }
    }
}
