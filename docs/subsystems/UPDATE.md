# 应用内自动更新（UPDATE）

> 对应代码：`core/update/`（4 个类）+ `ui/update/`（2 个对话框）+ `.github/workflows/release.yml`
>
> 一句话：**检查更新 → 应用内下载 → SHA-256 + 签名双校验 → 调起系统安装器**，
> 全程不依赖应用商店，也不引入任何第三方更新 SDK。

---

## 为什么这么设计

这类工具 App 走的是 GitHub 手动分发，用户最常见的困惑是「我装的这版是不是最新的」。
如果只给一个 Releases 页链接，绝大多数人不会去点；如果做静默自动安装，
又需要系统级权限并且完全绕过用户的知情。折中方案是：

1. **只检查，不打扰**：启动时静默查一次（24 小时最多一次），没更新或查失败都不留痕迹；
2. **让用户点最后那一下**：下载、校验在应用内完成，安装界面由系统弹出，用户仍要确认；
3. **校验在安装之前**：宁可拒绝安装，也不装一个来路不明的包。

最后一条是整个方案的核心。更新链路是唯一「从网络拿可执行文件并执行」的路径，
它比任何其他功能都值得多写一百行代码。

---

## 分层

| 层 | 类 | 职责 | 不做什么 |
| --- | --- | --- | --- |
| `core` | `UpdateChecker` | 请求 `releases/latest`，解析版本、日志、APK 地址与摘要 | 不下载、不安装 |
| `core` | `ApkDownloader` | 单任务下载、算摘要、校验、落盘 | 不弹任何 UI（只回回调） |
| `core` | `ApkVerifier` | SHA-256 / 签名 / 包名的纯判断 | 不决定该不该删文件 |
| `core` | `UpdateInstaller` | 查「安装未知应用」授权、FileProvider、拉起安装器 | 不做校验 |
| `ui` | `UpdateDialog` | 「发现新版本」结果框 | 不管下载 |
| `ui` | `UpdateDownloadDialog` | 下载进度 / 校验结果 / 安装入口 | 不管网络细节 |

`core` 层四个类**不 import 任何 `ui` 包**，这是项目的分层铁律。副作用是
`UpdateDownloadDialog` 必须自己决定文案与按钮语义 —— 这正是 UI 该做的事。

---

## 摘要从哪来：三级回落

安装前必须有一个可信的期望摘要。三个来源按可信度排序：

| 级别 | 来源 | 可信度 | 说明 |
| --- | --- | --- | --- |
| ① | asset 的 `digest` 字段 | 最高 | GitHub 在**接收上传时**算的，作者无法在事后伪造 |
| ② | Release 正文里的 `SHA-256：<64 位>` | 中 | 由 `release.yml` 从构建产物算出后写入 |
| ③ | 同名 `.apk.sha256` 侧车资产 | 中 | 同上，只是换个载体，供 ① 缺失时下载器按需拉取 |

**三级全空时下载器会拒绝安装**（`ERR_SHA_MISSING`），并把用户导向浏览器手动下载。
这不是保守过头：一个连摘要都拿不到的渠道，没有理由让 App 替用户执行它。
自己 Fork 发布时必须走 `release.yml`，否则自动更新会一直停在这一步 —— 这是刻意的。

解析实现见 `UpdateChecker.parseLatest` 与 `ApkVerifier.normalizeSha`。
后者要处理 `sha256:`、`SHA-256：`（中文冒号）、反引号包裹、
「摘要 + 两个空格 + 文件名」等写法，细节都在方法注释里。

---

## 校验链

`ApkDownloader` 在**同一个线程里**顺序做完四件事，任一步失败就删文件、不回调 `onReady`：

```
写盘的同时算 SHA-256
   ↓
① 摘要比对（constantTimeEquals）
   ↓
② 包名 == 当前 applicationId
   ↓
③ 新包签名证书 == 当前已装版本签名证书
   ↓
markReady() + onReady()
```

**摘要边下边算**，而不是下完再读一遍盘：27 MB 的包在低端机上二次读盘要一秒多，
没有额外收益。

**签名比对是提前失败**，不是安全兜底（真正的兜底是系统安装器）。
它的价值在于把「下到 100% 后系统说『应用未安装』」变成「下完立刻说清原因」。
典型触发场景：当前装的是自行编译的 debug 包，而 Release 是 release 签名 ——
两者包名相同、签名不同，系统必然拒装。文案要明确告诉用户「需要先卸载，会丢设置」。

