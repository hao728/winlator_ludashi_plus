package com.winlator.cmod.util;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 游戏恢复数据包管理器（Game Restore Package）v3
 *
 * v3变更：
 * - metadata新增完整容器环境字段（dxwrapper/wincomponents/emulator/box64Version等）
 * - 新增isWineVersionInstalled()供导入前检查
 * - 新增getExportSummary()供导出确认对话框使用
 * - 导入不再静默回退默认Wine版本，未安装时抛出明确错误
 * - ImportCallback新增onWarning()用于依赖缺失警告
 */
public class GameRestorePackageManager {

    private static final String PACKAGE_DIR = "/storage/emulated/0/Download/Winlator/GamePackages/";
    private static final String METADATA_FILE = "metadata.json";
    private static final String CONTAINER_CONFIG_FILE = ".container";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface ExportCallback {
        void onProgress(int percent, String message);
        void onComplete(String packagePath);
        void onError(String error);
    }

    public interface ImportCallback {
        void onProgress(int percent, String message);
        void onComplete(int containerId, String shortcutName);
        void onError(String error);
        void onWarning(String warning);
    }

    public static class PackageInfo {
        public String gameName;
        public String gameVersion;
        public String author;
        public String description;
        public String wineVersion;
        public boolean containsGameFiles;
        public boolean containsRegistry;
        public boolean containsWineRuntime;
        public long packageSize;
        public String[] requiredComponents;
        public String dxwrapper;
        public String dxwrapperConfig;
        public String wincomponents;
        public String emulator;
        public String box64Version;
        public String fexcoreVersion;
        public String graphicsDriver;
        public String graphicsDriverConfig;
        public String audioDriver;
        public String box64Preset;
        public String fexcorePreset;
        public String envVars;
        public String screenSize;
        // BUG1：渲染器与Vulkan Wrapper
        public boolean rendererNative;
        public String graphicsWrapper;
        // BUG4：Wine运行环境完整性警告
        public String wineRuntimeWarning;
        // v4：快捷方式信息（仅文件名和目录名，不含本地路径）
        public String executableName;
        public String gameDirName;
        // v6：是否包含快捷方式独立配置（per-shortcut）
        public boolean hasShortcutConfig;
    }

    private GameRestorePackageManager() {}

    // ==================== 导出功能 ====================

    public static void exportPackageAsync(Context context, Shortcut shortcut, boolean includeGameFiles,
                                           boolean includeRegistry, boolean includeWineRuntime,
                                           String author, String description,
                                           ExportCallback callback) {
        executor.execute(() -> {
            try {
                String path = exportPackage(context, shortcut, includeGameFiles, includeRegistry,
                        includeWineRuntime, author, description, callback);
                if (path != null) callback.onComplete(path);
            } catch (Exception e) {
                callback.onError("导出失败: " + e.getMessage());
            }
        });
    }

