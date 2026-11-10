#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Generate release notes from a build report and its release verification."""
import argparse
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
STORE = ROOT / "agent_os/android/src/com/inceptless/traceweave/AgentStore.java"


def schema_version():
    match = re.search(r"DATABASE_VERSION\s*=\s*(\d+)", STORE.read_text())
    return int(match.group(1)) if match else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    parser.add_argument("--verification", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--changes", default="", help="Optional bullet list file")
    args = parser.parse_args()

    report = json.loads(pathlib.Path(args.report).read_text())
    verification = json.loads(pathlib.Path(args.verification).read_text())
    version_name = verification.get("version") or report.get("version", "unknown")
    version_code = verification.get("version_code") or report.get("version_code", "unknown")
    package = verification.get("package") or report.get("package", "com.inceptless.traceweave")
    signer = verification.get("signer_sha256") or "unknown"
    sync_included = bool(verification.get("sync_runtime_included", False))
    model_included = bool(verification.get("model_runtime_included", False))
    tests_excluded = bool(verification.get("test_components_excluded", False))
    changes = ""
    if args.changes:
        changes = pathlib.Path(args.changes).read_text().strip()

    notes = [
        f"# Traceweave Agent OS {version_name} (versionCode {version_code})",
        "",
        f"- 包名：{package}",
        f"- 签名指纹：{signer}",
        f"- 数据格式：schema {schema_version()}（升级后不可回退到旧版本）",
        f"- 同步运行库：{'已包含' if sync_included else '未包含'}",
        f"- 模型运行库：{'已包含' if model_included else '未包含'}",
        f"- 测试组件已剔除：{'是' if tests_excluded else '否'}",
        "",
        "## 变更",
        "",
        changes or "- （未填写）",
        "",
        "## 已知限制",
        "",
        "- 跨两台真机的同步尚未验证；同机双包已完成端到端验证。",
        "- 端侧模型输出受运行时 token 上限约束。",
        "",
        "## 校验",
        "",
        f"- 构建报告：{pathlib.Path(args.report).name}",
        f"- 发布校验：{pathlib.Path(args.verification).name}",
        "",
        "---",
        "",
        "> **开发密钥侧载 / Development-key side load**：本包由开发用签名密钥"
        f"（SHA-256 `{signer}`）签名，属于侧载分发，**不是**生产签名或应用商店签名。"
        "安装前请用同版本 `SHA256SUMS` 校验 APK。",
    ]
    pathlib.Path(args.output).write_text("\n".join(notes) + "\n")
    print(f"release notes written to {args.output}")


if __name__ == "__main__":
    main()