签名读取走 `PackageManager.getPackageArchiveInfo`：API 28+ 用
`GET_SIGNING_CERTIFICATES` + `SigningInfo`，以下用 `GET_SIGNATURES`。
`signingInfo.hasMultipleSigners()` 为 true（v3 轮换密钥）时取 APK 内签名者，
否则取签名历史的最新一张。

---

## 单任务与「关框不中断」

`ApkDownloader` 是**静态单任务**，不是「每次 new 一个」：

- 启动静默检查与设置页手动检查可能几乎同时触发，两个任务并行下载既是浪费流量，
  也会在缓存目录里留下两份半截文件；
- 已有任务在跑时 `start()` 只换 listener，不另开线程。

关掉下载对话框**不会中断下载**（`attach(null)` 只摘回调）。数据继续写，
完成后落 `ready_tag` / `ready_path` / `ready_sha` 三个键。下次打开更新框时
`readyApk()` 命中就直接进「已就绪」态，按钮文案也从「立即更新」变成「安装更新」——
用户不会因为手滑关框而重下几十 MB。

要真的停，只有按框里的「取消」（`ApkDownloader.cancel()`），它会 `disconnect()`
并删掉 `.part` 文件。

---

## 权限与 FileProvider

| 项 | 值 | 说明 |
| --- | --- | --- |
| 权限 | `REQUEST_INSTALL_PACKAGES` | 普通权限，声明即可；Android 8.0 起还需用户单独授权 |
| 授权检查 | `PackageManager.canRequestPackageInstalls()` | 未授权时 `startActivity` **不报错也不提示**，静默失败 |
| 授权页 | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` | 国产 ROM 常裁掉，`UpdateInstaller` 有三级回落 |
| 分享 | `${applicationId}.fileprovider` | 路径见 `res/xml/file_paths.xml` |
| 落点 | `getExternalFilesDir("update")` | 不可用时回落 `getCacheDir()/update` |

`file_paths.xml` 里 `external-files-path` 与 `cache-path` 都是 `path="."`，
因此上面两个落点都在 FileProvider 的覆盖范围内 —— 改落点时**必须同步改这里**，
否则 `getUriForFile` 会抛 `IllegalArgumentException`。

Intent 除了 `FLAG_GRANT_READ_URI_PERMISSION` 还要 `setClipData`：只加 flag 在
部分 ROM 上不足以把读权限传给安装器，安装器打开 `content://` 会拿到 `SecurityException`。

---

## 签名密钥

自动更新靠「签名一致」判断继承关系，**换钥等于换应用**：用户装了 1.1.0，之后所有版本
必须用同一把钥签，否则 `ApkVerifier` 会判定签名不符并提示「需先卸载」（丢设置）。
所以这把钥要当资产对待，不是一次性产物。

| 项 | 值 |
| --- | --- |
| 本机路径 | `C:/Users/<user>/.aurora-signing/aurora-release.jks`（**项目目录之外**） |
| 配置 | 根目录 `keystore.properties`（已被 `.gitignore` 排除） |
| 参数 | RSA 4096、有效期 10950 天（30 年）、`alias=aurora`、DN `CN=Aurora` |
| 入库状态 | **绝不入库**，`*.jks` / `*.keystore` / `keystore.properties` 三条规则都在 `.gitignore` 里 |

`app/build.gradle.kts` 在**没有** `keystore.properties` 时跳过签名配置，`assembleDebug`
照常可跑 —— 这也是「克隆下来就能编」的前提。

### 同步到 GitHub Actions

`release.yml` 里的签名步骤读四个 secret。生成 base64 的方式：

```bash
base64 -w0 ~/.aurora-signing/aurora-release.jks | pbcopy   # macOS
base64 -w0 /c/Users/<user>/.aurora-signing/aurora-release.jks   # Git Bash，结果贴进仓库 secret
```

| Secret | 值 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | 上面那条命令的输出（单行，无换行） |
| `SIGNING_STORE_PASSWORD` | `keystore.properties` 的 `storePassword` |
| `SIGNING_KEY_ALIAS` | `aurora` |
| `SIGNING_KEY_PASSWORD` | `keystore.properties` 的 `keyPassword` |

**没配也能跑通 CI，但产物未签名、用户装不上** —— 这种包发出去比不发更糟，
所以宁可先用手动发版（下一节）过渡。

## 手动发版（不走 CI）

`scripts/publish_release.py` 是 `release.yml` 的离线等价物：同样的正文模板、同样的
`Aurora-<tag>.apk` 命名、同样产出 `.apk.sha256` 侧车，只是改在本地执行。

