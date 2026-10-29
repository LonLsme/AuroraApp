#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Aurora 发版准备：把「改版本号 → 写 CHANGELOG → 提交 → 打 tag」压成一条命令。

为什么需要它
------------
`release.yml` 靠 tag 触发。而发版在本项目是三步手工操作：改 `build.gradle.kts`
的 versionCode/versionName、把 CHANGELOG 的 [Unreleased] 改成正式段落、打 tag。
任何一步漏掉都会断链 —— v1.1.1 / v1.1.2 / v1.1.5 就是这么发出去的：
CHANGELOG 里写着、线上也发了，git 里却没有 tag，事后既没法从 CI 重建那个版本，
也查不到「1.1.5 对应哪一份代码」。本脚本把这四步做成一个不可分割的动作，
并在动手之前先体检。

和 `publish_release.py` 的分工
------------------------------
    release.py         准备发版：改文件、提交、打 tag（本地动作，不联网）
    publish_release.py 发布到 GitHub：建 Release、传 APK 与摘要（联网）

用法
----
    python scripts/release.py --check           # 体检，只报告不改任何东西
    python scripts/release.py 1.2.0             # 准备：改版本 + CHANGELOG + commit + tag
    python scripts/release.py 1.2.0 --push      # 准备完直接推送，交给 CI 出包
    python scripts/release.py 1.2.0 --local     # 准备完本地出签名包，供 publish_release.py 用