    public static String exportPackage(Context context, Shortcut shortcut, boolean includeGameFiles,
                                        boolean includeRegistry, boolean includeWineRuntime,
                                        String author, String description,
                                        ExportCallback callback) throws Exception {
        File exportDir = new File(PACKAGE_DIR);
        if (!exportDir.exists()) exportDir.mkdirs();

        File tempDir = new File(context.getCacheDir(), "grp_export_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) throw new Exception("无法创建临时目录");

        try {
            // BUG3：导出进度重新分配 0-100%
            callback.onProgress(2, "准备导出...");
            Container container = shortcut.container;
            if (container == null) throw new Exception("快捷方式没有关联容器");

            // v5修复：从配置文件重新加载container，确保内存中的字段是实际配置而非默认值
            File srcConfigFile = container.getConfigFile();
            if (srcConfigFile.exists()) {
                try {
                    container.loadData(new JSONObject(FileUtils.readString(srcConfigFile)));
                } catch (Exception e) {
                    // 配置加载失败时使用内存中已有的配置
                }
            }

            File containerDir = new File(tempDir, "container");
            File shortcutDir = new File(tempDir, "shortcut");
            File gamefilesDir = new File(tempDir, "gamefiles");
            File wineruntimeDir = new File(tempDir, "wineruntime");
            containerDir.mkdirs();
            shortcutDir.mkdirs();
            if (includeGameFiles) gamefilesDir.mkdirs();
            if (includeWineRuntime) wineruntimeDir.mkdirs();

            // 5-10%：导出容器配置
            callback.onProgress(6, "导出容器配置...");
            if (srcConfigFile.exists()) {
                FileUtils.copy(srcConfigFile, new File(containerDir, CONTAINER_CONFIG_FILE));
            }
            callback.onProgress(10, "容器配置已导出");

            // 10-20%：导出注册表（每个文件回调子进度）
            if (includeRegistry) {
                callback.onProgress(12, "导出注册表...");
                File wineDir = new File(container.getRootDir(), ".wine");
                if (wineDir.exists()) {
                    File destRegistryDir = new File(containerDir, "registry");
                    destRegistryDir.mkdirs();
                    String[] regFiles = {"system.reg", "user.reg", "userdef.reg"};
                    int regCount = 0;
                    for (String regFile : regFiles) {
                        File srcReg = new File(wineDir, regFile);
                        if (srcReg.exists()) {
                            FileUtils.copy(srcReg, new File(destRegistryDir, regFile));
                        }
                        regCount++;
                        int regProgress = 12 + (int) ((regCount / (float) regFiles.length) * 8);
                        callback.onProgress(Math.min(20, regProgress), "导出注册表: " + regFile);
                    }
                }
            }
            callback.onProgress(20, "注册表导出完成");

            // 20-40%：导出Wine运行环境（每个顶层目录回调子进度）
            boolean wineRuntimePacked = false;
            if (includeWineRuntime) {
                callback.onProgress(22, "导出Wine运行环境（较大，请耐心等待）...");
                File wineDir = new File(container.getRootDir(), ".wine");
                if (wineDir.exists()) {
                    copyWineRuntime(wineDir, wineruntimeDir, callback, 20, 38);
                    // P2修复9：移除 copyZDriveRuntime 调用——导入端还原分支被前置校验架空，为死代码。
                    wineRuntimePacked = true;
                    callback.onProgress(40, "Wine运行环境已打包");
                }
            }

            File gameExe = resolveExecutableFile(shortcut);
            String gameDirName = null;
            boolean gameFilesPacked = false;

            // 40-70%：导出游戏文件（每个文件/目录回调子进度）
            if (includeGameFiles && gameExe != null && gameExe.exists()) {
                callback.onProgress(42, "正在打包游戏文件: " + gameExe.getName() + "（整个游戏目录，请耐心等待）...");
                File gameParentDir = gameExe.getParentFile();
                if (gameParentDir != null && gameParentDir.exists()) {
                    gameDirName = gameParentDir.getName();
                    File destGameDir = new File(gamefilesDir, gameDirName);
                    copyDirectory(gameParentDir, destGameDir, callback, 40, 70);
                    gameFilesPacked = true;
                    callback.onProgress(70, "游戏目录已打包: " + gameDirName);
                }
            } else if (includeGameFiles) {
                callback.onProgress(55, "提示：未找到游戏文件路径，仅导出配置");
            }

            // 70-75%：导出快捷方式
            callback.onProgress(72, "导出快捷方式配置...");
            JSONObject shortcutJson = buildShortcutJson(shortcut, gameExe, gameDirName);
            FileUtils.writeString(new File(shortcutDir, "shortcut.json"), shortcutJson.toString(2));

            if (shortcut.iconFile != null && shortcut.iconFile.exists()) {
                FileUtils.copy(shortcut.iconFile, new File(shortcutDir, "icon" + getFileExtension(shortcut.iconFile.getName())));
            }
            callback.onProgress(75, "快捷方式已导出");

            // 创建元数据（快速，不占进度区间）
            callback.onProgress(76, "创建元数据...");
            JSONObject metadata = buildMetadata(context, shortcut, container, gameFilesPacked,
                    includeRegistry, wineRuntimePacked, author, description);
            FileUtils.writeString(new File(tempDir, METADATA_FILE), metadata.toString(2));

            // 75-95%：压缩ZIP（每个文件回调子进度）
            callback.onProgress(78, "压缩数据包...");
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String safeGameName = shortcut.name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
            String packageName = safeGameName + "_" + timeStamp + ".grp.zip";
            String packagePath = PACKAGE_DIR + packageName;
            File packageFile = new File(packagePath);

            zipDirectory(tempDir, packageFile, callback, 78, 95);

            // 95-100%：完成
            callback.onProgress(98, "正在保存...");
            callback.onProgress(100, "导出完成");
            return packagePath;

        } finally {
            FileUtils.delete(tempDir);
        }
    }

    // ==================== 导入功能 ====================

    public static void importPackageAsync(Context context, File packageFile, ImportCallback callback) {
        executor.execute(() -> {
            try {
                int[] result = importPackage(context, packageFile, callback);
                if (result != null && result[0] > 0) {
                    callback.onComplete(result[0], result[1] > 0 ? "导入成功" : "");
                }
            } catch (Exception e) {
                callback.onError("导入失败: " + e.getMessage());
            }
        });
    }

    /**
     * v3修复：不再静默回退默认Wine版本；未安装时抛出明确错误；收集依赖警告
     */
    public static int[] importPackage(Context context, File packageFile, ImportCallback callback) throws Exception {
        if (!packageFile.exists()) {
            throw new Exception("无效的游戏数据包文件");
        }
        String pkgName = packageFile.getName().toLowerCase(Locale.US);
        if (!pkgName.endsWith(".grp.zip") && !pkgName.endsWith(".zip")) {
            throw new Exception("无效的游戏数据包文件");
        }

        File tempDir = new File(context.getCacheDir(), "grp_import_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) throw new Exception("无法创建临时目录");

        try {
            // BUG3：导入进度重新分配 0-100%
            // 0-10%：解压并解析metadata
            callback.onProgress(3, "解压数据包...");
            unzipFile(packageFile, tempDir);

            callback.onProgress(7, "读取元数据...");
            File metadataFile = new File(tempDir, METADATA_FILE);
            if (!metadataFile.exists()) throw new Exception("数据包缺少 metadata.json");
            JSONObject metadata = new JSONObject(FileUtils.readString(metadataFile));

            String gameName = metadata.optString("gameName", "Imported Game");
            String wineVersion = metadata.optString("wineVersion", "");
            boolean containsRegistry = metadata.optBoolean("containsRegistry", false);
            boolean containsGameFiles = metadata.optBoolean("containsGameFiles", false);
            boolean containsWineRuntime = metadata.optBoolean("containsWineRuntime", false);

            // BUG5修复：即使数据包自带Wine运行环境（.wine前缀），Wine程序二进制仍需已安装。
            // wineruntime 打包的是 .wine 前缀（drive_c/注册表等），而非 wine/wine64 可执行程序。
            // 因此无论 containsWineRuntime 是否为 true，都必须校验 wineVersion 二进制已安装。
            if (wineVersion != null && !wineVersion.isEmpty()) {
                if (!isWineVersionInstalled(context, wineVersion)) {
                    FileUtils.delete(tempDir);
                    throw new Exception("数据包需要Wine版本 " + wineVersion
                            + "，当前未安装。请先在「设置 → 组件管理」中安装该版本后再导入。");
                }
            }

            JSONObject shortcutJson = null;
            String gameDirName = null;
            String executableName = null;
            File shortcutJsonFile = new File(tempDir, "shortcut/shortcut.json");
            if (shortcutJsonFile.exists()) {
                shortcutJson = new JSONObject(FileUtils.readString(shortcutJsonFile));
                gameDirName = shortcutJson.optString("gameDirName", "");
                executableName = shortcutJson.optString("executableName", "");
                // v4隐私保护：不再读取executablePath本地路径，导入时路径由系统自动生成
            }
            callback.onProgress(10, "元数据解析完成");

            // 10-30%：创建容器
            callback.onProgress(15, "创建容器...");

            ContainerManager manager = new ContainerManager(context);
            ContentsManager contentsManager = new ContentsManager(context);
            int newId = manager.getNextContainerId();

            File rootDir = ImageFs.find(context).getRootDir();
            File homeDir = new File(rootDir, "home");
            if (!homeDir.exists()) homeDir.mkdirs();
            File newContainerDir = new File(homeDir, ImageFs.USER + "-" + newId);
            if (!newContainerDir.mkdirs()) throw new Exception("无法创建容器目录");

            JSONObject importedConfig = null;
            File containerConfigFile = new File(tempDir, "container/" + CONTAINER_CONFIG_FILE);
            if (containerConfigFile.exists()) {
                importedConfig = new JSONObject(FileUtils.readString(containerConfigFile));
            }

            Container newContainer = new Container(newId, manager);
            newContainer.setRootDir(newContainerDir);

            if (importedConfig != null) {
                importedConfig.put("id", newId);
                importedConfig.put("name", gameName + " Container");
                newContainer.loadData(importedConfig);
            } else {
                newContainer.setName(gameName + " Container");
                if (wineVersion != null && !wineVersion.isEmpty()) {
                    newContainer.setWineVersion(wineVersion);
                }
            }

            // P0修复1：清除extraData中的安装状态缓存标记，强制目标设备重新安装所有组件。
            // 这些标记在源设备上表示"已安装/已匹配"，导入后若保留，启动时XServerDisplayActivity
            // 会据等值比较判定"无需重装"而跳过组件安装；但图形wrapper等库在imagefs/usr/lib下，
            // 并不随数据包迁移，导致目标设备图形库缺失→Wine崩溃/桌面闪退。
            // 移除后 container.getExtra(key) 返回空串，与期望配置不等，触发重新安装。
            // 注意：仅清除安装状态标记，保留用户配置项（graphicsWrapper/useDisplayX/lsfgEnabled/
            // frameGenBackend/controlsProfile等）。Container无公开getExtraData()，用putExtra(key,null)移除。
            newContainer.putExtra("dxwrapper", null);
            newContainer.putExtra("installedGraphicsWrapper", null);
            newContainer.putExtra("wincomponents", null);
            newContainer.putExtra("startupSelection", null);
            newContainer.putExtra("desktopTheme", null);
            newContainer.putExtra("box64Version", null);
            newContainer.putExtra("fexcoreVersion", null);

            callback.onProgress(25, "容器配置已加载");

            // 30-50%：还原Wine运行环境（含子进度）
            String containerWineVersion = newContainer.getWineVersion();
            if (containsWineRuntime) {
                callback.onProgress(32, "还原Wine运行环境（数据包内置，正在复制.wine目录）...");
                File srcWineRuntime = new File(tempDir, "wineruntime");
                File destWineDir = new File(newContainerDir, ".wine");
                if (srcWineRuntime.exists()) {
                    // v7：保留符号链接复制，避免dosdevices断裂
                    // 进度条优化：按顶层目录逐个复制并回调当前目录名
                    try {
                        File[] topItems = srcWineRuntime.listFiles();
                        int totalTop = topItems != null ? topItems.length : 0;
                        int doneTop = 0;
                        if (topItems != null) {
                            for (File item : topItems) {
                                File destItem = new File(destWineDir, item.getName());
                                // P2修复8：复制前先回调当前文件名，避免大目录复制期间进度条看似卡住
                                int preP = 32 + (int) ((doneTop / (float) Math.max(1, totalTop)) * 14);
                                callback.onProgress(Math.min(46, preP), "正在复制: " + item.getName());
                                try {
                                    copyDirectoryPreservingSymlinks(item, destItem);
                                } catch (Exception e) {
                                    // 单项失败不阻塞
                                }
                                doneTop++;
                                int p = 32 + (int) ((doneTop / (float) Math.max(1, totalTop)) * 14);
                                callback.onProgress(Math.min(46, p), "还原Wine运行环境: " + item.getName());
                            }
                        }
                        if (totalTop == 0) {
                            copyDirectoryPreservingSymlinks(srcWineRuntime, destWineDir);
                        }
                    } catch (Exception e) {
                        throw new Exception("Wine运行环境还原失败: " + e.getMessage());
                    }
                    // v7：修复dosdevices符号链接、注册表/ini占位并设置目录权限
                    callback.onProgress(47, "修复Wine环境（符号链接/权限）...");
                    fixWineEnvironment(destWineDir);
                    // BUG5：重建z:符号链接指向新容器的正确位置（旧链接指向旧设备绝对路径，已断裂）
                    rebuildZDriveSymlink(destWineDir, newContainerDir);
                    // P2修复9：移除zdrive还原死代码——L308前置校验已确保Wine必须已安装，此分支永不执行。

                    // P0修复2：删除随wineruntime复制过来的源容器原始.desktop文件。
                    // 源容器Desktop下的旧.desktop会被原样复制到新容器，随后createShortcutFromJson
                    // 又会按safeName新建一个.desktop；两者文件名不同导致桌面/菜单显示两个重复快捷方式。
                    File desktopDir = new File(destWineDir, "drive_c/users/" + ImageFs.USER + "/Desktop");
                    if (desktopDir.exists()) {
                        File[] oldDesktops = desktopDir.listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".desktop"));
                        if (oldDesktops != null) {
                            for (File f : oldDesktops) f.delete();
                        }
                    }

                    // P0修复3：即使包含Wine运行时，也要补全 system32/syswow64 标准DLL。
                    // 源容器的system32可能不完整，从已安装Wine版本的lib/wine目录复制缺失DLL，
                    // 不重新解压容器模板（.wine前缀已还原），仅补DLL。
                    try {
                        manager.extractCommonDllsForContainer(containerWineVersion, contentsManager, newContainerDir);
                    } catch (Exception e) {
                        callback.onWarning("系统DLL补全失败: " + e.getMessage());
                    }

                    callback.onProgress(48, "Wine运行环境还原成功");
                } else {
                    throw new Exception("数据包标记包含Wine运行环境，但未找到wineruntime目录");
                }
            } else {
                callback.onProgress(30, "创建容器（提取Wine运行环境）...");
                boolean patternExtracted = manager.extractContainerPatternFile(
                        newContainer, containerWineVersion, contentsManager, newContainerDir, null);

                if (!patternExtracted) {
                    FileUtils.delete(newContainerDir);
                    throw new Exception("无法提取Wine运行环境（版本: " + containerWineVersion + "）。请确认该版本已正确安装。");
                }
                callback.onProgress(48, "容器创建成功，ID: " + newId);
            }

            newContainer.saveData();
            callback.onProgress(50, "容器已保存");

            // P0防护：导入后完整性校验，确保容器配置已落盘、关键运行环境就绪，
            // 缺失项通过onWarning上报（不阻断导入，但帮助定位闪退根因）。
            verifyContainerIntegrity(newContainer, newContainerDir, containsWineRuntime, callback);

            // BUG1修复：将新创建的容器注册到 ContainerManager 内存列表，
            // 否则 manager.getContainers() 不包含新容器，导致导入后UI列表/容器查找失效。
            // maxContainerId 已是本次 newId（getNextContainerId 时取自磁盘最大值+1），
            // 加入列表后若后续复用同一 manager 实例也不会重复分配该 id。
            try {
                manager.getContainers().add(newContainer);
            } catch (Exception ignored) {
            }

            // P0修复5：激活新容器，更新 ~/xuser 符号链接指向新容器目录。
            // 否则xuser仍指向旧/默认容器，启动时Wine读写的不是刚导入的容器数据。
            // 非致命：即使此处失败，启动时也会由ContainerManager修正。
            try {
                manager.activateContainer(newContainer);
            } catch (Exception e) {
                Log.w("GameRestore", "activateContainer failed, will be corrected at startup: " + e.getMessage());
            }

            // v3：检查非Wine依赖，缺失时通过onWarning报告
            checkAndReportDependencies(context, metadata, callback);

            // 50-60%：还原注册表
            if (containsRegistry) {
                callback.onProgress(52, "导入注册表...");
                File registryDir = new File(tempDir, "container/registry");
                if (registryDir.exists()) {
                    File destWineDir = new File(newContainerDir, ".wine");
                    File[] regFiles = registryDir.listFiles();
                    if (regFiles != null) {
                        int regTotal = regFiles.length;
                        int regIdx = 0;
                        for (File regFile : regFiles) {
                            FileUtils.copy(regFile, new File(destWineDir, regFile.getName()));
                            regIdx++;
                            int regProgress = 52 + (int) ((regIdx / (float) Math.max(1, regTotal)) * 8);
                            callback.onProgress(Math.min(60, regProgress), "导入注册表: " + regFile.getName());
                        }
                    }
                }
            }
            callback.onProgress(60, "注册表导入完成");

            // 60-80%：导入游戏文件（含子进度）
            String newExePath = null;
            if (containsGameFiles) {
                callback.onProgress(62, "导入游戏文件...");
                File gamefilesDir = new File(tempDir, "gamefiles");
                if (gamefilesDir.exists()) {
                    File destGamesDir = new File(newContainerDir, ".wine/drive_c/Games");
                    destGamesDir.mkdirs();

                    File[] gameDirs = gamefilesDir.listFiles();
                    if (gameDirs != null) {
                        int dirTotal = gameDirs.length;
                        int dirIdx = 0;
                        for (File gameDir : gameDirs) {
                            if (gameDir.isDirectory()) {
                                File destGameDir = new File(destGamesDir, gameDir.getName());
                                // P2修复8：复制前先回调当前文件名，避免大游戏目录复制期间进度条看似卡住
                                int preDirP = 62 + (int) ((dirIdx / (float) Math.max(1, dirTotal)) * 18);
                                callback.onProgress(Math.min(80, preDirP), "正在复制: " + gameDir.getName());
                                copyDirectorySimple(gameDir, destGameDir);
                                if (gameDirName != null && !gameDirName.isEmpty() && gameDir.getName().equals(gameDirName)) {
                                    if (executableName != null && !executableName.isEmpty()) {
                                        newExePath = new File(destGameDir, executableName).getAbsolutePath();
                                    } else {
                                        File[] exes = destGameDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".exe"));
                                        if (exes != null && exes.length > 0) {
                                            newExePath = exes[0].getAbsolutePath();
                                        }
                                    }
                                }
                            }
                            dirIdx++;
                            int dirProgress = 62 + (int) ((dirIdx / (float) Math.max(1, dirTotal)) * 18);
                            callback.onProgress(Math.min(80, dirProgress), "导入游戏文件: " + gameDir.getName());
                        }
                    }

                    if (newExePath == null) {
                        File[] gameDirs2 = destGamesDir.listFiles();
                        if (gameDirs2 != null) {
                            for (File gd : gameDirs2) {
                                if (gd.isDirectory()) {
                                    File[] exes = gd.listFiles((dir, name) -> name.toLowerCase().endsWith(".exe"));
                                    if (exes != null && exes.length > 0) {
                                        newExePath = exes[0].getAbsolutePath();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            callback.onProgress(80, "游戏文件导入完成");

            // 80-90%：创建快捷方式
            callback.onProgress(83, "创建快捷方式...");
            boolean shortcutCreated = false;
            if (shortcutJson != null) {
                shortcutCreated = createShortcutFromJson(context, newContainer, shortcutJson, gameName, newExePath);
            }

            File shortcutDir = new File(tempDir, "shortcut");
            File[] iconFiles = shortcutDir.listFiles((dir, name) -> name.startsWith("icon."));
            if (iconFiles != null && iconFiles.length > 0) {
                File iconDir64 = newContainer.getIconsDir(64);
                if (!iconDir64.exists()) iconDir64.mkdirs();
                // 修复重复图标：复制后的图标文件名必须与.desktop中Icon=字段一致（safeName）。
                // Shortcut按Icon=safeName在iconsDir查找safeName.png/.ico，若仍叫icon.png会查找失败，
                // 导致回退默认图标或被ExeIconExtractor重新覆盖。safeName规则与createShortcutFromJson完全一致。
                String iconBaseName = (shortcutJson != null) ? shortcutJson.optString("name", gameName) : gameName;
                String iconSafeName = iconBaseName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
                for (File iconFile : iconFiles) {
                    String ext = getFileExtension(iconFile.getName());
                    FileUtils.copy(iconFile, new File(iconDir64, iconSafeName + ext));
                }
            }
            callback.onProgress(90, "快捷方式创建完成");

            // 90-100%：完成
            callback.onProgress(95, "正在完成...");
            callback.onProgress(100, "导入完成");
            return new int[]{newId, shortcutCreated ? 1 : 0};

        } finally {
            FileUtils.delete(tempDir);
        }
    }

    // ==================== 公共查询方法（v3新增） ====================

    /**
     * 检查指定Wine版本是否已安装。
     * 先按原始identifier查询；若识别失败，自动规范化分隔符（下划线→连字符、空格→连字符）
     * 与大小写后重试，兼容不同设备导出的版本字符串格式差异（如 proton_9.0_arm64ec / Proton-9.0-arm64ec）。
     * 同时校验path指向的目录真实存在，避免WineInfo.fromIdentifier解析失败时静默回退到
     * MAIN_WINE_VERSION 造成的误判。
     */
    public static boolean isWineVersionInstalled(Context context, String wineVersion) {
        if (wineVersion == null || wineVersion.isEmpty()) return false;
        try {
            ContentsManager contentsManager = new ContentsManager(context);
            // 候选列表：原始字符串 + 规范化字符串（下划线/空格统一为连字符，小写）
            String normalized = wineVersion.trim()
                    .replace('_', '-')
                    .replaceAll("\\s+", "-")
                    .toLowerCase(Locale.US);
            String[] candidates = normalized.equals(wineVersion.trim())
                    ? new String[] { wineVersion }
                    : new String[] { wineVersion, normalized };
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, candidate);
                if (wineInfo == null) continue;
                if (wineInfo.path != null && !wineInfo.path.isEmpty()) {
                    // 用户请求的就是主版本（随imagefs内置），直接信任，不校验磁盘路径
                    // （imagefs可能尚未完整解压，但主版本本身是内置的）
                    if (WineInfo.isMainWineVersion(candidate)) {
                        return true;
                    }
                    // 其他版本必须校验安装目录真实存在，避免fromIdentifier解析失败时
                    // 静默回退到MAIN_WINE_VERSION造成误判
                    File wineDir = new File(wineInfo.path);
                    if (wineDir.exists() && wineDir.isDirectory()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成导出摘要文本，供确认对话框显示
     */
    public static String getExportSummary(Shortcut shortcut, boolean includeGameFiles) {
        if (shortcut == null || shortcut.container == null) return "无法获取导出信息";
        Container container = shortcut.container;
        StringBuilder sb = new StringBuilder();
        sb.append("游戏名称: ").append(shortcut.name).append("\n");
        sb.append("Wine版本: ").append(container.getWineVersion()).append("\n");
        sb.append("图形驱动: ").append(container.getGraphicsDriver()).append("\n");
        sb.append("DXWrapper: ").append(container.getDXWrapper()).append("\n");
        sb.append("模拟器: ").append(container.getEmulator() != null ? container.getEmulator() : "默认").append("\n");
        sb.append("音频驱动: ").append(container.getAudioDriver()).append("\n");
        sb.append("\n将打包以下内容:\n");
        sb.append("✓ 容器配置（.container JSON）\n");
        sb.append("✓ 快捷方式配置+图标\n");
        if (includeGameFiles) {
            File gameExe = resolveExecutableFile(shortcut);
            if (gameExe != null && gameExe.exists()) {
                File gameDir = gameExe.getParentFile();
                if (gameDir != null && gameDir.exists()) {
                    long size = getDirectorySize(gameDir);
                    sb.append("✓ 游戏本体目录: ").append(gameDir.getName())
                      .append("（约").append(formatSize(size)).append("）\n");
                } else {
                    sb.append("✗ 游戏本体（未找到游戏目录）\n");
                }
            } else {
                sb.append("✗ 游戏本体（未找到exe文件）\n");
            }
        } else {
            sb.append("○ 游戏本体（不包含，仅配置）\n");
        }
        sb.append("\n注意: 数据包不包含Wine运行时文件，导入前需已安装对应Wine版本。");
        return sb.toString();
    }

    // ==================== v4：配置对比与游戏信息公共方法 ====================

    /**
     * 获取游戏exe文件（公开版resolveExecutableFile）
     */
    public static File getGameExeFile(Shortcut shortcut) {
        return resolveExecutableFile(shortcut);
    }

    /**
     * 获取游戏目录大小（字节）
     */
    public static long getGameDirectorySize(Shortcut shortcut) {
        File exe = resolveExecutableFile(shortcut);
        if (exe != null && exe.exists()) {
            File parent = exe.getParentFile();
            if (parent != null && parent.exists()) {
                return getDirectorySize(parent);
            }
        }
        return 0;
    }

    /**
     * v5：获取Wine运行环境大小（排除Games目录），用于导出对话框显示
     */
    public static long getWineRuntimeSize(Shortcut shortcut) {
        if (shortcut == null || shortcut.container == null) return 0;
        File wineDir = new File(shortcut.container.getRootDir(), ".wine");
        if (!wineDir.exists()) return 0;
        return getWineRuntimeSizeRecursive(wineDir);
    }

    private static long getWineRuntimeSizeRecursive(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                if ("drive_c".equals(file.getName())) {
                    File[] subFiles = file.listFiles();
                    if (subFiles != null) {
                        for (File sub : subFiles) {
                            if ("Games".equals(sub.getName())) continue;
                            size += sub.isDirectory() ? getWineRuntimeSizeRecursive(sub) : sub.length();
                        }
                    }
                } else {
                    size += getWineRuntimeSizeRecursive(file);
                }
            } else {
                size += file.length();
            }
        }
        return size;
    }

    /**
     * v5重写：对比容器配置与默认配置，覆盖所有配置字段
     * 使用Container类静态默认常量作为对比基准
     */
    public static String getConfigDiffSummary(Container container) {
        if (container == null) return "";
        StringBuilder sb = new StringBuilder();
        String defaultWine = WineInfo.MAIN_WINE_VERSION.identifier();

        // ━━ 基础运行环境 ━━
        appendDiffIfDifferent(sb, "Wine版本", container.getWineVersion(), defaultWine);
        appendDiffIfDifferent(sb, "图形驱动", container.getGraphicsDriver(), Container.DEFAULT_GRAPHICS_DRIVER);
        if (container.getGraphicsDriverConfig() != null
                && !container.getGraphicsDriverConfig().isEmpty()
                && !container.getGraphicsDriverConfig().equals(Container.DEFAULT_GRAPHICSDRIVERCONFIG)) {
            KeyValueSet gpuCfg = new KeyValueSet(container.getGraphicsDriverConfig());
            String vkVer = gpuCfg.get("vulkanVersion");
            String present = gpuCfg.get("presentMode");
            sb.append("• 图形驱动配置: Vulkan").append(vkVer.isEmpty() ? "" : " " + vkVer)
              .append(", present=").append(present.isEmpty() ? "默认" : present).append("\n");
        }
        appendDiffIfDifferent(sb, "DXWrapper", container.getDXWrapper(), Container.DEFAULT_DXWRAPPER);
        if (container.getDXWrapperConfig() != null && !container.getDXWrapperConfig().isEmpty()) {
            KeyValueSet dxCfg = new KeyValueSet(container.getDXWrapperConfig());
            String dxvkVer = dxCfg.get("version");
            String framerate = dxCfg.get("framerate");
            String async = dxCfg.get("async");
            sb.append("• DXWrapper配置: DXVK").append(dxvkVer.isEmpty() ? "" : " " + dxvkVer)
              .append(", 帧率=").append("0".equals(framerate) ? "不限" : framerate)
              .append(", 异步=").append("1".equals(async) ? "开" : "关").append("\n");
        }
        appendDiffIfDifferent(sb, "Wine组件", container.getWinComponents(), Container.DEFAULT_WINCOMPONENTS);
        appendDiffIfDifferent(sb, "音频驱动", container.getAudioDriver(), Container.DEFAULT_AUDIO_DRIVER);

        // ━━ 模拟器/转译层 ━━
        String emulator = container.getEmulator();
        if (emulator != null && !emulator.isEmpty() && !emulator.equals(Container.DEFAULT_EMULATOR)) {
            sb.append("• 模拟器: ").append(emulator).append("（默认: ").append(Container.DEFAULT_EMULATOR).append("）\n");
        }
        if (container.getBox64Version() != null && !container.getBox64Version().isEmpty()) {
            sb.append("• Box64版本: ").append(container.getBox64Version()).append("\n");
        }
        if (container.getFEXCoreVersion() != null && !container.getFEXCoreVersion().isEmpty()) {
            sb.append("• FEXCore版本: ").append(container.getFEXCoreVersion()).append("\n");
        }
        appendDiffIfDifferent(sb, "Box64预设", container.getBox64Preset(), com.winlator.cmod.box64.Box64Preset.COMPATIBILITY);
        appendDiffIfDifferent(sb, "FEXCore预设", container.getFEXCorePreset(), com.winlator.cmod.fexcore.FEXCorePreset.INTERMEDIATE);

        // ━━ 显示/分辨率 ━━
        appendDiffIfDifferent(sb, "屏幕尺寸", container.getScreenSize(), Container.DEFAULT_SCREEN_SIZE);
        if (container.isShowFPS()) sb.append("• 显示FPS: 开启\n");
        if (container.isFullscreenStretched()) sb.append("• 全屏拉伸: 开启\n");

        // ━━ 渲染器 ━━
        if (container.getRendererNative()) sb.append("• 渲染器: EGL原生（默认: Vulkan）\n");
        appendDiffIfDifferent(sb, "渲染呈现模式", container.getRendererPresentMode(), "fifo");
        if (!"system".equals(container.getRendererDriverId())) {
            sb.append("• 渲染驱动: ").append(container.getRendererDriverId()).append("\n");
        }
        if (container.getRendererFilterMode() != 0) {
            sb.append("• 渲染过滤模式: ").append(container.getRendererFilterMode()).append("\n");
        }
        if (container.getRendererSwapRB()) sb.append("• 渲染交换RB通道: 开启\n");

        // ━━ 图形包装器 ━━
        if (!Container.DEFAULT_GRAPHICS_WRAPPER.equals(container.getGraphicsWrapper())) {
            sb.append("• 图形包装器: ").append(container.getGraphicsWrapper()).append("\n");
        }

        // ━━ DisplayX ━━
        if (container.getUseDisplayX()) sb.append("• DisplayX: 开启\n");
        if (container.getTrueDisplayX()) sb.append("• TrueDisplayX: 开启\n");
        if (!container.getDisplayXPerformanceMode()) sb.append("• DisplayX性能模式: 关闭\n");
        if (!container.getDisplayXPresentAtRefreshRate()) sb.append("• DisplayX刷新率同步: 关闭\n");
        if (container.getDisplayXBackPressure()) sb.append("• DisplayX背压: 开启\n");
        if (container.getDisplayXPrecisePresentation()) sb.append("• DisplayX精确呈现: 开启\n");

        // ━━ 帧生成 ━━
        if (container.isLsfgEnabled()) {
            sb.append("• LSFG帧生成: 开启（倍率: ").append(container.getLsfgMultiplier()).append("）\n");
        }
        String frameGen = container.getFrameGenBackend();
        if (frameGen != null && !frameGen.isEmpty()) {
            sb.append("• 帧生成后端: ").append(frameGen).append("\n");
        }

        // ━━ 环境变量 ━━
        if (container.getEnvVars() != null && !container.getEnvVars().isEmpty()
                && !container.getEnvVars().equals(Container.DEFAULT_ENV_VARS)) {
            String env = container.getEnvVars();
            StringBuilder envSummary = new StringBuilder();
            if (env.contains("mesa_glthread=false")) envSummary.append("mesa_glthread=关 ");
            if (env.contains("WINEESYNC=0")) envSummary.append("ESYNC=关 ");
            if (env.contains("DXVK_HUD")) envSummary.append("DXVK_HUD ");
            if (env.contains("TU_DEBUG")) envSummary.append("TU_DEBUG ");
            if (envSummary.length() == 0) envSummary.append("已自定义");
            sb.append("• 环境变量: ").append(envSummary.toString().trim())
              .append("（").append(env.length()).append("字符）\n");
        }

        // ━━ CPU亲和性 ━━
        if (container.getCPUList() != null && !container.getCPUList().isEmpty()) {
            sb.append("• CPU亲和性: 已自定义\n");
        }
        if (container.getCPUListWoW64() != null && !container.getCPUListWoW64().isEmpty()) {
            sb.append("• WoW64 CPU亲和性: 已自定义\n");
        }
        if (container.isSyncCpuTopology()) sb.append("• CPU拓扑同步: 开启\n");

        // ━━ 输入/手柄 ━━
        if (container.getInputType() != com.winlator.cmod.winhandler.WinHandler.DEFAULT_INPUT_TYPE) {
            sb.append("• 输入类型: ").append(container.getInputType()).append("\n");
        }
        if (container.getPrimaryController() != 1) {
            sb.append("• 主控制器: ").append(container.getPrimaryController()).append("\n");
        }
        if (!container.isExclusiveXInput()) sb.append("• 独占XInput: 关闭\n");

        // ━━ 其他 ━━
        if (container.getStartupSelection() != Container.STARTUP_SELECTION_ESSENTIAL) {
            sb.append("• 启动选择: ").append(container.getStartupSelection()).append("\n");
        }
        if (container.getDesktopTheme() != null
                && !container.getDesktopTheme().equals(com.winlator.cmod.core.WineThemeManager.DEFAULT_DESKTOP_THEME)) {
            sb.append("• 桌面主题: 已自定义\n");
        }
        if (container.getMIDISoundFont() != null && !container.getMIDISoundFont().isEmpty()) {
            sb.append("• MIDI音色: ").append(container.getMIDISoundFont()).append("\n");
        }
        if (container.getLC_ALL() != null && !container.getLC_ALL().isEmpty()) {
            sb.append("• 区域设置: ").append(container.getLC_ALL()).append("\n");
        }
        if (container.getDrives() != null && !container.getDrives().equals(Container.DEFAULT_DRIVES)) {
            sb.append("• 驱动器映射: 已自定义\n");
        }

        if (sb.length() == 0) {
            sb.append("（全部使用默认配置）");
        }
        return sb.toString();
    }

    /**
     * 对比数据包内容器配置与默认配置，返回非默认项+安装状态的格式化文本
     * 格式："• Wine版本: proton-9.0-arm64ec ✓已安装"
     * 功能优化1：统一使用Container.DEFAULT_*常量对比，小选项合并显示
     */
    /**
     * 对比数据包元信息与默认配置，返回「将应用的设置」摘要。
     * 设计原则（参考Mali/Bannerlator）：只提炼关键人类可读项，不暴露细粒度技术字段。
     * 仅显示非默认/非空项，每行一个设置；全部默认时返回空串（由调用方提示）。
     * 安装/依赖状态不在此处显示，由导入弹窗的「依赖检查」区域统一提示。
     */
    public static String getConfigDiffFromMetadata(PackageInfo info, Context context) {
        if (info == null) return "";
        StringBuilder sb = new StringBuilder();
        String defaultWine = WineInfo.MAIN_WINE_VERSION.identifier();

        // Wine版本
        if (isNonDefault(info.wineVersion, defaultWine)) {
            sb.append("• Wine版本: ").append(info.wineVersion).append('\n');
        }
        // BUG1：渲染器（rendererNative=true显示Native，默认false=Vulkan不显示）
        if (info.rendererNative) {
            sb.append("• 渲染器: Native\n");
        }
        // OpenGL驱动（graphicsDriver，附Vulkan API版本）
        boolean gpuDriverDiff = isNonDefault(info.graphicsDriver, Container.DEFAULT_GRAPHICS_DRIVER);
        String vkApiVer = getKvsValue(info.graphicsDriverConfig, "vulkanVersion");
        String vkDriverVer = getKvsValue(info.graphicsDriverConfig, "version");
        if (gpuDriverDiff || !vkDriverVer.isEmpty()) {
            sb.append("• OpenGL驱动: ").append(isNotEmpty(info.graphicsDriver) ? info.graphicsDriver : Container.DEFAULT_GRAPHICS_DRIVER);
            if (!vkApiVer.isEmpty()) sb.append(" (Vulkan ").append(vkApiVer).append(')');
            sb.append('\n');
        }
        // BUG2修复：Vulkan驱动版本（如turnip26.2.0）；version为空但驱动为turnip时显示"内置"
        if (!vkDriverVer.isEmpty()) {
            sb.append("• Vulkan驱动: ").append(vkDriverVer).append('\n');
        } else if (info.graphicsDriver != null
                && (info.graphicsDriver.contains("turnip") || info.graphicsDriver.contains("freedreno"))) {
            sb.append("• Vulkan驱动: 内置\n");
        }
        // BUG2修复：Vulkan Wrapper始终显示（非空即显示），默认wrapper标注"(Current)"
        String gw = info.graphicsWrapper;
        if (isNotEmpty(gw)) {
            if (Container.DEFAULT_GRAPHICS_WRAPPER.equals(gw)) {
                sb.append("• Vulkan Wrapper: Wrapper (Current)\n");
            } else {
                sb.append("• Vulkan Wrapper: ").append(gw).append('\n');
            }
        }
        // BUG2：DXWrapper（附DXVK和VKD3D版本）
        String dxvkVer = getKvsValue(info.dxwrapperConfig, "version");
        String vkd3dVer = getKvsValue(info.dxwrapperConfig, "vkd3dVersion");
        boolean dxwrapperDiff = isNonDefault(info.dxwrapper, Container.DEFAULT_DXWRAPPER);
        if (dxwrapperDiff || !dxvkVer.isEmpty() || !vkd3dVer.isEmpty()) {
            sb.append("• DXWrapper: ").append(isNotEmpty(info.dxwrapper) ? info.dxwrapper : Container.DEFAULT_DXWRAPPER);
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
        String emu = info.emulator;
        boolean emuIsBox64 = emu != null && emu.toLowerCase(Locale.US).contains("box64");
        boolean emuIsFex = emu != null && emu.toLowerCase(Locale.US).contains("fex");
        if (emuIsBox64) {
            sb.append("• 转译器: Box64");
            if (isNotEmpty(info.box64Version)) sb.append(' ').append(info.box64Version);
            if (isNotEmpty(info.box64Preset)) sb.append("（预设: ").append(info.box64Preset).append('）');
            sb.append('\n');
        } else if (emuIsFex) {
            sb.append("• 转译器: FEXCore");
            if (isNotEmpty(info.fexcoreVersion)) sb.append(' ').append(info.fexcoreVersion);
            if (isNotEmpty(info.fexcorePreset)) sb.append("（预设: ").append(info.fexcorePreset).append('）');
            sb.append('\n');
        } else if (isNotEmpty(info.box64Version)) {
            // emulator为空但box64Version非空，默认显示Box64
            sb.append("• 转译器: Box64 ").append(info.box64Version);
            if (isNotEmpty(info.box64Preset)) sb.append("（预设: ").append(info.box64Preset).append('）');
            sb.append('\n');
        } else if (isNotEmpty(info.fexcoreVersion)) {
            // emulator为空但fexcoreVersion非空，默认显示FEXCore
            sb.append("• 转译器: FEXCore ").append(info.fexcoreVersion);
            if (isNotEmpty(info.fexcorePreset)) sb.append("（预设: ").append(info.fexcorePreset).append('）');
            sb.append('\n');
        } else if (isNonDefault(emu, Container.DEFAULT_EMULATOR)) {
            sb.append("• 转译器: ").append(emu).append('\n');
        }
        // 屏幕分辨率
        if (isNonDefault(info.screenSize, Container.DEFAULT_SCREEN_SIZE)) {
            sb.append("• 屏幕分辨率: ").append(info.screenSize).append('\n');
        }
        // 音频驱动
        if (isNonDefault(info.audioDriver, Container.DEFAULT_AUDIO_DRIVER)) {
            sb.append("• 音频驱动: ").append(info.audioDriver).append('\n');
        }
        // 环境变量（非默认时只提示已自定义，不暴露具体变量）
        if (isNotEmpty(info.envVars) && !info.envVars.equals(Container.DEFAULT_ENV_VARS)) {
            sb.append("• 环境变量: 已自定义\n");
        }

        return sb.toString().trim();
    }

    /** 非空且与默认值不同。 */
    private static boolean isNonDefault(String value, String defaultValue) {
        return value != null && !value.isEmpty() && !value.equals(defaultValue);
    }

    private static boolean isNotEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    /** 从 KeyValueSet 配置串中安全读取某个键。 */
    private static String getKvsValue(String config, String key) {
        if (config == null || config.isEmpty()) return "";
        try {
            String v = new KeyValueSet(config).get(key);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * v6：获取快捷方式独立配置（per-shortcut）与容器默认值的差异
     * 只显示在shortcut.extraData中显式设置的项（即覆盖了容器默认值的项）
     */
    public static String getShortcutConfigDiff(Shortcut shortcut) {
        if (shortcut == null) return "";
        StringBuilder sb = new StringBuilder();

        // 渲染器
        if (shortcut.getExtra("rendererNative", null) != null)
            sb.append("• EGL原生渲染: ").append(shortcut.getRendererNative() ? "开" : "关").append("\n");
        if (shortcut.getExtra("rendererPresentMode", null) != null)
            sb.append("• 渲染呈现模式: ").append(shortcut.getRendererPresentMode()).append("\n");
        if (shortcut.getExtra("rendererDriverId", null) != null)
            sb.append("• 渲染驱动ID: ").append(shortcut.getRendererDriverId()).append("\n");
        if (shortcut.getExtra("rendererFilterMode", null) != null)
            sb.append("• 渲染过滤模式: ").append(shortcut.getRendererFilterMode()).append("\n");
        if (shortcut.getExtra("rendererSwapRB", null) != null)
            sb.append("• 交换RB通道: ").append(shortcut.getRendererSwapRB() ? "开" : "关").append("\n");

        // DisplayX
        if (shortcut.getExtra("useDisplayX", null) != null || shortcut.getExtra("displayDriver", null) != null)
            sb.append("• DisplayX: ").append(shortcut.getUseDisplayX() ? "开" : "关").append("\n");
        if (shortcut.getExtra("trueDisplayX", null) != null)
            sb.append("• TrueDisplayX: ").append(shortcut.getTrueDisplayX() ? "开" : "关").append("\n");
        if (shortcut.getExtra("surfaceFormat", null) != null)
            sb.append("• 表面格式: ").append(shortcut.getSurfaceFormat()).append("\n");
        if (shortcut.getExtra("displayXPerformanceMode", null) != null)
            sb.append("• DisplayX性能模式: ").append(shortcut.getDisplayXPerformanceMode() ? "开" : "关").append("\n");
        if (shortcut.getExtra("displayXPresentAtRefreshRate", null) != null)
            sb.append("• 刷新率同步: ").append(shortcut.getDisplayXPresentAtRefreshRate() ? "开" : "关").append("\n");
        if (shortcut.getExtra("displayXBackPressure", null) != null)
            sb.append("• DisplayX背压: ").append(shortcut.getDisplayXBackPressure() ? "开" : "关").append("\n");
        if (shortcut.getExtra("displayXPrecisePresentation", null) != null)
            sb.append("• 精确呈现: ").append(shortcut.getDisplayXPrecisePresentation() ? "开" : "关").append("\n");

        // 帧生成
        if (shortcut.getExtra("lsfgEnabled", null) != null)
            sb.append("• LSFG帧生成: ").append(shortcut.isLsfgEnabled() ? "开" : "关")
              .append("（倍率: ").append(shortcut.getLsfgMultiplier()).append("）\n");
        if (shortcut.getExtra("frameGenBackend", null) != null)
            sb.append("• 帧生成后端: ").append(shortcut.getFrameGenBackend()).append("\n");

        // 其他
        if (shortcut.getExtra("working_dir", null) != null && !shortcut.getExtra("working_dir").isEmpty())
            sb.append("• 工作目录: 已设置\n");
        if (shortcut.getExtra("arguments", null) != null && !shortcut.getExtra("arguments").isEmpty())
            sb.append("• 启动参数: ").append(shortcut.getExtra("arguments")).append("\n");
        if (shortcut.getExtra("disableXinput", null) != null)
            sb.append("• 禁用XInput: ").append("1".equals(shortcut.getExtra("disableXinput")) ? "是" : "否").append("\n");

        if (sb.length() == 0) {
            sb.append("（快捷方式未设置独立配置，继承容器设置）");
        }
        return sb.toString();
    }

    private static void appendDiffIfDifferent(StringBuilder sb, String label, String current, String defaultValue) {
        if (current != null && !current.isEmpty() && !current.equals(defaultValue)) {
            sb.append("• ").append(label).append(": ").append(current)
              .append("（默认: ").append(defaultValue).append("）\n");
        }
    }

    public static PackageInfo parsePackageInfo(File packageFile) {
        File tempDir = null;
        try {
            tempDir = new File(System.getProperty("java.io.tmpdir"), "grp_parse_" + System.currentTimeMillis());
            tempDir.mkdirs();
            unzipFile(packageFile, tempDir);

            File metadataFile = new File(tempDir, METADATA_FILE);
            if (!metadataFile.exists()) return null;

            JSONObject metadata = new JSONObject(FileUtils.readString(metadataFile));
            PackageInfo info = new PackageInfo();
            info.gameName = metadata.optString("gameName", "");
            info.gameVersion = metadata.optString("gameVersion", "");
            info.author = metadata.optString("author", "");
            info.description = metadata.optString("description", "");
            info.wineVersion = metadata.optString("wineVersion", "");
            info.containsGameFiles = metadata.optBoolean("containsGameFiles", false);
            info.containsRegistry = metadata.optBoolean("containsRegistry", false);
            info.containsWineRuntime = metadata.optBoolean("containsWineRuntime", false);
            info.packageSize = packageFile.length();
            info.dxwrapper = metadata.optString("dxwrapper", "");
            info.dxwrapperConfig = metadata.optString("dxwrapperConfig", "");
            info.wincomponents = metadata.optString("wincomponents", "");
            info.emulator = metadata.optString("emulator", "");
            info.box64Version = metadata.optString("box64Version", "");
            info.fexcoreVersion = metadata.optString("fexcoreVersion", "");
            info.graphicsDriver = metadata.optString("graphicsDriver", "");
            info.graphicsDriverConfig = metadata.optString("graphicsDriverConfig", "");
            info.audioDriver = metadata.optString("audioDriver", "");
            info.box64Preset = metadata.optString("box64Preset", "");
            info.fexcorePreset = metadata.optString("fexcorePreset", "");
            info.envVars = metadata.optString("envVars", "");
            info.screenSize = metadata.optString("screenSize", "");
            // BUG1：渲染器与Vulkan Wrapper
            info.rendererNative = metadata.optBoolean("rendererNative", false);
            info.graphicsWrapper = metadata.optString("graphicsWrapper", Container.DEFAULT_GRAPHICS_WRAPPER);
            // BUG5：是否包含Z盘Wine运行时
            info.containsZDrive = metadata.optBoolean("containsZDrive", false);

            JSONArray components = metadata.optJSONArray("requiredComponents");
            if (components != null) {
                info.requiredComponents = new String[components.length()];
                for (int i = 0; i < components.length(); i++) {
                    info.requiredComponents[i] = components.optString(i, "");
                }
            }

            // v4：读取快捷方式信息（仅文件名和目录名，不含本地路径）
            File shortcutJsonFile = new File(tempDir, "shortcut/shortcut.json");
            if (shortcutJsonFile.exists()) {
                JSONObject shortcutJson = new JSONObject(FileUtils.readString(shortcutJsonFile));
                info.executableName = shortcutJson.optString("executableName", "");
                info.gameDirName = shortcutJson.optString("gameDirName", "");
                // BUG7：hasShortcutConfig判断修复——只要shortcutConfig字段存在即为true（即使为空对象）
                info.hasShortcutConfig = shortcutJson.has("shortcutConfig");
            }

            // BUG4：Wine运行环境完整性检查
            if (info.containsWineRuntime) {
                info.wineRuntimeWarning = checkWineRuntimeIntegrity(tempDir);
            }

            return info;
        } catch (Exception e) {
            return null;
        } finally {
            if (tempDir != null) FileUtils.delete(tempDir);
        }
    }

    // ==================== 路径解析核心方法 ====================

    private static File resolveExecutableFile(Shortcut shortcut) {
        String path = shortcut.path;
        if (path != null && !path.isEmpty()) {
            File f = new File(stripQuotes(path));
            if (f.exists()) return f;
        }

        try {
            if (shortcut.file != null && shortcut.file.exists()) {
                List<String> lines = Files.readAllLines(shortcut.file.toPath());
                for (String line : lines) {
                    if (line.startsWith("Exec=")) {
                        String extracted = extractPathFromExecLine(line);
                        if (extracted != null && !extracted.isEmpty()) {
                            File f = new File(extracted);
                            if (f.exists()) return f;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }

        if (path != null && !path.isEmpty()) {
            File mapped = mapWinePathToReal(shortcut.container, stripQuotes(path));
            if (mapped != null && mapped.exists()) return mapped;
        }

        return null;
    }

    private static String extractPathFromExecLine(String execLine) {
        if (execLine == null || execLine.isEmpty()) return null;
        String line = execLine.trim();
        if (!line.startsWith("Exec=") && !line.contains("wine ")) {
            return stripQuotes(line);
        }
        if (line.startsWith("Exec=")) {
            line = line.substring(5);
        }
        int wineIdx = line.lastIndexOf("wine ");
        if (wineIdx >= 0) {
            String afterWine = line.substring(wineIdx + 5).trim();
            return stripQuotes(afterWine);
        }
        int quoteStart = line.indexOf('"');
        if (quoteStart >= 0) {
            int quoteEnd = line.indexOf('"', quoteStart + 1);
            if (quoteEnd > quoteStart) {
                return line.substring(quoteStart + 1, quoteEnd);
            }
        }
        return stripQuotes(line.trim());
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ==================== 内部工具方法 ====================

    /**
     * v3：buildMetadata扩展完整容器环境字段
     * P2修复9：移除containsZDrive字段（导入端为死代码，导出白白增加数据包体积）
     */
    private static JSONObject buildMetadata(Context context, Shortcut shortcut, Container container,
                                             boolean gameFilesPacked, boolean includeRegistry,
                                             boolean containsWineRuntime,
                                             String author, String description) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("version", 3);
        metadata.put("packageType", "game-restore");
        metadata.put("gameName", shortcut.name);
        metadata.put("gameVersion", "1.0");
        metadata.put("author", author != null ? author : "");
        metadata.put("description", description != null ? description : "");
        metadata.put("wineVersion", container.getWineVersion());
        metadata.put("createdAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(new Date()));
        metadata.put("containsGameFiles", gameFilesPacked);
        metadata.put("containsRegistry", includeRegistry);
        metadata.put("containsWineRuntime", containsWineRuntime);

        // v3：完整容器环境字段
        metadata.put("graphicsDriver", container.getGraphicsDriver() != null ? container.getGraphicsDriver() : "");
        metadata.put("graphicsDriverConfig", container.getGraphicsDriverConfig() != null ? container.getGraphicsDriverConfig() : "");
        metadata.put("dxwrapper", container.getDXWrapper() != null ? container.getDXWrapper() : "");
        metadata.put("dxwrapperConfig", container.getDXWrapperConfig() != null ? container.getDXWrapperConfig() : "");
        metadata.put("wincomponents", container.getWinComponents() != null ? container.getWinComponents() : "");
        metadata.put("audioDriver", container.getAudioDriver() != null ? container.getAudioDriver() : "");
        metadata.put("emulator", container.getEmulator() != null ? container.getEmulator() : "");
        metadata.put("box64Version", container.getBox64Version() != null ? container.getBox64Version() : "");
        metadata.put("fexcoreVersion", container.getFEXCoreVersion() != null ? container.getFEXCoreVersion() : "");
        metadata.put("box64Preset", container.getBox64Preset() != null ? container.getBox64Preset() : "");
        metadata.put("fexcorePreset", container.getFEXCorePreset() != null ? container.getFEXCorePreset() : "");
        metadata.put("screenSize", container.getScreenSize() != null ? container.getScreenSize() : "");
        metadata.put("envVars", container.getEnvVars() != null ? container.getEnvVars() : "");
        // BUG1：渲染器与Vulkan Wrapper
        metadata.put("rendererNative", container.getRendererNative());
        String gw = container.getGraphicsWrapper();
        metadata.put("graphicsWrapper", gw != null ? gw : Container.DEFAULT_GRAPHICS_WRAPPER);

        // v3：完整依赖列表
        JSONArray components = new JSONArray();
        components.put(container.getWineVersion());
        if (container.getGraphicsDriver() != null && !container.getGraphicsDriver().isEmpty()) {
            components.put("graphics-driver:" + container.getGraphicsDriver());
        }
        if (container.getDXWrapper() != null && !container.getDXWrapper().isEmpty()) {
            components.put("dxwrapper:" + container.getDXWrapper());
        }
        if (container.getBox64Version() != null && !container.getBox64Version().isEmpty()) {
            components.put("box64:" + container.getBox64Version());
        }
        if (container.getFEXCoreVersion() != null && !container.getFEXCoreVersion().isEmpty()) {
            components.put("fexcore:" + container.getFEXCoreVersion());
        }
        metadata.put("requiredComponents", components);

        return metadata;
    }

    /**
     * v3：检查非Wine依赖，缺失时通过onWarning报告
     */
    private static void checkAndReportDependencies(Context context, JSONObject metadata, ImportCallback callback) {
        try {
            ContentsManager contentsManager = new ContentsManager(context);

            // 检查图形驱动
            String gpuDriver = metadata.optString("graphicsDriver", "");
            if (gpuDriver != null && !gpuDriver.isEmpty() && !"zink".equals(gpuDriver) && !"freedreno".equals(gpuDriver)) {
                callback.onWarning("数据包使用图形驱动: " + gpuDriver + "，请确认该驱动已安装。");
            }

            // 检查DXWrapper
            String dxwrapper = metadata.optString("dxwrapper", "");
            if (dxwrapper != null && !dxwrapper.isEmpty() && !dxwrapper.contains("dxvk") && !dxwrapper.contains("vkd3d")) {
                callback.onWarning("数据包使用DXWrapper: " + dxwrapper + "，请确认对应组件已安装。");
            }

            // 检查Box64版本
            String box64Ver = metadata.optString("box64Version", "");
            if (box64Ver != null && !box64Ver.isEmpty()) {
                callback.onWarning("数据包指定Box64版本: " + box64Ver + "，如运行异常请在容器设置中确认。");
            }

            // 检查FEXCore版本
            String fexcoreVer = metadata.optString("fexcoreVersion", "");
            if (fexcoreVer != null && !fexcoreVer.isEmpty()) {
                callback.onWarning("数据包指定FEXCore版本: " + fexcoreVer + "，如运行异常请在容器设置中确认。");
            }
        } catch (Exception e) {
            // 依赖检查失败不阻塞导入
        }
    }

    /**
     * P2修复7：增强Wine运行环境完整性检查。
     * 当 containsWineRuntime=true 时，除检查 wineruntime/ 目录存在非空外，
     * 还校验关键目录和注册表文件是否存在，缺失项追加到警告文本中。
     * 仅警告不阻断导入（fixWineEnvironment 会补全非关键文件）。
     * 返回空字符串表示完整；否则返回警告文本。
     */
    private static String checkWineRuntimeIntegrity(File tempDir) {
        try {
            File wineRuntime = new File(tempDir, "wineruntime");
            if (!wineRuntime.exists() || !wineRuntime.isDirectory()) {
                // wineruntime目录完全缺失才警告（container/registry/单独存在不算Wine运行环境）
                boolean hasRegistryOnly = new File(tempDir, "container/registry/system.reg").exists()
                        || new File(tempDir, "container/registry/user.reg").exists();
                if (!hasRegistryOnly) {
                    return "Wine运行环境不完整，导入后可能无法启动";
                }
                // 仅有注册表而无wineruntime目录：不警告，fixWineEnvironment会补全目录结构
                return "";
            }
            String[] entries = wineRuntime.list();
            if (entries == null || entries.length == 0) {
                return "Wine运行环境目录为空，导入后可能无法启动";
            }

            // P2修复7：校验关键目录和注册表文件，缺失项追加到警告
            StringBuilder missing = new StringBuilder();
            if (!new File(wineRuntime, "drive_c").isDirectory()) {
                missing.append("缺少 drive_c 目录; ");
            } else {
                if (!new File(wineRuntime, "drive_c/windows").isDirectory()) {
                    missing.append("缺少 windows 目录; ");
                }
                if (!new File(wineRuntime, "drive_c/windows/system32").isDirectory()) {
                    missing.append("缺少 system32 目录; ");
                }
            }
            if (!new File(wineRuntime, "dosdevices").isDirectory()) {
                missing.append("缺少 dosdevices 目录; ");
            }
            // 注册表：system.reg 位于 .wine 根目录（即 wineruntime/ 下）
            if (!new File(wineRuntime, "system.reg").exists()
                    && !new File(wineRuntime, "user.reg").exists()) {
                missing.append("缺少 system.reg/user.reg 注册表; ");
            }

            if (missing.length() > 0) {
                return "Wine运行环境可能不完整：" + missing.toString().trim()
                        + "（导入后将自动补全，但仍可能影响首次启动）";
            }
        } catch (Exception e) {
            return "Wine运行环境不完整，导入后可能无法启动";
        }
        return "";
    }

    private static JSONObject buildShortcutJson(Shortcut shortcut, File gameExe, String gameDirName) throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", shortcut.name);
        // v4隐私保护：不存储本地绝对路径，只存文件名和目录名
        json.put("executableName", gameExe != null ? gameExe.getName() : "");
        json.put("gameDirName", gameDirName != null ? gameDirName : "");
        json.put("containerId", shortcut.getContainerId());
        json.put("workingDir", shortcut.getExtra("working_dir", ""));
        json.put("arguments", shortcut.getExtra("arguments", ""));
        json.put("rendererDriverId", shortcut.getRendererDriverId());
        json.put("rendererPresentMode", shortcut.getRendererPresentMode());
        json.put("rendererFilterMode", shortcut.getRendererFilterMode());

        // BUG7：导出快捷方式独立配置（per-shortcut），排除设备专属字段
        // 先导出固定列表中的字段
        JSONObject shortcutConfig = new JSONObject();
        String[] perShortcutKeys = {
            "rendererNative", "rendererPresentMode", "rendererDriverId", "rendererFilterMode", "rendererSwapRB",
            "useDisplayX", "trueDisplayX", "surfaceFormat",
            "displayXPerformanceMode", "displayXPresentAtRefreshRate", "displayXBackPressure", "displayXPrecisePresentation",
            "lsfgEnabled", "lsfgMultiplier", "lsfgFlowScale", "frameGenBackend",
            "working_dir", "arguments", "disableXinput", "hudMode", "favorite"
        };
        for (String key : perShortcutKeys) {
            String value = shortcut.getExtra(key, null);
            if (value != null && !value.isEmpty()) {
                shortcutConfig.put(key, value);
            }
        }
        // BUG7：从.desktop文件读取[Extra Data]段，导出所有额外字段（不只是固定列表）
        readExtraDataFromDesktopFile(shortcut, shortcutConfig);
        // BUG7：即使shortcutConfig为空也写入空对象，导入时知道有这个字段
        json.put("shortcutConfig", shortcutConfig);
        return json;
    }

    /**
     * BUG7：从.desktop文件的[Extra Data]段读取所有键值对，合并到shortcutConfig中。
     * 确保导出shortcut的所有extraData字段，不只是固定列表。
     */
    private static void readExtraDataFromDesktopFile(Shortcut shortcut, JSONObject shortcutConfig) {
        if (shortcut.file == null || !shortcut.file.exists()) return;
        try {
            List<String> lines = Files.readAllLines(shortcut.file.toPath());
            boolean inExtraSection = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    inExtraSection = trimmed.equalsIgnoreCase("[Extra Data]");
                    continue;
                }
                if (!inExtraSection || trimmed.isEmpty()) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                // 跳过设备专属/身份字段
                if ("uuid".equals(key) || "customCoverArtPath".equals(key)) continue;
                if (!shortcutConfig.has(key) && !value.isEmpty()) {
                    shortcutConfig.put(key, value);
                }
            }
        } catch (Exception e) {
            // 读取失败不阻塞导出
        }
    }

    private static boolean createShortcutFromJson(Context context, Container container, JSONObject shortcutJson,
                                                    String defaultName, String newExePath) {
        try {
            String name = shortcutJson.optString("name", defaultName);
            String workingDir = shortcutJson.optString("working_dir", "");
            String arguments = shortcutJson.optString("arguments", "");

            // v4隐私保护：仅使用导入后系统生成的新路径，不读取数据包中的本地路径
            String exePath;
            if (newExePath != null && !newExePath.isEmpty() && new File(newExePath).exists()) {
                exePath = newExePath;
            } else {
                // 回退：在容器Games目录下扫描exe文件
                File gamesDir = new File(container.getRootDir(), ".wine/drive_c/Games");
                File foundExe = scanForExe(gamesDir);
                if (foundExe != null) {
                    exePath = foundExe.getAbsolutePath();
                } else {
                    return false;
                }
            }

            File desktopDir = container.getDesktopDir();
            if (!desktopDir.exists()) desktopDir.mkdirs();

            String safeName = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
            File desktopFile = new File(desktopDir, safeName + ".desktop");
            String winePrefix = container.getRootDir().getAbsolutePath() + "/.wine";

            StringBuilder execBuilder = new StringBuilder();
            execBuilder.append("env WINEPREFIX=\"").append(winePrefix).append("\" wine \"").append(exePath).append("\"");
            if (arguments != null && !arguments.isEmpty()) {
                execBuilder.append(" ").append(arguments);
            }

            try (PrintWriter writer = new PrintWriter(new FileOutputStream(desktopFile))) {
                writer.println("[Desktop Entry]");
                writer.println("Name=" + name);
                writer.println("Exec=" + execBuilder.toString());
                writer.println("Type=Application");
                writer.println("Icon=" + safeName);
                writer.println("container_id:" + container.id);
                writer.println();
                writer.println("[Extra Data]");
                if (workingDir != null && !workingDir.isEmpty()) {
                    writer.println("working_dir=" + workingDir);
                }
                if (arguments != null && !arguments.isEmpty()) {
                    writer.println("arguments=" + arguments);
                }
                String rendererDriverId = shortcutJson.optString("rendererDriverId", "");
                if (!rendererDriverId.isEmpty()) {
                    writer.println("rendererDriverId=" + rendererDriverId);
                }
                String rendererPresentMode = shortcutJson.optString("rendererPresentMode", "");
                if (!rendererPresentMode.isEmpty()) {
                    writer.println("rendererPresentMode=" + rendererPresentMode);
                }
                int rendererFilterMode = shortcutJson.optInt("rendererFilterMode", 0);
                if (rendererFilterMode != 0) {
                    writer.println("rendererFilterMode=" + rendererFilterMode);
                }
                // v6：应用快捷方式独立配置（per-shortcut）
                JSONObject shortcutConfig = shortcutJson.optJSONObject("shortcutConfig");
                if (shortcutConfig != null) {
                    Iterator<String> keys = shortcutConfig.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = shortcutConfig.optString(key, "");
                        if (!value.isEmpty()) {
                            writer.println(key + "=" + value);
                        }
                    }
                }
            }

            return desktopFile.exists();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex) : "";
    }

    private static File mapWinePathToReal(Container container, String winePath) {
        if (winePath == null || winePath.isEmpty()) return null;
        String path = stripQuotes(winePath).trim();

        if (path.startsWith("/")) {
            return new File(path);
        }
        if (path.startsWith("Z:") || path.startsWith("z:")) {
            String realPath = path.substring(2).replace('\\', '/');
            return new File(realPath);
        }
        if (path.startsWith("C:") || path.startsWith("c:")) {
            String relativePath = path.substring(2).replace('\\', '/');
            return new File(container.getRootDir(), ".wine/drive_c" + relativePath);
        }
        if (path.length() >= 2 && path.charAt(1) == ':') {
            String driveLetter = path.substring(0, 1).toUpperCase();
            String relativePath = path.substring(2).replace('\\', '/');
            if (container != null) {
                for (String[] drive : container.drivesIterator()) {
                    if (drive[0].equalsIgnoreCase(driveLetter)) {
                        return new File(drive[1], relativePath);
                    }
                }
            }
            return new File(Environment.getExternalStorageDirectory(), relativePath);
        }
        if (container != null) {
            return new File(container.getRootDir(), ".wine/drive_c/" + path.replace('\\', '/'));
        }
        return null;
    }

    private static long getDirectorySize(File dir) {
        long size = 0;
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                size += getDirectorySize(file);
            } else {
                size += file.length();
            }
        }
        return size;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * v4：在指定目录下递归扫描第一个exe文件
     */
    private static File scanForExe(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = scanForExe(file);
                if (found != null) return found;
            } else if (file.getName().toLowerCase().endsWith(".exe")) {
                return file;
            }
        }
        return null;
    }

    private static void copyDirectory(File src, File dest, ExportCallback callback, int startProgress, int endProgress) {
        if (!src.isDirectory()) return;
        if (!dest.exists()) dest.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        int total = files.length;
        int copied = 0;
        for (File file : files) {
            File destFile = new File(dest, file.getName());
            if (file.isDirectory()) {
                copyDirectorySimple(file, destFile);
            } else {
                FileUtils.copy(file, destFile);
            }
            copied++;
            int progress = startProgress + (int) ((copied / (float) total) * (endProgress - startProgress));
            callback.onProgress(Math.min(endProgress, progress), "复制文件: " + file.getName());
        }
    }

    /**
     * v5：复制Wine运行环境，排除游戏目录（Games）避免重复打包
     * v7：保留符号链接（dosdevices）
     * BUG5修复：不再跳过根目录注册表文件（system.reg/user.reg/userdef.reg）。
     *   原因：若导出时 includeRegistry=false，注册表文件既不会进 container/registry/，
     *   又被这里跳过，导致导入后的 .wine 完全没有注册表，wine 首次启动即崩溃、容器进不去。
     *   现在注册表随 .wine 一起打包；若同时存在 container/registry/ 单独还原，会覆盖此份。
     */
    private static void copyWineRuntime(File wineDir, File destDir, ExportCallback callback,
                                         int startProgress, int endProgress) {
        if (!wineDir.isDirectory() || !destDir.exists()) return;
        File[] files = wineDir.listFiles();
        if (files == null) return;
        int total = files.length;
        int copied = 0;
        for (File file : files) {
            // 排除游戏目录（已在gamefiles/中）
            if (file.isDirectory() && "drive_c".equals(file.getName())) {
                File destDriveC = new File(destDir, "drive_c");
                if (!destDriveC.exists()) destDriveC.mkdirs();
                File[] driveCFiles = file.listFiles();
                if (driveCFiles != null) {
                    for (File subFile : driveCFiles) {
                        if ("Games".equals(subFile.getName())) continue; // 排除游戏目录
                        File destSub = new File(destDriveC, subFile.getName());
                        try {
                            copyDirectoryPreservingSymlinks(subFile, destSub);
                        } catch (Exception e) {
                            // 单个文件复制失败不阻塞整体导出
                        }
                    }
                }
            } else {
                File destFile = new File(destDir, file.getName());
                try {
                    copyDirectoryPreservingSymlinks(file, destFile);
                } catch (Exception e) {
                    // 单个文件复制失败不阻塞整体导出
                }
            }
            copied++;
            int progress = startProgress + (int) ((copied / (float) total) * (endProgress - startProgress));
            callback.onProgress(Math.min(endProgress, progress), "复制Wine运行环境: " + file.getName());
        }
    }

    /**
     * BUG5：导出z:符号链接指向的Wine运行时文件。
     * z:指向容器根目录的上两级（包含wine/wine64可执行文件和库）。
     * 将该目录中的Wine运行时文件复制到wineruntime/zdrive/，排除大的临时文件和缓存。
     * @return true if zdrive was packed
     * @deprecated P2修复9：导入端还原分支被"Wine必须已安装"前置校验架空，为死代码。
     *             已从 exportPackage 中移除调用，保留方法定义以减少影响面。
     */
    @Deprecated
    private static boolean copyZDriveRuntime(File wineDir, File wineruntimeDir, ExportCallback callback) {
        try {
            File zLink = new File(wineDir, "dosdevices/z:");
            if (!Files.isSymbolicLink(zLink.toPath())) return false;
            java.nio.file.Path zTarget = Files.readSymbolicLink(zLink.toPath());
            File zTargetDir = zLink.toPath().getParent().resolve(zTarget).normalize().toFile();
            if (!zTargetDir.exists() || !zTargetDir.isDirectory()) return false;

            // 只复制Wine运行时相关的顶层文件/目录，排除大的缓存和用户数据
            File zdriveDest = new File(wineruntimeDir, "zdrive");
            zdriveDest.mkdirs();
            callback.onProgress(38, "复制Z盘Wine运行时（可能较大）...");

            File[] entries = zTargetDir.listFiles();
            if (entries == null) return false;
            int total = entries.length;
            int done = 0;
            for (File entry : entries) {
                String name = entry.getName();
                // 排除大的临时文件、缓存、home目录（home里已有容器数据）
                if (name.equals("home") || name.equals(".cache") || name.equals(".tmp")
                        || name.equals("tmp") || name.equals("proc") || name.equals("sys")
                        || name.equals("dev") || name.equals("data") || name.equals("sdcard")) {
                    done++;
                    continue;
                }
                File destEntry = new File(zdriveDest, name);
                try {
                    if (entry.isDirectory()) {
                        copyDirectorySimple(entry, destEntry);
                    } else {
                        FileUtils.copy(entry, destEntry);
                    }
                } catch (Exception e) {
                    // 单个文件失败不阻塞
                }
                done++;
                int p = 38 + (int) ((done / (float) Math.max(1, total)) * 2);
                callback.onProgress(Math.min(40, p), "复制Z盘运行时: " + name);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void copyDirectorySimple(File src, File dest) {
        if (!src.isDirectory()) return;
        if (!dest.exists()) dest.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File file : files) {
            File destFile = new File(dest, file.getName());
            if (file.isDirectory()) {
                copyDirectorySimple(file, destFile);
            } else {
                FileUtils.copy(file, destFile);
            }
        }
    }

    /**
     * v7：复制目录时保留符号链接和文件属性。
     * 使用 Files.copy + NOFOLLOW_LINKS 确保 dosdevices/c: 等符号链接不被展开为普通文件。
     */
    private static void copyDirectoryPreservingSymlinks(File src, File dest) throws java.io.IOException {
        if (!src.exists()) return;
        // 符号链接：原样复制链接（不跟随目标）
        if (Files.isSymbolicLink(src.toPath())) {
            if (dest.exists()) dest.delete();
            Files.copy(src.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return;
        }
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            // 复制目录属性
            try {
                Files.setAttribute(dest.toPath(), "lastModifiedTime",
                        Files.getLastModifiedTime(src.toPath()));
            } catch (Exception ignored) {}
            File[] files = src.listFiles();
            if (files == null) return;
            for (File file : files) {
                File destFile = new File(dest, file.getName());
                copyDirectoryPreservingSymlinks(file, destFile);
            }
        } else {
            FileUtils.copy(src, dest);
        }
    }

    /**
     * P0防护：导入后容器完整性校验。
     * 检查项（缺失仅告警，不阻断导入）：
     *   1. .container 配置文件已写入且非空；
     *   2. extraData 中安装状态缓存标记已清除（dxwrapper/installedGraphicsWrapper/
     *      wincomponents/startupSelection/desktopTheme/box64Version/fexcoreVersion）；
     *   3. 若含Wine运行时：.wine 目录、dosdevices/c: 与 z: 符号链接、system.reg/user.reg 存在。
     */
    private static void verifyContainerIntegrity(Container container, File containerDir,
                                                  boolean containsWineRuntime, ImportCallback callback) {
        try {
            // 1. .container 文件落盘校验
            File cfgFile = container.getConfigFile();
            if (!cfgFile.exists() || cfgFile.length() == 0) {
                callback.onWarning("容器配置文件(.container)未正确写入，可能导致启动失败");
            }

            // 2. extraData 缓存标记清除校验（应为空串，即已被putExtra(null)移除）
            String[] cachedKeys = {"dxwrapper", "installedGraphicsWrapper", "wincomponents",
                    "startupSelection", "desktopTheme", "box64Version", "fexcoreVersion"};
            for (String key : cachedKeys) {
                String v = container.getExtra(key);
                if (v != null && !v.isEmpty()) {
                    callback.onWarning("容器缓存标记未清除(" + key + "=" + v + ")，可能跳过组件重装");
                }
            }

            // 3. Wine运行时关键路径校验
            if (containsWineRuntime) {
                File wineDir = new File(containerDir, ".wine");
                if (!wineDir.exists()) {
                    callback.onWarning("Wine运行环境目录(.wine)缺失，容器可能闪退");
                } else {
                    File dosdevices = new File(wineDir, "dosdevices");
                    if (!dosdevices.exists()) {
                        callback.onWarning("dosdevices目录缺失，c:/z:盘符可能不可用");
                    } else {
                        File cLink = new File(dosdevices, "c:");
                        if (!cLink.exists()) {
                            callback.onWarning("dosdevices/c: 链接缺失，C盘不可用");
                        }
                        File zLink = new File(dosdevices, "z:");
                        if (!zLink.exists()) {
                            callback.onWarning("dosdevices/z: 链接缺失，Z盘不可用");
                        }
                    }
                    if (!new File(wineDir, "system.reg").exists()) {
                        callback.onWarning("system.reg 注册表文件缺失");
                    }
                    if (!new File(wineDir, "user.reg").exists()) {
                        callback.onWarning("user.reg 注册表文件缺失");
                    }
                }
            }
        } catch (Exception e) {
            Log.w("GameRestore", "verifyContainerIntegrity failed: " + e.getMessage());
        }
    }

    /**
     * v7：导入Wine运行环境后修复dosdevices符号链接并设置.wine目录权限。
     * 确保 .wine/dosdevices/c: 指向 ../drive_c，断裂则重建。
     * BUG5修复：确保 system.reg/user.reg/userdef.reg 与 system.ini/win.ini 存在，
     *   缺失时写入最小占位内容，避免 wine 首次启动因缺少注册表/ini 文件而崩溃、容器进不去。
     */
    private static void fixWineEnvironment(File wineDir) {
        try {
            // 确保drive_c存在
            File driveC = new File(wineDir, "drive_c");
            if (!driveC.exists()) driveC.mkdirs();
            // BUG5：确保 drive_c 关键子目录存在（users/windows/Program Files 等）
            // （这些通常随 .wine 一起复制；此处仅兜底创建，避免空目录导致 wine 报错）
            new File(driveC, "windows").mkdirs();
            new File(driveC, "users").mkdirs();
            new File(driveC, "Program Files").mkdirs();
            new File(driveC, "Program Files (x86)").mkdirs();

            // 修复dosdevices目录
            File dosdevices = new File(wineDir, "dosdevices");
            if (!dosdevices.exists()) dosdevices.mkdirs();

            // 检查c: -> ../drive_c 符号链接
            File cLink = new File(dosdevices, "c:");
            boolean cLinkOk = false;
            try {
                if (Files.isSymbolicLink(cLink.toPath())) {
                    java.nio.file.Path target = Files.readSymbolicLink(cLink.toPath());
                    if ("../drive_c".equals(target.toString())) cLinkOk = true;
                }
            } catch (Exception ignored) {}
            if (!cLinkOk) {
                if (cLink.exists()) {
                    // 如果是普通文件或目录（解压后断裂），删除后重建符号链接
                    deleteRecursiveQuiet(cLink);
                }
                try {
                    Files.createSymbolicLink(cLink.toPath(),
                            java.nio.file.Paths.get("../drive_c"));
                } catch (Exception e) {
                    // 创建符号链接失败时退化为目录
                    if (!cLink.exists()) cLink.mkdirs();
                }
            }

            // BUG5：确保注册表文件存在，缺失则写最小占位（wine 可在此基础上重建）
            ensureFileContent(new File(wineDir, "system.reg"),
                    "REGEDIT4\n\n[System\\\\ControlSet001\\\\Control\\\\ Wine]\n\"Version\"=\"Wine 8.0\"\n");
            ensureFileContent(new File(wineDir, "user.reg"),
                    "REGEDIT4\n\n[Software\\\\Wine]\n");
            ensureFileContent(new File(wineDir, "userdef.reg"),
                    "REGEDIT4\n\n[Software\\\\Wine]\n");

            // BUG5：确保 system.ini / win.ini 存在
            ensureFileContent(new File(wineDir, "system.ini"),
                    "[drivers]\n" + "midi=mmdrv.dll\n" + "timer=timer.dll\n\n");
            ensureFileContent(new File(wineDir, "win.ini"),
                    "[windows]\n" + "spooler=yes\n" + "load=\n" + "run=\n" + "NetWork=0\n\n");

            // 设置.wine目录及子目录权限
            setPermissionsRecursive(wineDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** BUG5：文件不存在时写入最小占位内容。 */
    private static void ensureFileContent(File file, String content) {
        if (file == null) return;
        if (file.exists() && file.length() >= 0) return;
        try (PrintWriter pw = new PrintWriter(new FileOutputStream(file, false))) {
            pw.print(content);
        } catch (Exception ignored) {}
    }

    /**
     * BUG5：重建z:符号链接，指向新容器根目录的上两级（Wine运行时目录）。
     * 导入时旧z:链接指向旧设备绝对路径，必须用新容器的getRootDir()重建。
     * 同时确保c:符号链接正确指向../drive_c。
     */
    private static void rebuildZDriveSymlink(File wineDir, File newContainerDir) {
        try {
            File dosdevices = new File(wineDir, "dosdevices");
            if (!dosdevices.exists()) dosdevices.mkdirs();

            // z: -> newContainerDir/../..
            File zLink = new File(dosdevices, "z:");
            if (zLink.exists() || Files.isSymbolicLink(zLink.toPath())) {
                deleteRecursiveQuiet(zLink);
            }
            String zTarget = newContainerDir.getPath() + "/../..";
            try {
                Files.createSymbolicLink(zLink.toPath(), java.nio.file.Paths.get(zTarget));
            } catch (Exception e) {
                // P0修复4：创建符号链接失败时不再退化为空目录。
                // 若z:变成空目录而非imagefs根，Wine将找不到二进制（wine/server等）而崩溃。
                // 此处仅记录错误，z:链接缺失时Wine启动阶段会由WineUtils.createDosdevicesSymlinks()重建。
                Log.w("GameRestore", "z: symlink creation failed, will be rebuilt at startup: " + e.getMessage());
            }

            // c: -> ../drive_c（确保正确）
            File cLink = new File(dosdevices, "c:");
            boolean cLinkOk = false;
            try {
                if (Files.isSymbolicLink(cLink.toPath())) {
                    java.nio.file.Path target = Files.readSymbolicLink(cLink.toPath());
                    if ("../drive_c".equals(target.toString())) cLinkOk = true;
                }
            } catch (Exception ignored) {}
            if (!cLinkOk) {
                if (cLink.exists()) deleteRecursiveQuiet(cLink);
                try {
                    Files.createSymbolicLink(cLink.toPath(), java.nio.file.Paths.get("../drive_c"));
                } catch (Exception e) {
                    if (!cLink.exists()) cLink.mkdirs();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void deleteRecursiveQuiet(File file) {
        if (file == null || !file.exists()) return;
        if (Files.isSymbolicLink(file.toPath())) {
            file.delete();
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursiveQuiet(c);
            }
        }
        file.delete();
    }

    private static void setPermissionsRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        } catch (Exception ignored) {}
        if (Files.isSymbolicLink(dir.toPath())) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory()) {
                setPermissionsRecursive(f);
            } else {
                try {
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                    f.setExecutable(true, false);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void zipDirectory(File sourceDir, File outputZip, ExportCallback callback,
                                    int startProgress, int endProgress) throws Exception {
        // 先统计总文件数，用于计算子进度
        int totalFiles = countFilesRecursive(sourceDir);
        if (totalFiles <= 0) totalFiles = 1;
        final int[] zippedFiles = {0};
        try (FileOutputStream fos = new FileOutputStream(outputZip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipDirectoryHelper(sourceDir, sourceDir, zos, callback, startProgress, endProgress,
                    totalFiles, zippedFiles);
        }
    }

    private static int countFilesRecursive(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        // BUG2修复：ZIP时跳过符号链接，避免dosdevices/z: -> / 打包整个根目录
        if (Files.isSymbolicLink(dir.toPath())) return 0;
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) continue; // 跳过符号链接
            if (f.isDirectory()) {
                count += countFilesRecursive(f);
            } else {
                count++;
            }
        }
        return count;
    }

    private static void zipDirectoryHelper(File rootDir, File currentDir, ZipOutputStream zos,
                                           ExportCallback callback, int startProgress, int endProgress,
                                           int totalFiles, int[] zippedFiles) throws Exception {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            // BUG2修复：跳过符号链接（dosdevices/c:、z:等），不在ZIP中跟随
            if (Files.isSymbolicLink(file.toPath())) continue;
            String entryName = rootDir.toURI().relativize(file.toURI()).getPath();
            if (file.isDirectory()) {
                zipDirectoryHelper(rootDir, file, zos, callback, startProgress, endProgress,
                        totalFiles, zippedFiles);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                }
                // BUG3：ZIP压缩子进度回调
                zippedFiles[0]++;
                int progress = startProgress + (int) ((zippedFiles[0] / (float) totalFiles) * (endProgress - startProgress));
                callback.onProgress(Math.min(endProgress, progress), "压缩文件: " + file.getName());
            }
        }
    }

    private static void unzipFile(File zipFile, File destDir) throws Exception {
        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                String destDirPath = destDir.getCanonicalPath();
                String newFilePath = newFile.getCanonicalPath();
                if (!newFilePath.startsWith(destDirPath + File.separator)) {
                    throw new Exception("非法的 zip 条目路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
