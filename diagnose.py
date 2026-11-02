#!/usr/bin/env python3
"""启动后的排查脚本：mod 到底加载了没有，没加载是为什么。

用法：
    python diagnose.py
"""
from __future__ import annotations

import os
import re
import zipfile

INSTANCE = r"E:\MC\MC\profiles\26.3-Fabric 0.20.1 (1)"
MODS = os.path.join(INSTANCE, "mods")
LOG_CANDIDATES = [
    os.path.join(INSTANCE, "logs", "latest.log"),
    os.path.join(INSTANCE, "logs", "debug.log"),
    os.path.join(INSTANCE, "latest.log"),
    r"E:\MC\MC\logs\latest.log",
]

NEEDLE = re.compile(
    r"trit|fabric|mixin|mod resolution|ModResolve|error|exception|crash",
    re.IGNORECASE)


def check_mods() -> None:
    print("=== mods 目录 ===")
    if not os.path.isdir(MODS):
        print(f"不存在：{MODS}")
        return
    found = sorted(f for f in os.listdir(MODS) if f.endswith(".jar"))
    if not found:
        print("（空）")
        return
    for f in found:
        p = os.path.join(MODS, f)
        size = os.path.getsize(p)
        tag = ""
        try:
            with zipfile.ZipFile(p) as z:
                names = z.namelist()
                has_json = "fabric.mod.json" in names
                ncls = sum(1 for n in names if n.endswith(".class"))
                nested = sum(1 for n in names if n.startswith("META-INF/jars/"))
                tag = f"fabric.mod.json={'有' if has_json else '无'} class={ncls} 嵌套jar={nested}"
        except Exception as e:
            tag = f"无法读取：{e}"
        print(f"  {f}  ({size/1024:.0f} KB)  {tag}")


def check_log() -> None:
    print()
    print("=== 日志 ===")
    logs = [p for p in LOG_CANDIDATES if os.path.isfile(p)]
    if not logs:
        print("没找到日志文件。游戏至少启动过一次才会有。")
        print("找过这些位置：")
        for p in LOG_CANDIDATES:
            print("  " + p)
        return

    for p in logs:
        print(f"--- {p} ---")
        try:
            with open(p, encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        except Exception as e:
            print(f"  读取失败：{e}")
            continue

        hits = [ln.rstrip("\n") for ln in lines if NEEDLE.search(ln)]
        # 只看最后 40 条，前面的通常都是无关的启动噪音
        for ln in hits[-40:]:
            print("  " + ln[:200])
        print(f"  （共 {len(lines)} 行，命中 {len(hits)} 行）")


def main() -> None:
    print(f"整合包：{INSTANCE}")
    print()
    check_mods()
    check_log()
    print()
    print("判读：")
    print("  · 日志里有 '[TRIT] 已加载' → mod 正常装载，进世界按 F8 即可")
    print("  · 出现 'ModResolutionException' → Fabric API 版本和 MC 26.3 不匹配")
    print("  · 出现 'NoClassDefFound' / 'NoSuchMethod' → 编译时用的 MC 版本和运行时不一致")
    print("  · 完全没有 trit 字样 → jar 没被扫描到，确认它在 mods/ 根目录且后缀是 .jar")


if __name__ == "__main__":
    main()
