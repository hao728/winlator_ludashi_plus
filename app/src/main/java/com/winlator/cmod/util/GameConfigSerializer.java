package com.winlator.cmod.util;

import android.content.Context;
import android.os.Environment;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 游戏配置序列化工具（新格式 v1.0）
 *
 * 格式结构：
 * {
 *   "meta": { format_version, app_source, export_time, game_name, exe_name },
 *   "container_config": { ...容器所有配置字段... },
 *   "shortcut_config": { ...快捷方式独立配置字段（仅当有独立配置时存在）... },
 *   "components": [ {name, type}, ... ]
 * }
 *
 * 与Bannerlator格式兼容：导入时自动识别并映射字段。
 */
public class GameConfigSerializer {

    public static final String FORMAT_VERSION = "1.0";
    public static final String APP_SOURCE = "winlator-ludashi-plus";

    // 快捷方式可独立配置的字段白名单（基于Shortcut.java的getExtra(null)模式）
    private static final String[] SHORTCUT_CONFIG_KEYS = {
        "rendererNative", "useDisplayX", "trueDisplayX", "surfaceFormat",
        "displayXPerformanceMode", "displayXPresentAtRefreshRate",
        "displayXBackPressure", "displayXPrecisePresentation", "displayxConfig",
        "rendererPresentMode", "rendererDriverId", "rendererFilterMode",
        "rendererSwapRB", "lsfgEnabled", "lsfgMultiplier", "lsfgFlowScale",
        "frameGenBackend", "working_dir", "arguments", "disableXinput", "hudMode"
    };

    // 容器配置字段白名单（基于Container.java的getter/setter）
    private static final String[] CONTAINER_CONFIG_KEYS = {
        "screenSize", "envVars", "graphicsDriver", "graphicsWrapper",
        "graphicsDriverConfig", "dxwrapper", "dxwrapperConfig",
        "audioDriver", "wincomponents", "drives", "wineVersion",
        "showFPS", "rendererNative", "rendererPresentMode", "rendererDriverId",
        "rendererFilterMode", "rendererSwapRB", "fullscreenStretched",
        "startupSelection", "cpuList", "cpuListWoW64", "syncCpuTopology",
        "desktopTheme", "fexcoreVersion", "fexcorePreset", "box64Preset",
        "box64Version", "emulator", "midiSoundFont", "inputType",
        "lc_all", "primaryController", "controllerMapping", "exclusiveXInput",
        "useDisplayX", "trueDisplayX", "surfaceFormat",
        "displayXPerformanceMode", "displayXPresentAtRefreshRate",
        "displayXBackPressure", "displayXPrecisePresentation",
        "lsfgEnabled", "lsfgMultiplier", "lsfgFlowScale", "frameGenBackend"
    };

    /**
     * 从Container和Shortcut导出配置为新格式JSON
     */
    public static JSONObject exportConfig(Context context, Shortcut shortcut, Container container) throws Exception {
        JSONObject root = new JSONObject();

        // 1. meta
        JSONObject meta = new JSONObject();
        meta.put("format_version", FORMAT_VERSION);
        meta.put("app_source", APP_SOURCE);
        meta.put("export_time", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date()));
        meta.put("game_name", shortcut.name);
        meta.put("exe_name", shortcut.getExecutable());
        root.put("meta", meta);

        // 2. container_config：从容器配置文件读取所有白名单字段
        JSONObject containerConfig = new JSONObject();
        File cfgFile = container.getConfigFile();
        if (cfgFile.exists()) {
            JSONObject srcCfg = new JSONObject(FileUtils.readString(cfgFile));
            for (String key : CONTAINER_CONFIG_KEYS) {
                if (srcCfg.has(key)) {
                    containerConfig.put(key, srcCfg.get(key));
                }
            }
        }
        root.put("container_config", containerConfig);

        // 3. shortcut_config：仅当快捷方式有独立配置时才写入
        JSONObject shortcutConfig = extractShortcutConfig(shortcut);
        if (shortcutConfig.length() > 0) {
            root.put("shortcut_config", shortcutConfig);
        }

        // 4. components：从container_config推导依赖组件
        JSONArray components = deriveComponents(containerConfig);
        if (components.length() > 0) {
            root.put("components", components);
        }

