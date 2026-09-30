#!/usr/bin/env python3
"""
自动同步上游组件到Winlator仓库
支持扩展：在COMPONENTS配置中添加新的组件源
"""
import os
import re
import json
import io
import sys
import tarfile
import zstandard
import requests

GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
HEADERS = {"Authorization": f"token {GITHUB_TOKEN}"} if GITHUB_TOKEN else {}

# ========== 组件配置（后续添加新组件在这里加） ==========
COMPONENTS = [
    {
        "name": "panDXVK",
        "repo": "isygold/panDXVK",
        "type": "dxvk",
        "asset_pattern": "pandxvk-",       # 匹配的asset文件名前缀
        "asset_ext": ".wcp",                 # asset扩展名
        "target_dir": "app/src/main/assets/dxwrapper/",
        "file_prefix": "dxvk-",              # 输出文件前缀
        "file_suffix": "",                    # 版本号后缀（version_transform已包含）
        "array_file": "app/src/main/res/values/arrays.xml",
        "array_name": "dxvk_version_entries",
        "strip_profile": True,               # 是否去掉profile.json
        "version_transform": lambda tag: tag.lstrip("v").replace("panVK", "pandxvk"),
    },
    # 后续添加其他组件示例：
    # {
    #     "name": "xxx驱动",
    #     "repo": "xxx/xxx",
    #     "type": "driver",
    #     ...
    # },
]

def get_latest_release(repo):
    """获取最新release（优先正式版，没有则取预发布）"""
    url = f"https://api.github.com/repos/{repo}/releases?per_page=5"
    resp = requests.get(url, headers=HEADERS, timeout=30)
    resp.raise_for_status()
    releases = resp.json()
    if not releases:
        return None
    # 优先取第一个（API默认按发布时间排序）
    return releases[0]

def download_asset(release, pattern, ext):
    """从release中下载匹配的asset"""
    for asset in release.get("assets", []):
        name = asset["name"]
        if name.startswith(pattern) and name.endswith(ext):
            url = asset["browser_download_url"]
            print(f"  下载: {name}")
            resp = requests.get(url, headers=HEADERS, timeout=120)
            resp.raise_for_status()
            return name, resp.content
    return None, None

def repack_tar_zst(data, strip_profile=False, add_dot_prefix=True):
    """将tar.zst数据重新打包，可选去掉profile.json，加./前缀"""
    dctx = zstandard.ZstdDecompressor()
    
    # 读取原tar（流式解压，避免frame header问题）
    with dctx.stream_reader(io.BytesIO(data)) as reader:
        with tarfile.open(fileobj=reader, mode="r|") as tar:
            members = []
            file_data = {}
            for member in tar:
                if strip_profile and member.name.endswith("profile.json"):
                    print(f"  跳过: {member.name}")
                    continue
                # 调整路径：加./前缀
                new_name = member.name
                if add_dot_prefix and not new_name.startswith("./"):
                    new_name = "./" + new_name.lstrip("/")
                member.name = new_name
                members.append(member)
                if member.isfile():
                    f = tar.extractfile(member)
                    if f:
                        file_data[new_name] = f.read()
    
    # 重新打包为tar.zst
    output = io.BytesIO()
    cctx = zstandard.ZstdCompressor(level=19)
    with cctx.stream_writer(output) as compressor:
        with tarfile.open(fileobj=compressor, mode="w") as tar_out:
            for member in members:
                if member.isfile() and member.name in file_data:
                    tar_out.addfile(member, io.BytesIO(file_data[member.name]))
                else:
                    tar_out.addfile(member)
        result = output.getvalue()
    
    return result

def get_existing_versions(array_file, array_name):
    """从arrays.xml中读取已有的版本列表"""
    if not os.path.exists(array_file):
        return []
    with open(array_file, "r", encoding="utf-8") as f:
        content = f.read()
    # 提取指定array的items
    pattern = rf'<string-array name="{array_name}">(.*?)</string-array>'
    match = re.search(pattern, content, re.DOTALL)
    if not match:
        return []
    items = re.findall(r'<item>(.*?)</item>', match.group(1))
    return items

def add_version_to_array(array_file, array_name, new_version):
    """向arrays.xml添加新版本"""
    with open(array_file, "r", encoding="utf-8") as f:
        content = f.read()
    
    pattern = rf'(<string-array name="{array_name}">)(.*?)(</string-array>)'
    match = re.search(pattern, content, re.DOTALL)
    if not match:
        print(f"  错误: 找不到 {array_name}")
        return False
    
    # 检查是否已存在
    if f"<item>{new_version}</item>" in match.group(2):
        print(f"  版本已存在: {new_version}")
        return False
    
    # 在第一个item前插入新版本（最新版在前）
    items_block = match.group(2)
    first_item = items_block.find("<item>")
    if first_item >= 0:
        new_items = items_block[:first_item] + f"<item>{new_version}</item>\n        " + items_block[first_item:]
    else:
        new_items = f"\n        <item>{new_version}</item>\n    "
    
    new_content = content[:match.start()] + match.group(1) + new_items + match.group(3) + content[match.end():]
    
    with open(array_file, "w", encoding="utf-8") as f:
        f.write(new_content)
    print(f"  已添加版本: {new_version}")
    return True

def sync_component(comp):
    """同步单个组件"""
    print(f"\n{'='*50}")
    print(f"同步组件: {comp['name']} ({comp['repo']})")
    print(f"{'='*50}")
    
    # 1. 获取最新release
    release = get_latest_release(comp["repo"])
    if not release:
        print("  无release")
        return False
    
    tag = release.get("tag_name", "")
    print(f"  最新tag: {tag}")
    print(f"  发布时间: {release.get('published_at', '')}")
    
    # 2. 转换版本号
    version = comp["version_transform"](tag)
    filename = f"{comp['file_prefix']}{version}{comp['file_suffix']}.tzst" if comp.get("file_suffix") else f"{comp['file_prefix']}{version}.tzst"
    filepath = os.path.join(comp["target_dir"], filename)
    
    # 3. 检查是否已存在
    if os.path.exists(filepath):
        print(f"  文件已存在，跳过: {filepath}")
        return False
    
    # 4. 下载asset
    asset_name, data = download_asset(release, comp["asset_pattern"], comp["asset_ext"])
    if not data:
        print("  未找到匹配的asset")
        return False
    
    # 5. 重新打包
    print(f"  重新打包: {asset_name} -> {filename}")
    repacked = repack_tar_zst(data, strip_profile=comp.get("strip_profile", False))
    
    # 6. 保存文件
    os.makedirs(comp["target_dir"], exist_ok=True)
    with open(filepath, "wb") as f:
        f.write(repacked)
    print(f"  已保存: {filepath} ({len(repacked)//1024}KB)")
    
    # 7. 更新arrays.xml
    array_version = version + comp.get("file_suffix", "")
    updated = add_version_to_array(comp["array_file"], comp["array_name"], array_version)
    
    return updated or os.path.exists(filepath)

def main():
    print("开始同步上游组件...")
    any_updated = False
    
    for comp in COMPONENTS:
        try:
            updated = sync_component(comp)
            if updated:
                any_updated = True
        except Exception as e:
            print(f"  错误: {e}")
            import traceback
            traceback.print_exc()
    
    # 输出结果供workflow判断
    if any_updated:
        print("\n::set-output name=has_updates::true")
        print("有组件更新，需要提交")
    else:
        print("\n::set-output name=has_updates::false")
        print("所有组件已是最新")

if __name__ == "__main__":
    main()
