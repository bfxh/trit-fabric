#!/usr/bin/env python3
"""构建 trit Fabric mod 并装进目标整合包。

不用 Gradle：整个工程只依赖 MC 本体 + Fabric API，javac 直接编就够，
省掉 Gradle + Loom 几十分钟的下载和映射处理。

用法：
    python build.py            # 编译 + 打包 + 装进整合包
    python build.py --no-copy  # 只编译打包，不复制
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))

MC_JAR = r"E:\MC\MC\meta\versions\26.3-0.20.1\26.3-0.20.1.jar"
MC_LIBS = r"E:\MC\MC\meta\libraries"
JAVAC = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\javac.exe"
JAR_TOOL = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\jar.exe"

MODS_DIR = r"E:\MC\MC\profiles\26.3-Fabric 0.20.1 (1)\mods"
API_JAR = os.path.join(ROOT, "libs", "fabric-api-0.165.0+26.3.jar")
API_CLASSES = os.path.join(ROOT, "libs", "api-classes")

BUILD = os.path.join(ROOT, "build")
CLASSES = os.path.join(BUILD, "classes")
OUT = os.path.join(BUILD, "out")
VERSION = "0.1.0"


def classpath() -> list[str]:
    """MC 本体 + 它的 116 个依赖 + Fabric API 的解包类。"""
    paths = [MC_JAR]
    ver_json = os.path.join(os.path.dirname(MC_JAR), "26.3-0.20.1.json")
    import json
    with open(ver_json, encoding="utf-8") as f:
        data = json.load(f)
    for lib in data.get("libraries", []):
        name = lib.get("name", "")
        parts = name.split(":")
        if len(parts) < 3:
            continue
        g, a, v = parts[0], parts[1], parts[2]
        rel = "/".join(g.split(".")) + f"/{a}/{v}/{a}-{v}.jar"
        p = os.path.join(MC_LIBS, rel)
        if os.path.isfile(p):
            paths.append(p)
    if os.path.isdir(API_CLASSES):
        paths.append(API_CLASSES)
    if os.path.isfile(API_JAR):
        paths.append(API_JAR)
    return paths


def compile_java(cp: list[str]) -> bool:
    src = os.path.join(ROOT, "src", "main", "java")
    sources = []
    for dirpath, _, files in os.walk(src):
        for f in files:
            if f.endswith(".java"):
                sources.append(os.path.join(dirpath, f))
    if not sources:
        print("没有找到 java 源文件")
        return False

    if os.path.isdir(CLASSES):
        shutil.rmtree(CLASSES)
    os.makedirs(CLASSES, exist_ok=True)

    # 参数写进文件，避免命令行长度和转义问题
    argfile = os.path.join(BUILD, "javac.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("-encoding\nUTF-8\n")
        f.write("-nowarn\n")
        f.write("-g\n")
        f.write("-d\n" + CLASSES + "\n")
        f.write("-cp\n" + os.pathsep.join(cp) + "\n")
        for s in sources:
            f.write(s + "\n")

    r = subprocess.run([JAVAC, "@" + argfile], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print("=== 编译失败 ===")
        print(r.stdout[:8000])
        print(r.stderr[:4000])
        return False
    if r.stdout.strip():
        print(r.stdout[:2000])
    print(f"编译通过：{len(sources)} 个源文件")
    return True


def package() -> str:
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)
    jar_path = os.path.join(OUT, f"trit-{VERSION}.jar")

    res = os.path.join(ROOT, "src", "main", "resources")
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as z:
        # 资源
        for dirpath, _, files in os.walk(res):
            for f in files:
                full = os.path.join(dirpath, f)
                arc = os.path.relpath(full, res).replace("\\", "/")
                z.write(full, arc)
        # class
        for dirpath, _, files in os.walk(CLASSES):
            for f in files:
                if not f.endswith(".class"):
                    continue
                full = os.path.join(dirpath, f)
                arc = os.path.relpath(full, CLASSES).replace("\\", "/")
                z.write(full, arc)
    print(f"打包完成：{jar_path}")
    return jar_path


def install(jar_path: str, fabric_api: str) -> None:
    if not os.path.isdir(MODS_DIR):
        print(f"目标 mods 目录不存在：{MODS_DIR}")
        return
    # 清掉旧版本，避免两个 trit jar 同时被加载。
    # 注意：直接 overwrite 即可（文件名固定为 trit-0.1.0.jar），不用 os.remove --
    # 本机回收站删除会被拦截导致整条安装失败。旧 jar 移到 _old 备份而不是删。
    old_dir = os.path.join(MODS_DIR, "_old")
    for f in os.listdir(MODS_DIR):
        if f.startswith("trit-") and f.endswith(".jar"):
            try:
                os.makedirs(old_dir, exist_ok=True)
                shutil.move(os.path.join(MODS_DIR, f), os.path.join(old_dir, f))
                print(f"备份旧版 {f} → _old/")
            except OSError as e:
                print(f"无法移走旧版 {f}（忽略）：{e}")

    dst = os.path.join(MODS_DIR, os.path.basename(jar_path))
    shutil.copy2(jar_path, dst)
    print(f"已安装：{dst}")

    # Fabric API 是硬依赖，顺手放进去
    if os.path.isfile(fabric_api):
        api_dst = os.path.join(MODS_DIR, os.path.basename(fabric_api))
        if not os.path.exists(api_dst):
            shutil.copy2(fabric_api, api_dst)
            print(f"已安装依赖：{api_dst}")
        else:
            print(f"依赖已存在：{api_dst}")


def ensure_api() -> bool:
    """没有 Fabric API 就按 26.3 的版本自动下载，并解出编译用的 class。"""
    if os.path.isfile(API_JAR):
        return True

    print("未找到 Fabric API，正在查询 26.3 的可用版本…")
    import json as _json
    import urllib.request

    url = "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%2226.3%22%5D&limit=1"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            data = _json.load(r)
    except Exception as e:
        print(f"查询失败：{e}")
        return False
    if not data:
        print("Modrinth 上没有 26.3 的 Fabric API")
        return False

    files = [f for f in data[0].get("files", []) if f["filename"].endswith(".jar")]
    if not files:
        return False
    dl = files[0]["url"]
    print(f"下载 {files[0]['filename']}")

    os.makedirs(os.path.dirname(API_JAR), exist_ok=True)
    try:
        with urllib.request.urlopen(dl, timeout=180) as r, open(API_JAR, "wb") as out:
            shutil.copyfileobj(r, out)
    except Exception as e:
        print(f"下载失败：{e}")
        return False
    return True


def unpack_api() -> None:
    """Fabric API 主 jar 里的模块是嵌套 jar，javac 读不到，解出来放 api-classes。"""
    if not os.path.isfile(API_JAR):
        return
    if os.path.isdir(API_CLASSES) and os.listdir(API_CLASSES):
        return
    import io
    os.makedirs(API_CLASSES, exist_ok=True)
    n = 0
    with zipfile.ZipFile(API_JAR) as z:
        for name in z.namelist():
            if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(z.read(name))) as sub:
                    for e in sub.namelist():
                        if not e.endswith(".class"):
                            continue
                        target = os.path.join(API_CLASSES, e)
                        os.makedirs(os.path.dirname(target), exist_ok=True)
                        if not os.path.exists(target):
                            with open(target, "wb") as out:
                                out.write(sub.read(e))
                            n += 1
    print(f"解出 {n} 个 Fabric API class")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-copy", action="store_true", help="只编译打包，不复制到整合包")
    args = ap.parse_args()

    for p in (JAVAC, MC_JAR):
        if not os.path.isfile(p):
            print(f"缺少必要文件：{p}")
            return 1
    if not ensure_api():
        return 1
    unpack_api()

    cp = classpath()
    if not compile_java(cp):
        return 1
    jar_path = package()
    if not args.no_copy:
        install(jar_path, API_JAR)
    return 0


if __name__ == "__main__":
    sys.exit(main())
