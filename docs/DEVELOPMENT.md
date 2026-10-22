# 开发指南

> 本文是动手改这个项目之前的**必读清单**。
> 重点是「哪些写法看着能简化但实际会炸」以及「改完拿什么证明它是对的」。
> 架构层面的内容见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

---

## 一、环境

| 项 | 值 |
| --- | --- |
| JDK | **21**（AGP 9.x 强制要求；模块的 `sourceCompatibility` / `targetCompatibility` 是 **11**） |
| Android SDK | 需含 **`platforms/android-36.1`**（`compileSdk = 36` 且 `minorApiLevel = 1`，只用 `android-36` 不够） |
| `local.properties` | `sdk.dir=<你的 SDK 路径>`，**不要提交**（已在 `.gitignore`） |
| Gradle 用户目录 | 默认 `~/.gradle`；可用环境变量 `GRADLE_USER_HOME` 指到别处 |
| 命令行 | 三个平台都行；本项目的维护者环境是 Windows + Git Bash（下文示例用 `./gradlew`） |

> **Windows 上的一个坑**：`javac` / `javap` 是 Windows 程序，`-cp` 必须用 Windows 风格路径
> （`C:\...` 或 `E:\...`）。在 Git Bash 里写 `/e/...` 会报「找不到类」—— 这不是代码问题。

---

## 二、构建

```bash
# 常规构建（首次会下载依赖；依赖已在本地缓存时可加 --offline 跳过网络检查）
./gradlew :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk

# 装机（-r 覆盖安装，-d 允许降级）
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

### 检查更新指向的仓库

应用通过 GitHub Releases API 判断有没有新版本，仓库地址不写死在代码里：

```properties
# gradle.properties
aurora.updateRepo=LonLsme/AuroraApp
```

构建时它被烧进 `BuildConfig.UPDATE_REPO`。**Fork 或改仓库名后必须改这一行**，
否则 App 会一直报「仓库或 Release 不存在（HTTP 404）」。命令行也能临时覆盖：

```bash
./gradlew :app:assembleDebug -Paurora.updateRepo=you/AuroraApp
```

要验证检查逻辑本身（不联网）：

```bash
./gradlew :app:testDebugUnitTest --tests '*UpdateCheckerTest' --tests '*ReleaseNotesTest'
```

版本比较的边界（`1.10.0` vs `1.9.0`、`1.2` vs `1.2.0`、`-beta` 低于正式版）都在单测里锁死了。
想验证真实网络链路，直接用 curl 打一次接口，比对字段名即可：

```bash
curl -H "Accept: application/vnd.github+json" \
     -H "User-Agent: Aurora-App/1.1.0" \
     https://api.github.com/repos/LonLsme/AuroraApp/releases/latest
```

### 发版

1. 改 `app/build.gradle.kts` 的 `versionCode`（必须递增）与 `versionName`
2. 在 `CHANGELOG.md` 的 `[Unreleased]` 下确认条目，改成 `## [x.y.z] - YYYY-MM-DD`
3. `git tag vX.Y.Z && git push origin vX.Y.Z`
4. `.github/workflows/release.yml` 自动跑测试 → 构建 Release APK → 挂到**草稿** Release
5. 到 GitHub 填好更新说明、点 **Publish release**

> 草稿与预发布**不会**被 `releases/latest` 返回，所以发布之前用户那边的检查更新不会误报。

CI 出已签名包需要四个 secret：`SIGNING_KEYSTORE_BASE64` / `SIGNING_STORE_PASSWORD` /
`SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`。不配也能跑通，只是产物未签名、装不上。

### `Android Studio 运行时会锁 Gradle 缓存`

现象是 `./gradlew` 报 `.lck (拒绝访问)` 或 `native-platform.dll` 加载失败。
**这不是代码问题，不要去杀 IDE 进程** —— 要么关掉 IDE 再跑命令行，
要么让 IDE 自己构建。

### 体积别只看源文件，也别只看行数

删掉源文件 / 换掉 `assets/` 内容之后，**必须先 `clean` 或删掉产物 APK 再重新打包**，
否则测到的是旧产物。详见「踩坑 · 打包」一节。

---

## 三、项目约定

这几条是这个项目**明确要求**的做法，不是建议。

### 3.1 命令与文案必须回既有工具查证

引用某个开源项目的实现或效果之前，**先回它的源码里看真实实现**，
确认「它在哪、由谁实现、原文是什么」，再决定怎么移植。

- 文案、命令字符串一律以既有工具的**原文**为准，**不凭空写、不「顺手美化」**。
- 如果确实需要偏离原文，**必须在代码注释里写明偏离理由**。
- 反例：曾准备给某个界面加一个「旋转动画」，回源码一看 KernelSU 根本没有这个动画 —— 差点凭空捏出来一个不存在的效果。