        return root;
    }

    /**
     * 从快捷方式提取独立配置（排除元数据key）
     */
    private static JSONObject extractShortcutConfig(Shortcut shortcut) {
        JSONObject config = new JSONObject();
        for (String key : SHORTCUT_CONFIG_KEYS) {
            String value = shortcut.getExtra(key, null);
            if (value != null && !value.isEmpty()) {
                try {
                    config.put(key, value);
                } catch (Exception e) {}
            }
        }
        return config;
    }

    /**
     * 从容器配置推导依赖组件列表
     */
    private static JSONArray deriveComponents(JSONObject containerConfig) {
        JSONArray components = new JSONArray();
        try {
            // DXVK
            if (containerConfig.has("dxwrapperConfig")) {
                String cfg = containerConfig.getString("dxwrapperConfig");
                String version = extractKeyValue(cfg, "version");
                if (version != null && !version.isEmpty()) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "DXVK");
                    components.put(comp);
                }
            }
            // VKD3D
            if (containerConfig.has("dxwrapperConfig")) {
                String cfg = containerConfig.getString("dxwrapperConfig");
                String version = extractKeyValue(cfg, "vkd3dVersion");
                if (version != null && !version.isEmpty() && !"None".equalsIgnoreCase(version)) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "VKD3D");
                    components.put(comp);
                }
            }
            // GPU驱动
            if (containerConfig.has("graphicsDriverConfig")) {
                String cfg = containerConfig.getString("graphicsDriverConfig");
                String version = extractKeyValue(cfg, "version");
                if (version != null && !version.isEmpty()) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "GPU");
                    components.put(comp);
                }
            }
            // Wine
            if (containerConfig.has("wineVersion")) {
                String version = containerConfig.getString("wineVersion");
                if (version != null && !version.isEmpty()) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "Wine");
                    components.put(comp);
                }
            }
            // FEXCore
            if (containerConfig.has("fexcoreVersion")) {
                String version = containerConfig.getString("fexcoreVersion");
                if (version != null && !version.isEmpty()) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "FEXCore");
                    components.put(comp);
                }
            }
            // Box64
            if (containerConfig.has("box64Version")) {
                String version = containerConfig.getString("box64Version");
                if (version != null && !version.isEmpty()) {
                    JSONObject comp = new JSONObject();
                    comp.put("name", version);
                    comp.put("type", "Box64");
                    components.put(comp);
                }
            }
        } catch (Exception e) {}
        return components;
    }

    private static String extractKeyValue(String configStr, String key) {
        if (configStr == null || configStr.isEmpty()) return null;
        for (String pair : configStr.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /**
     * 导入配置到Container和Shortcut
     * @return true表示配置中有shortcut_config（快捷方式独立配置）
     */
    public static boolean importConfig(JSONObject config, Container targetContainer, Shortcut targetShortcut) throws Exception {
        // 1. 写入容器配置
        if (config.has("container_config")) {
            JSONObject cc = config.getJSONObject("container_config");
            File cfgFile = targetContainer.getConfigFile();
            JSONObject dst = cfgFile.exists()
                    ? new JSONObject(FileUtils.readString(cfgFile))
                    : new JSONObject();

            Iterator<String> it = cc.keys();
            while (it.hasNext()) {
                String key = it.next();
                // 不覆盖身份信息
                if ("id".equals(key) || "name".equals(key) || "rootDir".equals(key)) continue;
                dst.put(key, cc.get(key));
            }
            FileUtils.writeString(cfgFile, dst.toString());
            targetContainer.loadData(dst);
            targetContainer.saveData();
        }

        // 2. 写入快捷方式独立配置
        boolean hasShortcutConfig = config.has("shortcut_config") && !config.isNull("shortcut_config");
        if (hasShortcutConfig && targetShortcut != null) {
            JSONObject sc = config.getJSONObject("shortcut_config");
            Iterator<String> it = sc.keys();
            while (it.hasNext()) {
                String key = it.next();
                Object val = sc.get(key);
                String strVal = (val == null) ? null : String.valueOf(val);
                targetShortcut.putExtra(key, strVal);
            }
            targetShortcut.saveData();
        }

        return hasShortcutConfig;
    }

    /**
     * 判断是否为Bannerlator格式配置
     */
    public static boolean isBannerlatorFormat(JSONObject config) {
        return config.has("settings") && config.has("components")
                && !config.has("container_config");
    }

    /**
     * 将Bannerlator格式转换为新格式
     *
     * Bannerlator格式：
     *   settings.pc_ls_*  → 容器级组件选择
     *   settings.bl_ext   → 快捷方式级配置
     *   components        → 依赖组件
     */
    public static JSONObject convertFromBannerlator(JSONObject bannerConfig) throws Exception {
        JSONObject root = new JSONObject();

        // meta
        JSONObject meta = new JSONObject();
        meta.put("format_version", FORMAT_VERSION);
        meta.put("app_source", "bannerlator-imported");
        if (bannerConfig.has("meta")) {
            JSONObject bm = bannerConfig.getJSONObject("meta");
            if (bm.has("device")) meta.put("device", bm.getString("device"));
            if (bm.has("soc")) meta.put("soc", bm.getString("soc"));
        }
        root.put("meta", meta);

        JSONObject settings = bannerConfig.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();

        // container_config：从pc_ls_*映射
        JSONObject containerConfig = new JSONObject();

        // Wine版本
        if (settings.has("pc_ls_CONTAINER_LIST")) {
            containerConfig.put("wineVersion", extractBannerComponentName(settings, "pc_ls_CONTAINER_LIST"));
        }
        // DXVK
        if (settings.has("pc_ls_DXVK")) {
            String dxvkName = extractBannerComponentName(settings, "pc_ls_DXVK");
            // dxwrapperConfig中设置version
            String existing = containerConfig.optString("dxwrapperConfig", "");
            containerConfig.put("dxwrapperConfig", setConfigValue(existing, "version", dxvkName));
        }
        // VKD3D
        if (settings.has("pc_ls_VK3k")) {
            String vkd3dName = extractBannerComponentName(settings, "pc_ls_VK3k");
            String existing = containerConfig.optString("dxwrapperConfig", "");
            containerConfig.put("dxwrapperConfig", setConfigValue(existing, "vkd3dVersion", vkd3dName));
        }
        // GPU驱动
        if (settings.has("pc_ls_GPU_DRIVER_")) {
            String gpuName = extractBannerComponentName(settings, "pc_ls_GPU_DRIVER_");
            containerConfig.put("graphicsDriverConfig", "version=" + gpuName);
        }
        // FEXCore
        if (settings.has("pc_set_constant_95")) {
            containerConfig.put("fexcoreVersion", extractBannerComponentName(settings, "pc_set_constant_95"));
        }
        // Graphics Wrapper
        if (settings.has("pc_ls_GRAPHICS_WRAPPER")) {
            containerConfig.put("graphicsWrapper", settings.getString("pc_ls_GRAPHICS_WRAPPER"));
        }
        // 音频驱动
        if (settings.has("pc_ls_AUDIO_DRIVER")) {
            containerConfig.put("audioDriver", settings.getString("pc_ls_AUDIO_DRIVER"));
        }
        // 环境变量
        if (settings.has("pc_ls_environment_variable")) {
            containerConfig.put("envVars", settings.getString("pc_ls_environment_variable"));
        }
        // dxwrapper类型
        containerConfig.put("dxwrapper", "dxvk+vkd3d");

        root.put("container_config", containerConfig);

        // shortcut_config：从bl_ext映射
        if (settings.has("bl_ext")) {
            JSONObject blExt = settings.getJSONObject("bl_ext");
            JSONObject shortcutConfig = new JSONObject();
            // 直接映射白名单内的字段
            for (String key : SHORTCUT_CONFIG_KEYS) {
                if (blExt.has(key)) {
                    shortcutConfig.put(key, blExt.getString(key));
                }
            }
            // bl_ext中的dxwrapperConfig也映射到容器级
            if (blExt.has("dxwrapperConfig")) {
                containerConfig.put("dxwrapperConfig", blExt.getString("dxwrapperConfig"));
            }
            if (shortcutConfig.length() > 0) {
                root.put("shortcut_config", shortcutConfig);
            }
        }

        // components：直接复制
        if (bannerConfig.has("components")) {
            root.put("components", bannerConfig.getJSONArray("components"));
        }

        return root;
    }

    private static String extractBannerComponentName(JSONObject settings, String key) {
        try {
            String val = settings.getString(key);
            // Bannerlator格式: {"name":"xxx"}
            if (val.startsWith("{")) {
                JSONObject obj = new JSONObject(val);
                return obj.optString("name", val);
            }
            return val;
        } catch (Exception e) {
            return "";
        }
    }

    private static String setConfigValue(String configStr, String key, String value) {
        if (configStr == null) configStr = "";
        // 检查key是否存在
        boolean found = false;
        StringBuilder result = new StringBuilder();
        String[] pairs = configStr.split(",");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
                result.append(key).append('=').append(value);
                found = true;
            } else {
                result.append(pair);
            }
            if (i < pairs.length - 1) result.append(',');
        }
        if (!found) {
            if (result.length() > 0) result.append(',');
            result.append(key).append('=').append(value);
        }
        return result.toString();
    }

    /**
     * 获取配置文件保存目录
     */
    public static File getConfigDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "Download/Winlator/Configs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 保存配置到文件
     */
    public static String saveConfigToFile(Context context, JSONObject config, String gameName) throws Exception {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String safeName = gameName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
        String fileName = safeName + "_" + timeStamp + ".json";
        File file = new File(getConfigDir(), fileName);
        FileUtils.writeString(file, config.toString(2));
        return file.getAbsolutePath();
    }
}
