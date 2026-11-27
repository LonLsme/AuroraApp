# 参与贡献 Aurora

感谢你愿意参与 Aurora 的改进。

Aurora 是基于 GPL-3.0 协议开源的项目，所有贡献内容也将遵循该协议发布。在参与之前，建议先了解项目的结构和已有实现，这能帮助你更快上手，也能避免重复踩坑。

首先建议阅读 [`docs/README.md`](docs/README.md)。这里整理了项目的架构分层、四个 Tab 的组成方式、各子系统的实现说明，以及开发过程中遇到的一些问题和处理方案。很多代码设计上的原因和细节都可以在文档中找到。

---

## 一、开发环境

| 项目             | 要求              | 说明                                                  |
| -------------- | --------------- | --------------------------------------------------- |
| JDK            | **21**          | AGP 9.x 要求，低版本可能无法正常配置或构建                           |
| Android Studio | **Narwhal 及以上** | 需支持 AGP 9.1.1                                       |
| Android SDK    | **Platform 36** | 项目使用 `compileSdk 36.1`，具体配置见 `app/build.gradle.kts` |
| Gradle         | **9.5.1**       | 使用项目自带的 Wrapper，无需单独安装                              |

### 首次构建

1. 配置 Android SDK 路径：

```bash
# 将路径替换为本机 Android SDK 的实际位置
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

`local.properties` 已加入 `.gitignore`，不要将它提交到仓库。

2. 构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

首次构建需要下载相关依赖，请确保网络正常，不要使用 `--offline`。

3. 构建完成后，APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

\<text color="secondary" weight="medium">Windows 用户：\</text> `local.properties` 中的 SDK 路径可以使用正斜杠，例如 `sdk.dir=C:/Android/Sdk`，也可以使用转义后的反斜杠。

如果构建时出现 `.lck` 锁文件或缓存拒绝访问的问题，可能是 Android Studio 正在占用 Gradle 缓存。先关闭 IDE，再重新构建，避免直接终止相关进程。

### Release 构建

Release 构建需要配置签名。具体步骤见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) 中的「发布构建」。

未配置签名时，`assembleRelease` 生成未签名 APK 属于正常情况。

---

## 二、代码结构与约定

项目主要分为以下几个部分：

```text
ui/    表现层：Activity、Fragment、自定义 View、Compose
core/  能力层：root、flash、monitor、display、cpu、util
data/  数据层：Root 检测、运行时探测、模型
```

以下约定在开发过程中需要遵守。

1. **保持分层关系。**

   依赖方向为 `ui → core → data`。`core/` 中不得引用 `ui/` 下的类，避免业务能力与界面耦合。

2. **颜色使用语义 attr。**

   使用 `?attr/auroraCard`、`?attr/auroraFill`、`?attr/auroraAccent` 等语义属性，不使用硬编码色值或直接引用固定颜色资源。主题和强调色的适配依赖这些属性。

3. **注意深色模式下的组件层次。**

   深色模式下，`auroraCard` 与页面背景的明度差异较小。新增组件时，建议使用 `?attr/auroraSeparator` 添加 1dp 描边，避免组件与背景融为一体。

4. **处理好外部输入。**

   涉及命令拼接的外部输入必须经过转义处理，使用 `FlashCommands.esc()` 或 `FlashRuntime.shellEscape()`。执行命令页面除外，该页面的输入本身就是命令，相关原因已在代码注释中说明。

5. **注释要说明设计原因。**

   中文注释和 Javadoc 保持项目现有风格。尽量说明实现背后的原因、限制和取舍，而不是重复代码本身的功能。

---

## 三、提交前检查

提交代码之前，请检查以下事项：

- [ ] `./gradlew :app:assembleDebug` 构建通过，且没有新增编译警告。
- [ ] Javadoc 中没有意外出现 `*/`。例如 `/proc/[0-9]*/stat` 这类路径可能提前结束注释，导致后续内容出现编译错误。
- [ ] XML 注释中没有 `--`。例如 `--storage=xxx`、`--dry-run` 不能直接放入 XML 注释中，否则可能导致 `parseDebugLocalResources` 失败；放在属性值中则不受此限制。
- [ ] 新增 Activity 已在 `AndroidManifest.xml` 中注册，子页面的 `configChanges` 配置符合项目要求。
- [ ] 没有提交 `local.properties`、`*.jks`、`keystore.properties`、`build/` 或 `*.log` 等文件。
- [ ] 没有将临时文件放入 `app/src/main/assets/`。该目录下的文件会被打包进 APK。
- [ ] 新增或修改的资源、文案与 `docs/` 中对应的文档保持一致。涉及结构或实现变化时，记得同步更新文档。

### 单元测试

如果改动涉及可测试的逻辑，建议运行单元测试：

```bash
./gradlew :app:testDebugUnitTest
```

---

## 四、提交信息规范

提交信息使用 Conventional Commits 格式，描述内容使用中文。

示例：

```text
feat(flash): 新增「执行命令」页，支持自定义 ADB / Fastboot 命令
fix(theme): 修复主题预览底栏颜色与实机不一致
docs(readme): 补全首次构建步骤
refactor(monitor): 进程采集改为单次 root dump
```

常用类型：

`feat`、`fix`、`refactor`、`perf`、`docs`、`build`、`chore`、`test`

每条提交尽量只处理一件事，方便后续审查和维护。不要在功能提交中顺手修改版本号或进行无关的格式调整，版本变更由维护者统一处理。

---

## 五、Pull Request

提交 PR 时请注意以下事项：

1. 从 `main` 分支创建独立分支，例如 `feat/xxx`、`fix/xxx`。合并后可删除分支。
2. 只修改与本次目标相关的文件，避免夹带无关格式调整或其他改动。
3. 在 PR 描述中说明改动内容、改动原因以及验证方式。截图、日志或测试结果有助于审查。
4. 涉及 UI 的改动，请提供浅色和深色模式下的截图。项目颜色涉及强调色覆盖层和深色资源两部分，仅检查一种模式可能遗漏问题。AMOLED 纯黑模式已在 1.2.2 移除，无需额外测试该模式。
5. 涉及刷机、Root、SELinux 等高风险操作的改动，必须说明测试设备、系统版本及实际验证情况。

提交 PR 即表示你同意贡献内容以 GPL-3.0 协议发布，并确认提交内容为本人原创，或来自允许兼容使用的开源来源。

---

## 六、提交时需要注意

- 不要提交签名密钥、`local.properties`、个人设备信息或其他敏感文件。
- 不要为了统一格式而批量重排现有代码。`core/` 中的部分注释和代码布局是有意保留的。
- 不要在未经验证的情况下修改刷机命令模板。相关命令经过逐项核对，错误修改可能导致设备或分区损坏。
- 不要在 PR 中夹带版本号修改，版本更新由维护者统一处理。

---

## 七、可以从哪些地方开始

如果暂时不知道从哪里开始，可以考虑以下方向：

- **文档完善：** `docs/` 中仍有部分子系统需要补充说明。
- **单元测试：** `core/` 中的纯函数，例如 `FlashCommands`、`AuroraPalette`、`LoaderCatalog`，目前测试覆盖仍有不足。补充测试是比较容易入手的贡献方向。
- **设备兼容：** 帮助验证不同 ROM 下的系统数据读取和功能兼容性，相关内容可参考 [`docs/subsystems/SYSTEM_DATA.md`](docs/subsystems/SYSTEM_DATA.md)。
- **翻译与资源整理：** 目前部分界面文案仍直接写在布局文件和 Java 代码中，逐步整理并抽离到 `strings.xml` 是一项可以独立推进的工作。

如果你有其他改进想法，也不必局限于以上内容。只要与项目相关，都欢迎讨论。

感谢每一份认真提交的反馈、代码和文档。

真希望这个项目越来越好，谢谢！

— LonLsme