### 3.2 不自己跑真机验证

改完代码 = **编译通过 + 离线断言通过 + 把「待验证项」列成清单交给用户**。

- **装包（`adb install -r -d`）算交付动作，可以做。**
- **但不要自己点 UI、改设置、重启设备跑验证流程。**
- 用户明确说过「主力机」时，**连装包都不做**，只交付代码。

### 3.3 UI 不堆提示文案

页面只放**控件 + 实时状态**。

机制说明、注意事项、救援命令这类内容**一律不要做成卡片摆在页面上** ——
需要解释的东西写进代码注释和 `docs/`。

### 3.4 耗时检测 / IO 禁止进首屏路径

任何 Root 调用、文件遍历、进程枚举都必须走**单线程池 + 分片渲染**，
不能出现在 `onCreate` 的主线程路径上。

反例：安全页曾经在主线程执行 `su getenforce`（耗时 2.5s），导致进入即卡死。

### 3.5 刷机页的语义是「Aurora 是主机」

刷机页所有子页的语义统一为：

> **Aurora 是刷机工具（主机），操作对象是连着的那台目标设备。**

不是操作本机。凡涉及「免 root」「激活」「冻结」这类词，**先确认是主机还是本机** ——
这个方向已经踩过一次，把「激活目标机权限」理解成了「激活本机权限」。

### 3.6 改动要留痕

每次实质性改动，在项目的 Obsidian 笔记里当天目录的 `改动日志.md` **顶部追加**（倒序），
并同步根目录 `改动日志总览.md` 的索引表。

---

## 四、踩坑清单

### 4.1 注释类（编译期直接报错，最高频）

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| **Javadoc 里含 `*/`** | 注释被提前闭合 → 一片「非法字符」错误 | 写成 `&#42;/`、换分隔符，或把常量提出来 |
| **XML 注释里含 `--`** | `[Fatal Error] xxx.xml: 注释中不允许出现字符串 "--"` | 改用别的措辞。**注意属性值 `android:text="…--dry-run…"` 是合法的**，只有注释里不行 |
| **lambda 引用非 final 局部变量** | 编译失败 | 把变量声明成 `final`，或改成成员字段 |

> 这三条是本项目**最高频**的编译失败原因，注释类已经连踩多次。
> 写含路径/命令/正则的注释时，先扫一遍有没有 `*/` 和 `--`。

### 4.2 构建类

**① `assets/` 下的任何文件都会被无条件打进 APK。**

不需要区分扩展名、不会被 shrink 掉。所以：
- **备份文件（`.bak` 之类）绝对不能留在 `assets/` 里**；实测把一个 7.76MB 的备份留在
  `assets/`，APK 直接从 26.0MB 涨到 35.9MB。
- 放回去之前先想清楚它对包体的贡献。

**② 删掉 `assets/` 里的条目后，增量打包会留下「死区」。**

实测：移出备份后 APK 只从 35,896,238 降到 35,896,155（几乎没变），
而用 Python `zipfile` 加总条目 `compress_size` 只有 27,871,335 ——
**8.02MB 不属于任何条目，是旧包的残留**。

```bash
# 正确做法：删掉产物 APK 再重新打包
rm -f app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug --offline
# → 体积立刻回到真实值（overhead 约 276KB，属正常）
```

与「删源文件后务必 clean 再判体积」是同一类问题。

**③ 工具链包版本号必须跟着升。**

`FlashToolchain.TOOLCHAIN_VERSION` 是 assets 的版本门控。
**改了 `assets/flash_tools.zip` 的内容却不升号，已装机的用户永远解压不到新文件。**
详见 [`subsystems/FLASH.md`](subsystems/FLASH.md)。

### 4.3 验证类

| 坑 | 说明 |
| --- | --- |
| **`javap` 按 GBK 输出** | 中文会显示成乱码（`ȷ` / `ˢ` / `ִ`）→ 用 `iconv -f GBK -t UTF-8` 转码 |
| **`static final String` 常量会被编译期折叠** | 常量表达式的值直接内联进调用处，**单独 grep 字符串常量找不到它** → 查这种字段要用 `javap -constants` 看 `ConstantValue` |
| **aapt2 查 `id` 要两步** | `aapt2 dump xmltree` 里 `android:id` 是**资源 ID 数字**（`@0x7f08xxxx`），名字不在里面。必须先 `aapt2 dump resources` 拿「`id/名字` → `0x...`」，再回布局 grep 那个数字 |
| **`aapt2 dump resources` 的行格式** | `    resource 0x7f0800ce id/btnQdlRgpt` → **字段 2 是数值、字段 3 是名字**（曾按字段 2 比名字，导致整章断言全红） |
| **同一份 xmltree 里两种属性形态并存** | `android:id` 与 `app:strokeColor` 打印**原始值**（`@0x7f05009a`），普通属性解析成**名字**（`@color/ios_separator`） |
| **`aapt2 dump xmltree` 对短名标签原样输出** | 是 `E: ImageView`，**不是** `E: android.widget.ImageView` |

