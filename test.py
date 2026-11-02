#!/usr/bin/env python3
"""编译并运行状态机内核自检（不需要 Minecraft 环境，core 包是纯逻辑）。"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

# 中文 Windows 的控制台默认 cp936，javac 的错误里一旦有非 GBK 字符就整段打印失败，
# 把真错误吞成 UnicodeEncodeError。输出统一走 UTF-8 并替换坏字节。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
JAVAC = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\javac.exe"
JAVA = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\java.exe"

OUT = os.path.join(ROOT, "build", "test-classes")
MAIN = os.path.join(ROOT, "src", "main", "java", "com", "bfxh", "trit")
# 只编不依赖 Minecraft 的包：core 是纯逻辑，neo 是纯数值，behave 只依赖 core+neo。
# sense / act / TritMod 要碰游戏类，不在自检范围里。
PURE = [os.path.join(MAIN, "core"), os.path.join(MAIN, "behave"), os.path.join(MAIN, "neo")]
TEST = os.path.join(ROOT, "src", "test", "java")
RUNNERS = ("com.bfxh.trit.SelfTest", "com.bfxh.trit.SimTest", "com.bfxh.trit.NeoTest")


def decode(raw: bytes) -> str:
    """javac / java 在中文 Windows 上按 GBK 输出错误，硬按 UTF-8 解会得到乱码，
    等于把真错误变成看不懂的噪音。按 UTF-8 → GBK 依次试，都不行再替换。"""
    if not raw:
        return ""
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def main() -> int:
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    sources = []
    for base in PURE + [TEST]:
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    sources.append(os.path.join(dirpath, f))
    print(f"编译 {len(sources)} 个文件（core + behave + neo + test）")

    argfile = os.path.join(ROOT, "build", "test.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("-encoding\nUTF-8\n-nowarn\n-d\n" + OUT + "\n")
        for s in sources:
            f.write(s + "\n")

    r = subprocess.run([JAVAC, "@" + argfile], capture_output=True)
    if r.returncode != 0:
        print(decode(r.stdout)[:6000])
        print(decode(r.stderr)[:3000])
        return 1

    rc = 0
    for cls in RUNNERS:
        print("--- " + cls + " ---")
        run = subprocess.run([JAVA, "-cp", OUT, cls], capture_output=True)
        print(decode(run.stdout))
        if run.stderr.strip():
            print(decode(run.stderr)[:3000])
        if run.returncode != 0:
            rc = run.returncode
    return rc


if __name__ == "__main__":
    sys.exit(main())