```bash
# 1. 出包（需要 keystore.properties 在位）
./gradlew :app:assembleRelease

# 2. 改名 + 算摘要
mkdir -p build/release-artifacts
cp app/build/outputs/apk/release/app-release.apk build/release-artifacts/Aurora-v1.1.0.apk
cd build/release-artifacts && sha256sum Aurora-v1.1.0.apk > Aurora-v1.1.0.apk.sha256 && cd ../..

# 3. 发布（token 只走环境变量）
GH_TOKEN=ghp_xxx python scripts/publish_release.py v1.1.0
```

脚本会先自查「侧车摘要 = 实际文件摘要」，不一致直接退出；`CHANGELOG.md` 里抽不到
对应版本段落也拒绝发布 —— 不发半成品。

> 这个脚本**不会**触发 `release.yml`：GitHub 通过 API 建 release 时顺带创建的 tag
> 不算一次 push 事件。想走 CI 就在本地 `git tag` 后 `git push --tags`。

## GitHub Actions 发版

`release.yml` 由 `v*` tag 触发，关键步骤：

| 步骤 | 作用 | 不做会怎样 |
| --- | --- | --- |
| **Verify tag matches versionName** | tag `v1.2.0` 必须等于 `versionName` | 包里写 1.1.0、Release 标 v1.2.0 → 用户永远「已是最新」 |
| Run unit tests | 63 个用例 | 算法写错没人挡 |
| Build release APK | `assembleRelease` | — |
| Rename + digest | `Aurora-v1.2.0.apk` + `.apk.sha256` | 多个 tag 的资产全叫 `app-release.apk`，摘要也没处算 |
| Verify APK signature | `apksigner verify --print-certs` | keystore 配错要到用户手机上才发现 |
| Compose release notes | 从 `CHANGELOG.md` 抽本版段落 + 写 SHA-256 | 手贴容易漏版本、把上一版内容贴进来 |
| Create **draft** release | 草稿，人工 Publish | tag 打错时已经被人下载了 |

### 客户端与 CI 的契约（改任一侧都要两边一起改）

1. APK 资产名以 `.apk` 结尾 —— `UpdateChecker` 取第一个匹配项；
2. 摘要出现在三处之一：asset `digest`（GitHub 自动）、正文 `SHA-256：\`<64 位>\``、
   `*.apk.sha256` 资产；
3. tag 以 `v` 开头，且去掉 `v` 后能通过语义化版本比较（`1.2.0`、`1.2.0-beta.1` 都行）；
4. **草稿与预发布不计入 `releases/latest`** —— 所以发布后必须点 Publish，
   否则 App 永远查不到这一版；带连字符的 tag 会被标成 prerelease，同样不会被推到正式用户。

---

## 失败模式速查

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| 设置页检查：`仓库或 Release 不存在（HTTP 404）` | 仓库名不对 / 还没 Publish Release | 改 `gradle.properties` 的 `aurora.updateRepo` / 去点 Publish |
| `GitHub 接口调用次数已用尽` | 未登录 60 次/小时/IP | 等一小时，或点「手动查看」 |
| `该版本没有提供 SHA-256 校验值` | 发布时没走 `release.yml` | 补发 `.sha256` 侧车或正文摘要 |
| `校验失败：安装包与官方摘要不一致` | 传输损坏 / 代理改写了响应 | 重试；反复出现就查网络中间设备 |
| `签名与当前安装的版本不一致` | 装的是自行编译的 debug 包 | 卸载后重装（会丢设置） |
| 点「安装」没反应 | 未授权「安装未知应用」 | 框里会显示「去授权」并跳设置页 |

---

## 维护提醒

- 改 `UpdateChecker.Release` 的字段时，`parseLatest` 与 `UpdateCheckerTest` 要一起改；
  那几个用例锁的是「摘要从哪一级来」，写错的表现是「永远提示摘要缺失」。
- `ApkVerifier` 的纯函数有独立单测（`ApkVerifierTest`），用 FIPS 180-4 的标准向量
  锁死算法输出。**不要**改成「跑一遍看不报错」式的断言。
- 单测里能真的 `new JSONObject(...)`，是因为 `testImplementation(libs.json)` 引入了
  真实的 `org.json`；`android.jar` 里那份是桩，调用会抛 `not mocked`。
- 下载目录里的历史 APK 在每次 `start()` 时清空（`cleanOld`）。如果将来要做
  「保留上一版以便回滚」，改动点在 `ApkDownloader.Job.cleanOld`。