> **断言写错比代码写错更常见。** 写断言之前，先跑一次 `javap` / `aapt2` 看**实际输出**，
> 再照着实际输出写期望值，而不是照着「我以为它应该长什么样」写。

---

## 五、验证方法（不跑真机也能验的部分）

### 5.1 离线断言脚本

适用场景：验证「某个类/方法/常量/资源 id/字符串文案是否存在」，**不需要设备**。

classpath 组成：

```
<SDK>/platforms/android-36/android.jar
app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes   ← Kotlin 产物在这
```

要点：

- **Kotlin 编译产物不在 `javac` 目录下**，在 `built_in_kotlinc/.../classes`。
- `android.util.Log` 是 stub（调用即抛异常）→ 断言脚本里自写一个 **no-op 版，
  排在 `android.jar` 之前**。
- 用 `javap -p` 断言**私有函数存在/不存在**（反断言很有用：
  「这个类**不应该**调用 `owrite`」比「应该调用 `rgpt`」更能防回归）。
- 断言要**分章**（如 A 工具链包 / B 字节码 / C 资源 id / D Manifest / E 反断言），
  一眼能看出是哪一层坏了。

### 5.2 「假可执行文件」探针

适用场景：验证**命令字符串经过转义后，外部程序真正收到的原文是什么** ——
比读代码可靠得多。

做法：

1. 写一个把 `argv` 打印出来的**一行 shell**，当成假 `adb`（或假 `qdl`）放进临时目录；
2. 用**绝对路径**把这个目录加进 `PATH` 的最前面；
3. 直接跑生产代码里拼装命令的那个类，看假程序收到了什么。

```bash
# 关键细节：
# ① 必须用绝对路径加进 PATH —— 否则报 "sh: line 1: adb: command not found"
# ② Java 输出中文要加 -Dstdout.encoding=UTF-8
# ③ 如果被测代码会 mkdir 之类的副作用，配一个 no-op 假命令，别在真实路径下建目录
```

这个手法验证过的东西举例：带空格的引导路径仍是**单个参数**、
`it's` 被转义成 `'\''` 后**能正确还原**、传 `boot; rm -rf /` 这样的分区名
**被单参数化未被执行**、调试开关**幂等**。

### 5.3 产物级核对

```bash
# 看 APK 里真实打进的文件
unzip -l app/build/outputs/apk/debug/app-debug.apk

# 看布局 xmltree 里的**真实属性值**（不是源码里的写法）
aapt2 dump xmltree app-debug.apk --file res/layout/activity_qdl.xml

# 逐字节比对：APK 内的 zip 是否与源文件一致
python -c "
import zipfile
z = zipfile.ZipFile('app/build/outputs/apk/debug/app-debug.apk')
assert z.read('assets/flash_tools.zip') == open('app/src/main/assets/flash_tools.zip','rb').read()
print('一致')
"
```

### 5.4 交付清单

改完之后，交付给用户的内容应该是：

1. **编译结果**：`BUILD SUCCESSFUL` + 产物大小及变化量；
2. **断言结果**：多少项通过、几章、含哪些反断言；
3. **改了哪些文件**：逐个列出来；
4. **待验证项**：明确写出「哪些是离线验不了的、需要上真机怎么看」，
   并**按重要性排序**。这一项最容易被省略，但它是交付的核心 ——
   用户就是照着这个清单去测的。

---

## 六、提升效率的几个约定

| 场景 | 做法 |
| --- | --- |
| 想知道某个布局/文案在哪个文件 | 先 `Grep` 文案关键字，命中后再读 |
| 想知道某个功能做没做 | 查 [`FEATURES.md`](FEATURES.md)，比翻代码快 |
| 指标读不到 / 显示 `--` | **先看 [`subsystems/SYSTEM_DATA.md`](subsystems/SYSTEM_DATA.md)**，多数是系统权限边界而不是 bug |
| 需要看某次改动的原因 | 项目 Obsidian 笔记的当日 `改动日志.md` |
| 新加一个竖屏刷机子页 | 照抄 `ui/flash/QdlActivity.java` 的骨架，**别忘了 Manifest 里加 `configChanges`**（见下） |

> [!warning] 刷机子页必须带 `configChanges`
> 所有刷机子 Activity 都在 `AndroidManifest.xml` 里声明了完整的 `configChanges`
> （`screenSize|smallestScreenSize|density|screenLayout|orientation|uiMode|fontScale|layoutDirection`）。
>
> 原因：**页内输出区是刷机过程中唯一的进度来源，Activity 重建会把整段输出清空。**
> 新加子页时漏掉这一行，用户转个屏就丢日志。
