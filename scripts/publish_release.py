# -*- coding: utf-8 -*-
"""手动发布 GitHub Release：建 tag、写正文、上传 APK 与摘要侧车。

这是 `release.yml` 的**离线等价物** —— 用在「不方便走 GitHub Actions」的场合
（没配签名 secret、网络到 Actions 不稳、或就想本地出包自己发）。

正文格式与 workflow 逐字一致，其中「- SHA-256：`<64 位>`」那一行被 App 的
`UpdateChecker.extractSha256` 依赖，是硬契约，改格式会让自动更新退化成手动下载。

前置：
    1. 根目录有 keystore.properties，且 `assembleRelease` 产出了已签名包；
    2. 已把 `app-release.apk` 改名为 `build/release-artifacts/Aurora-<tag>.apk`，
       并在旁边生成 `Aurora-<tag>.apk.sha256`；
    3. 环境变量 GH_TOKEN 是一个有 repo 权限的 token（不要写进任何文件）。

用法：
    GH_TOKEN=ghp_xxx python scripts/publish_release.py v1.1.0

脚本会先自查「侧车摘要 = 实际文件摘要」，不一致直接退出，不发半成品。
同一个 tag 已经发过时不会另建一条 Release，而是**删掉同名旧资产、刷新正文、重传** ——
重发（修打包错误、换掉有问题的包）是幂等的，不会在 tag 上堆两条记录。
PATCH Release 与上传资产都不会触发 `release.yml`，所以重发不会引出 CI 覆盖资产。
"""
import hashlib
import io
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

OWNER, REPO = "LonLsme", "AuroraApp"
ART_DIR = os.path.join("build", "release-artifacts")
API = "https://api.github.com"


def read_changelog_section(version):
    """抽 `## [version]` 到下一个 `## [` 之间的内容，等价于 workflow 里的 awk。

    段尾的 `---` 是 CHANGELOG 里版本之间的分隔线，拼进正文会与模板自己的
    分隔线叠成两条；段首的空行同样多余（正文标题下会凭空多出两行空白）。
    这里两头都剥掉。
    """
    text = io.open("CHANGELOG.md", encoding="utf-8").read()
    out, flag = [], False
    head = re.compile(r"^## \[" + re.escape(version) + r"\]")
    for line in text.splitlines():
        if head.match(line):
            flag = True
            continue
        if flag and line.startswith("## ["):
            break
        if flag:
            out.append(line)
    while out and not out[0].strip():
        out.pop(0)
    while out and out[-1].strip() in ("", "---"):
        out.pop()
    return "\n".join(out)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def api_headers(ctype=None):
    h = {
        "Authorization": "Bearer " + os.environ["GH_TOKEN"],
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "aurora-release-script",
    }
    if ctype:
        h["Content-Type"] = ctype
    return h


def call(method, url, data=None, ctype="application/json"):
    req = urllib.request.Request(url, data=data, headers=api_headers(ctype), method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read()
            # DELETE / 204 是空 body，不该被当成 JSON 解析失败
            return r.status, (json.loads(raw.decode("utf-8")) if raw else {})
    except urllib.error.HTTPError as e:
        print("HTTP %d %s\n%s" % (e.code, url, e.read().decode("utf-8", "replace")[:1200]))
        raise


def get_release(tag):
    """查 tag 对应的 Release；返回 None 表示这一版还没发过（404 不是错误）。"""
    url = "%s/repos/%s/%s/releases/tags/%s" % (API, OWNER, REPO, urllib.parse.quote(tag))
    req = urllib.request.Request(url, headers=api_headers(), method="GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        print("HTTP %d %s\n%s" % (e.code, url, e.read().decode("utf-8", "replace")[:1200]))
        raise


def main():
    if len(sys.argv) < 2:
        print("用法: GH_TOKEN=xxx python scripts/publish_release.py v1.1.0")
        return 2
    tag = sys.argv[1]
    version = tag.lstrip("v")

    apk = os.path.join(ART_DIR, "Aurora-%s.apk" % tag)
    sum_file = apk + ".sha256"
    for p in (apk, sum_file):
        if not os.path.exists(p):
            print("缺少产物:", p)
            return 2

    digest = sha256_of(apk)
    sidecar = io.open(sum_file, encoding="utf-8").read().split()[0]
    if digest != sidecar:
        print("侧车摘要与实际文件不一致：\n  文件 %s\n  侧车 %s" % (digest, sidecar))
        return 1

    section = read_changelog_section(version)
    if not section:
        print("CHANGELOG.md 里没有 `## [%s]` 段落，先补条目再发版" % version)
        return 1

    body = (
        "## 安装包\n\n"
        "- `Aurora-%s.apk`（%.1f MB）\n"
        # 下面这一行是硬契约：App 内自动更新按它取摘要做校验
        "- SHA-256：`%s`\n\n"
        "## 更新内容\n\n"
        "%s\n\n"
        "---\n\n"
        "> App 内的「检查更新 → 立即更新」会读取上面的 SHA-256 校验安装包。\n"
        "> 请连同 `Aurora-%s.apk.sha256` 一起下载，不要只拿 APK。\n"
    ) % (tag, os.path.getsize(apk) / 1048576.0, digest, section, tag)

    io.open(os.path.join(ART_DIR, "release-notes.md"), "w",
            encoding="utf-8", newline="\n").write(body)
    print("正文 %d 行，摘要 %s" % (len(body.splitlines()), digest))

    assets = (
        (apk, "Aurora-%s.apk" % tag, "application/vnd.android.package-archive"),
        (sum_file, "Aurora-%s.apk.sha256" % tag, "text/plain"),
    )
    asset_names = {name for _, name, _ in assets}

    existing = get_release(tag)
    if existing:
        # 重发同一版本（修打包错误、换掉有问题的资产）必须是幂等的：
        # 再 POST 一个会得到第二份同名 Release，tag 页面上就出现两条记录。
        rel_id = existing["id"]
        print("Release 已存在（id=%d），走更新：替换资产 + 刷新正文" % rel_id)
        for asset in existing.get("assets", []):
            if asset["name"] in asset_names:
                call("DELETE", "%s/repos/%s/%s/releases/assets/%d"
                     % (API, OWNER, REPO, asset["id"]))
                print("  删除旧资产 %-28s %d 字节" % (asset["name"], asset["size"]))
        _, rel = call("PATCH", "%s/repos/%s/%s/releases/%d" % (API, OWNER, REPO, rel_id),
                      json.dumps({"name": "Aurora %s" % tag, "body": body,
                                  "draft": False, "prerelease": False}).encode("utf-8"))
        print("正文已刷新:", rel["html_url"])
    else:
        payload = json.dumps({
            "tag_name": tag,
            "target_commitish": "main",
            "name": "Aurora %s" % tag,
            "body": body,
            "draft": False,
            "prerelease": False,
        }).encode("utf-8")
        status, rel = call("POST", "%s/repos/%s/%s/releases" % (API, OWNER, REPO), payload)
        print("Release 创建:", status, rel["html_url"], "id=", rel["id"])

    for path, name, ctype in assets:
        data = open(path, "rb").read()
        url = "https://uploads.github.com/repos/%s/%s/releases/%d/assets?name=%s" % (
            OWNER, REPO, rel["id"], urllib.parse.quote(name))
        st, asset = call("POST", url, data, ctype)
        print("  上传 %-28s %d  %.1f MB" % (name, st, asset["size"] / 1048576.0))
    print("完成。确认是 published（非 draft / prerelease），否则 App 查不到这一版。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
