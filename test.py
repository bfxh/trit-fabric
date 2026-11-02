#!/usr/bin/env python3
"""编译并运行状态机内核自检（不需要 Minecraft 环境，core 包是纯逻辑）。"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
JAVAC = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\javac.exe"
JAVA = r"E:\MC\MC\meta\java_versions\mojang-java-runtime-epsilon-windows-x64-b374544c680d\bin\java.exe"

OUT = os.path.join(ROOT, "build", "test-classes")
CORE = os.path.join(ROOT, "src", "main", "java", "com", "bfxh", "trit", "core")
TEST = os.path.join(ROOT, "src", "test", "java")


def main() -> int:
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    sources = []
    for base in (CORE, TEST):
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    sources.append(os.path.join(dirpath, f))
    # core 之外的 main 源码依赖 MC，测试不编它们
    print(f"编译 {len(sources)} 个文件（core + test）")

    argfile = os.path.join(ROOT, "build", "test.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("-encoding\nUTF-8\n-nowarn\n-d\n" + OUT + "\n")
        for s in sources:
            f.write(s + "\n")

    r = subprocess.run([JAVAC, "@" + argfile], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(r.stdout[:6000])
        print(r.stderr[:3000])
        return 1

    run = subprocess.run([JAVA, "-cp", OUT, "com.bfxh.trit.SelfTest"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    print(run.stdout)
    if run.stderr.strip():
        print(run.stderr[:3000])
    return run.returncode


if __name__ == "__main__":
    sys.exit(main())
