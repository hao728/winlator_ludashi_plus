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
        ArrayList<Entry> result = new ArrayList<>();
        OkHttpClient http = new OkHttpClient();
        for (DriverRepo repo : RepositoryManagerDialog.loadDriverRepos(context, 0)) {
            if (repo.apiUrl == null || repo.apiUrl.isEmpty()) continue;
            try (Response response = http.newCall(new Request.Builder().url(repo.apiUrl).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) continue;
                JSONArray releases = new JSONArray(response.body().string());
                int accepted = 0;
                for (int i = 0; i < releases.length() && accepted < 20; i++) {
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
                            publishedAt = java.text.DateFormat.getDateTimeInstance().parse(
                                published.replace("T", " ").replace("Z", "")
                            ).getTime();
                        }
                    } catch (Exception ignored) {}

                    // 只取第一个.zip asset，避免同一release重复显示
                    String downloadUrl = "";
                    String assetName = "";
                    for (int j = 0; j < assets.length(); j++) {
                        JSONObject asset = assets.optJSONObject(j);
                        if (asset == null) continue;
                        String url = asset.optString("browser_download_url", "");
                        String aname = asset.optString("name", "");
                        if (!url.isEmpty() && aname.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                            downloadUrl = url;
                            assetName = aname;
                            break;
                        }
                    }
                    if (downloadUrl.isEmpty()) continue;

                    String name = releaseName.isEmpty() ? assetName.replaceFirst("(?i)\\.zip$", "") : releaseName;
                    if (name.isEmpty()) continue;

                    result.add(new Entry(repo.name, name, downloadUrl, tagName, publishedAt));
                    accepted++;
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
