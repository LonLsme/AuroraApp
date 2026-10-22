# 参与贡献 Aurora

感谢愿意花时间改进 Aurora。本项目是 **GPL-3.0** 开源软件，任何贡献都会以同一协议发布。

> 先读 [`docs/README.md`](docs/README.md)。那份索引里能查到架构分层、四个 tab 的拼装方式、
> 各子系统的实现细节和一批「已经踩过的坑」——**九成的「为什么这里要这么写」都在里面**，
> 比从代码反推快得多。

---

## 一、环境要求

| 项 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21** | AGP 9.x 强制要求；低版本会在配置阶段直接失败 |
| Android Studio | **Narwhal 及以上**（支持 AGP 9.1.1） | 旧版 IDE 打不开本工程 |
| Android SDK | **Platform 36**（`compileSdk 36.1`） | 见 `app/build.gradle.kts` |
| 构建工具 | Gradle **9.5.1**（Wrapper 自带，无需手动装） | 见 `gradle/wrapper/gradle-wrapper.properties` |

首次构建：

```bash
# 1) 指明 SDK 位置（不要提交这个文件，已在 .gitignore 里）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建 Debug APK（首次会下载依赖，别加 --offline）
./gradlew :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

> Windows 上 `local.properties` 里的路径要写成 `sdk.dir=C\:\\Android\\Sdk`（或正斜杠 `C:/Android/Sdk`）。
> 若报 `.lck (拒绝访问)`，是 Android Studio 正持着 Gradle 缓存 —— 关掉 IDE 再构建，不要杀进程。

**Release 构建**需要自带签名，见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) 的「发布构建」一节；
不提供签名时 `assembleRelease` 只会产出未签名 APK，这是预期行为。

---

## 二、代码结构约定（硬规则）

```
ui/    表现层：Activity / Fragment / 自定义 View / Compose
core/  能力层：root / flash / monitor / display / cpu / util
data/  数据层：Root 检测、运行时探测、模型
```

1. **分层严格单向**：`ui → core → data`。**`core/` 下不允许 import 任何 `ui/` 的类** ——
   这条没有例外，`grep` 一下就能自查。
2. **颜色必须走语义 attr**（`?attr/auroraCard` / `auroraFill` / `auroraAccent`），
   不要写 `@color/` 或硬编码 hex：只有 attr 会被「强调色覆盖层」和「AMOLED 纯黑」改写。
3. **AMOLED 下新组件要补 1dp 描边**：纯黑模式里 `auroraCard` 与页面背景同为纯黑，
   不描边的控件会整块消失。用 `@color/ios_separator`。
4. **外部输入一律转义**（`FlashCommands.esc()` / `FlashRuntime.shellEscape()`），
   唯一例外是「执行命令」页 —— 那一页的输入本身就是命令行，注释里已写明原因。
5. **中文注释、Javadoc 保持现有密度**。这个项目的注释是为了让下一个人看懂
   「为什么这么写」，不是复述代码在做什么。

---

## 三、提交前自查清单

- [ ] `./gradlew :app:assembleDebug` 通过，且没有**新增**的编译警告
- [ ] Javadoc 里没有出现 `*/`（写 `/proc/[0-9]*/stat` 这类路径会提前闭合注释，随后一片「非法字符」）
- [ ] XML 注释里没有 `--`（`--storage=xxx`、`--dry-run` 写进 `<!-- -->` 会让 `parseDebugLocalResources` 直接失败；
      写在属性值里是合法的）
- [ ] 新增 Activity 已在 `AndroidManifest.xml` 注册；子页带 `configChanges`
- [ ] 没有把 `local.properties`、`*.jks`、`keystore.properties`、`build/`、`*.log` 加进提交
- [ ] 没有往 `app/src/main/assets/` 放临时文件（**这个目录下的任何文件都会无条件打进 APK**）
- [ ] 新增的资源/文案与 `docs/` 里对应章节保持一致（改了结构就顺手更文档）

跑单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

---

## 四、提交信息

用 Conventional Commits 形式，**正文用中文**：

```
feat(flash): 新增「执行命令」页，支持自定义 ADB / Fastboot 命令
fix(theme): 修复主题预览底栏颜色与实机不一致
docs(readme): 补全首次构建步骤
refactor(monitor): 进程采集改为单次 root dump
```

常用类型：`feat` / `fix` / `refactor` / `perf` / `docs` / `build` / `chore` / `test`。

一条提交只做一件事。**不要在功能提交里顺手改版本号或重排格式**，那会让 review 无法聚焦。

---

## 五、Pull Request

1. 从 `master` 切分支：`feat/xxx`、`fix/xxx`
2. 只改与目标相关的文件，不要夹带无关格式化
3. PR 描述里写清：**改了什么 / 为什么 / 怎么验证的**（截图或日志最有用）
4. 涉及 UI 的改动请附**浅色 / 深色 / AMOLED 纯黑**三种模式下的截图 ——
   这个项目的配色有三层覆盖（强调色 / AMOLED / 夜间），只看一种模式很容易漏
5. 涉及刷机、Root、SELinux 这类高风险动作的改动，**必须说明你在什么设备上测过**

提交即表示你同意该贡献以 **GPL-3.0** 授权发布，并确认代码为你本人原创或来自兼容协议的来源。

---

## 六、不要做的事

- **不要**提交签名密钥、`local.properties`、个人设备信息
- **不要**为了「看起来更整洁」而批量重排既有代码（`core/` 里的大量注释是有意为之）
- **不要**在没验证的情况下改刷机命令模板 —— 那些字符串是逐条对照既有工具核对过的，
  改错会写坏用户的设备分区
- **不要**在 PR 里夹带版本号变更，发版由维护者统一做

---

## 七、可以贡献什么

不确定从哪开始，可以看这几类：

- **文档补全**：`docs/` 里仍有「待补」的子系统
- **单元测试**：`core/` 下的纯函数（`FlashCommands`、`AuroraPalette`、`LoaderCatalog`）
  几乎都还没有测试，这是最容易上手、收益最直接的方向
- **设备兼容**：不同 ROM 上读不到的系统数据（见 [`docs/subsystems/SYSTEM_DATA.md`](docs/subsystems/SYSTEM_DATA.md)）
- **翻译**：目前界面文案硬编码在布局与 Java 里，抽出 `strings.xml` 是一项独立的大工作