版本号怎么定：新增功能抬中位（1.1.5 → 1.2.0），纯修 bug 抬末位（→ 1.1.6）。
versionCode 由脚本自动 +1，不需要手填。
"""
import argparse
import datetime
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"
ART_DIR = ROOT / "build" / "release-artifacts"

# CHANGELOG 里 [Unreleased] 段的占位说明，发版时要整行丢掉
PLACEHOLDER = "下一个版本要做的改动放这里"
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


# ───────────────────────────── 输出 ─────────────────────────────

def head(title):
    print()
    print(title)
    print("─" * len(title))


def ok(msg):
    print("  ✓ " + msg)


def warn(msg):
    print("  ! " + msg)


def bad(msg):
    print("  ✗ " + msg)


def die(msg):
    print()
    bad(msg)
    sys.exit(1)


# ───────────────────────────── git ─────────────────────────────

def git(*args, check=True):
    """跑一条 git 命令。失败时把 stderr 原样抛出来，不吞错误。"""
    proc = subprocess.run(
        ["git"] + list(args),
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        die("git %s 失败：\n%s" % (" ".join(args), (proc.stderr or proc.stdout or "").strip()))
    return proc.stdout.strip() if proc.returncode == 0 else ""


def current_branch():
    return git("rev-parse", "--abbrev-ref", "HEAD")


def is_clean():
    return git("status", "--porcelain") == ""


def tag_exists(tag):
    return git("tag", "-l", tag) != ""


def all_tags():
    out = git("tag", "-l")
    return set(out.splitlines()) if out else set()


def behind_ahead(upstream="origin/main"):
    """返回 (落后, 领先)。upstream 不存在时返回 None。"""
    if not git("rev-parse", "--verify", upstream, check=False):
        return None
    out = git("rev-list", "--left-right", "--count", "%s...HEAD" % upstream)
    left, right = out.split()
    return int(left), int(right)


# ──────────────────────── 版本号与 CHANGELOG ────────────────────────

def read_gradle_version():
    text = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not code or not name:
        die("在 app/build.gradle.kts 里找不到 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def write_gradle_version(new_code, new_name):
    text = GRADLE.read_text(encoding="utf-8")
    text = re.sub(r"(versionCode\s*=\s*)\d+", r"\g<1>%d" % new_code, text, count=1)
    text = re.sub(r'(versionName\s*=\s*")[^"]*(")', r"\g<1>%s\g<2>" % new_name, text, count=1)
    GRADLE.write_text(text, encoding="utf-8", newline="")


def parse_changelog_versions():
    """按出现顺序列出 CHANGELOG 里所有正式版本号（不含 Unreleased）。"""
    text = CHANGELOG.read_text(encoding="utf-8")
    return re.findall(r"^##[ \t]*\[(\d+\.\d+\.\d+)\]", text, re.M)


def read_unreleased_body():
    """抽出 [Unreleased] 段的实际内容，剥掉占位说明与分隔线。"""
    text = CHANGELOG.read_text(encoding="utf-8")
    # 用 [ \t]* 而不是 \s*：\s 会吃掉后面的换行，导致切出来的段落开头多出空行
    m = re.search(r"^##[ \t]*\[Unreleased\][ \t]*$", text, re.M)
    if not m:
        die("CHANGELOG.md 里没有 `## [Unreleased]` 段落")
    start = m.end()
    nxt = re.search(r"^##[ \t]*\[", text[start:], re.M)
    end = start + (nxt.start() if nxt else len(text) - start)

    kept = []
    for line in text[start:end].splitlines():
        stripped = line.strip()
        if stripped == "---" or stripped.startswith(PLACEHOLDER):
            continue
        kept.append(line)
    return "\n".join(kept).strip()


def promote_unreleased(version):
    """把 [Unreleased] 扶正成 [version]，并留一个空的新 [Unreleased]。"""
    body = read_unreleased_body()
    if not body:
        die("CHANGELOG 的 [Unreleased] 里没有内容。\n"
            "     发版前先写这一版改了什么 —— 它会原样变成 Release 的发布说明，\n"
            "     也是 App 内「检查更新」弹窗里给用户看的那段字。")

    text = CHANGELOG.read_text(encoding="utf-8")
    m = re.search(r"^##[ \t]*\[Unreleased\][ \t]*$", text, re.M)
    start = m.end()
    nxt = re.search(r"^##[ \t]*\[", text[start:], re.M)
    end = start + (nxt.start() if nxt else len(text) - start)

    today = datetime.date.today().isoformat()
    block = (
        "\n\n---\n\n"
        "## [%s] - %s\n\n"
        "%s\n\n"
        "---\n\n"
    ) % (version, today, body)

    CHANGELOG.write_text(text[:start] + block + text[end:], encoding="utf-8", newline="")
    return body


# ───────────────────────────── 体检 ─────────────────────────────

def do_check():
    head("Aurora 发版体检")

    branch = current_branch()
    if branch == "main":
        ok("当前分支 main")
    else:
        warn("当前在 %s，不是 main" % branch)

    if is_clean():
        ok("工作区干净")
    else:
        warn("工作区有未提交改动：")
        for line in git("status", "--short").splitlines():
            print("      " + line)

    rel = behind_ahead()
    if rel is None:
        warn("没有 origin/main（没配远程？）")
    else:
        behind, ahead = rel
        if behind == 0 and ahead == 0:
            ok("与 origin/main 同步")
        elif ahead > 0 and behind == 0:
            warn("本地领先 origin/main %d 个提交（未推送）" % ahead)
        elif behind > 0 and ahead == 0:
            warn("本地落后 origin/main %d 个提交，建议先 pull" % behind)
        else:
            warn("与 origin/main 已分叉：落后 %d、领先 %d" % (behind, ahead))

    code, name = read_gradle_version()
    ok("当前版本 %s（versionCode %d）" % (name, code))

    body = read_unreleased_body()
    if body:
        ok("CHANGELOG [Unreleased] 有内容（%d 行）" % len(body.splitlines()))
    else:
        warn("CHANGELOG [Unreleased] 是空的 —— 发版前必须先写")

    head("标签覆盖")
    versions = parse_changelog_versions()
    tags = all_tags()
    missing = []
    for v in versions:
        if "v" + v in tags:
            ok("v%-8s 有 tag" % v)
        else:
            missing.append(v)
    # 反查：有 tag 但 CHANGELOG 里没有对应段落
    for t in sorted(tags):
        if not t.startswith("v"):
            continue
        bare = t[1:]
        if VERSION_RE.match(bare) and bare not in versions:
            warn("%s 有 tag，但 CHANGELOG 里没有对应段落" % t)

    head("结论")
    if missing:
        print("  CHANGELOG 记了 %d 个版本，git 里只有 %d 个 tag。缺："
              % (len(versions), len(versions) - len(missing)))
        for v in missing:
            print("      v" + v)
        print()
        print("  补齐历史 tag（指向该版本发布时的提交，用 git log 确认）：")
        print("      git tag -a v%s <提交哈希> -m \"Aurora v%s\"" % (missing[0], missing[0]))
        print()
    if not missing and body:
        print("  可以发版。跑：python scripts/release.py <新版本号>")
    if not body:
        print("  下一步：先往 CHANGELOG.md 的 [Unreleased] 里写这一版的改动。")
    print()


# ───────────────────────────── 准备发版 ─────────────────────────────

def do_prepare(version, push, local):
    if not VERSION_RE.match(version):
        die("版本号格式应为 X.Y.Z（纯数字三段），收到：%s\n"
            "     预发布（如 1.2.0-beta.1）目前不走本脚本，手工打 tag 即可，\n"
            "     release.yml 会把带连字符的 tag 自动标为预发布。" % version)

    head("发版前检查")

    branch = current_branch()
    if branch != "main":
        die("当前在 %s，发版必须在 main 上" % branch)
    ok("在 main 上")

    if not is_clean():
        die("工作区有未提交改动，先提交或 stash：\n%s" % git("status", "--short"))

    ok("工作区干净")

    tag = "v" + version
    if tag_exists(tag):
        die("%s 已存在。同一个版本号不能发两次 —— 用户端的「检查更新」按版本号比较，\n"
            "     版本号不变就等于告诉所有人「已是最新」，重发的包永远送不出去。\n"
            "     要重发同一版本，用 scripts/publish_release.py（它会覆盖资产不新建 tag）。" % tag)
    ok("%s 未被占用" % tag)

    old_code, old_name = read_gradle_version()
    def vt(v):
        return tuple(int(x) for x in v.split("."))
    if vt(version) <= vt(old_name):
        die("新版本 %s 必须大于当前 %s" % (version, old_name))
    ok("版本递增 %s → %s" % (old_name, version))

    body = read_unreleased_body()
    if not body:
        die("CHANGELOG 的 [Unreleased] 里没有内容，先写这一版改了什么")
    ok("CHANGELOG 有内容（%d 行）" % len(body.splitlines()))

    new_code = old_code + 1

    head("写入")
    write_gradle_version(new_code, version)
    ok("app/build.gradle.kts → versionCode %d / versionName \"%s\"" % (new_code, version))

    promote_unreleased(version)
    ok("CHANGELOG → ## [%s] - %s" % (version, datetime.date.today().isoformat()))

    head("提交与打 tag")
    git("add", "app/build.gradle.kts", "CHANGELOG.md")
    git("commit", "-m", "chore(release): %s" % tag)
    ok("commit chore(release): %s" % tag)
    git("tag", "-a", tag, "-m", "Aurora %s" % tag)
    ok("tag %s" % tag)

    if local:
        do_local_build(tag)

    head("下一步")
    if push:
        print("  推送中…")
        git("push", "origin", "main")
        git("push", "origin", tag)
        ok("已推送 main 与 %s" % tag)
        print()
        print("  release.yml 会自动跑：构建 → 算摘要 → 建**草稿** Release。")
        print("  到 https://github.com/LonLsme/AuroraApp/releases 点 Publish release 完成发版。")
    elif local:
        print("  本地包已就绪。发布：")
        print("      GH_TOKEN=xxx python scripts/publish_release.py %s" % tag)
    else:
        print("  推送（触发 CI 出包）：")
        print("      git push origin main && git push origin %s" % tag)
        print()
        print("  或者本地出签名包：")
        print("      python scripts/release.py %s --local   # 已改过文件，改用下面的手工步骤" % version)
        print("      ./gradlew :app:assembleRelease")
    print()


def do_local_build(tag):
    head("本地构建 Release 包")
    env_note = ""
    if not (ROOT / "keystore.properties").exists():
        die("根目录没有 keystore.properties，本地出不了签名包。\n"
            "     签名密钥在 ~/.aurora-signing/aurora-release.jks，参见 docs/subsystems/UPDATE.md")

    gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    proc = subprocess.run(
        [str(gradlew), ":app:assembleRelease", "--offline"],
        cwd=str(ROOT),
    )
    if proc.returncode != 0:
        die("assembleRelease 失败，没有产物可以发布")

    src = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not src.exists():
        die("找不到 %s" % src)

    ART_DIR.mkdir(parents=True, exist_ok=True)
    dst = ART_DIR / ("Aurora-%s.apk" % tag)
    shutil.copy2(str(src), str(dst))
    ok("产物 → %s" % dst.relative_to(ROOT))

    digest = subprocess.run(
        [sys.executable, "-c",
         "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())",
         str(dst)],
        capture_output=True, text=True,
    ).stdout.strip()
    (ART_DIR / ("Aurora-%s.apk.sha256" % tag)).write_text(digest + "\n", encoding="utf-8")
    ok("摘要 → %s…" % digest[:16])


def main():
    parser = argparse.ArgumentParser(
        description="Aurora 发版准备工具（改版本号 + CHANGELOG + commit + tag）",
    )
    parser.add_argument("version", nargs="?", help="新版本号，如 1.2.0")
    parser.add_argument("--check", action="store_true", help="只体检，不改任何东西")
    parser.add_argument("--push", action="store_true", help="准备完直接推送，触发 CI")
    parser.add_argument("--local", action="store_true", help="准备完本地出签名包")
    args = parser.parse_args()

    if args.check:
        do_check()
        return
    if not args.version:
        parser.print_help()
        print()
        print("示例：")
        print("    python scripts/release.py --check")
        print("    python scripts/release.py 1.2.0")
        return
    if args.push and args.local:
        die("--push 与 --local 只能选一个：一个交给 CI 出包，一个本地出包")

    do_prepare(args.version, args.push, args.local)


if __name__ == "__main__":
    main()
