# 子系统：高通 QDL 9008 深刷

> 代码位置：`core/flash/QdlCommands.java`（命令层，605 行）+
> `ui/flash/QdlActivity.java`（页面，610 行）+ `res/layout/activity_qdl.xml`（656 行）。
> 工具链打包见这个子系统在 [`FLASH.md`](FLASH.md) 第二节的 qdl 部分。

---

## 一、这个子系统在做什么

高通机型在 **EDL（Emergency Download，9008）** 模式下，可以通过 USB 直接读写
eMMC / UFS，绕过 bootloader —— 这就是「深刷」。

Aurora 的实现方式**不是自己写 Sahara / Firehose 协议**，而是
**移植了一个定制版 Linux `qdl` CLI**：把 aarch64 可执行文件打进工具链，
用 `su -c` + 固定 env 前缀跑起来，由它去操作 9008 口。

> 版本标识 `qdl 2.8`；banner 里保留着该分支构建者的署名 —— 是作者改过的分支。

### 一轮纠错（值得记住）

有一份流传的分析文档写着「`aarch64.tar` 含 adb/fastboot/**qdl/rgpt**」——
**这是错的**。`tar -tf` 实测 `aarch64/bin/` 里**只有 `qdl`**；
**`rgpt` 是这个定制 qdl 的子命令**，不是独立二进制。

---

## 二、qdl 的 CLI（从二进制还原，非推测）

```
qdl [options] <prog.mbn> rgpt <output_dir>
qdl [options] <prog.mbn>  reboot | reset | get_slot | set_slot <a|b>
qdl [options] <prog.mbn>  (erase <address> ...) | ((read|write) <address> ...)
qdl [options] <prog.mbn>  ((oread | owrite | oerase) <name> <Path|...>)
qdl [options] <prog.mbn>  (sha256 <address>)...
qdl list
qdl ramdump [--debug] [-o <path>] [<segment-filter>,...]
```

| 选项 | 说明 |
| --- | --- |
| `-s` / `--storage=T` | 合法值 `<emmc\|nand\|nvme\|spinor\|ufs>` |
| `--oplus-token[=PROJID]` | 欧加 Token 授权 |
| `-R` / `--skip-reset` | 完成后**不复位设备** |
| `-n` / `--dry-run` | 只拼装不执行 |
| `-d` / `--debug` | 详细日志 |
| `-u` / `--out-chunk-size` | 输出分块大小 |
| `--signeddigests` | 可重复，指定签名/摘要文件 |
| `-D` / `--vip-table-path` | VIP 模式的分区表路径 |

USB 标识拼法：`05c6:%04x`（`05c6` 是高通 Vendor ID，`9008` 是 EDL 的 Product ID）。

---

## 三、命令层：`QdlCommands`

### 3.1 为什么不并进 `FlashCommands`

**qdl 命令既不带 `adb` 也不带 `fastboot` 前缀** —— 它就是本机的一个可执行文件，
靠 `FlashRuntime` 的 env 前缀把工具链目录前置到 `PATH` 之后**直接跑**。
形态与「目标机通道」差别太大，混在一起会让两边的约定互相污染。

### 3.2 公开 API

| 方法 | 说明 |
| --- | --- |
| `rgpt(storage, progPath, auth, ...)` | 识别分区表（导出到 `OUTPUT_DIR`） |
| `setSlot(...)` / `getSlot(...)` | 切 / 查 A·B 槽位 |
| `reset(...)` / `reboot(...)` | 复位 / 重启 |
| `rgptThenSetSlot(...)` | 合成一条 shell 的「先 rgpt 再 set_slot」 |
| `owrite(...)` / `oread(...)` / `oerase(...)` | 单分区读写擦（**分区名寻址**） |
| `owriteAll(...)` / `owriteSelected(...)` / `oreadAll(...)` | 批量版本 |
| `pairedBaseNames(partitionsTxt)` | 从 `partitions.txt` 算 A/B 配对 |
| `applyDebugFlags(cmd, debug, dryRun)` | 幂等注入 `--debug` / `--dry-run` |
| `detectEdl()` | 探测 9008 设备 |
| `killResidual()` | 清理残留进程 |
| `parseActiveSlot(output)` | 从 `get_slot` 输出解析当前槽位 |
| `countSavedLuns(output)` | 数出导出了几个 LUN 的 GPT |
| `isValidSlot` / `isValidPartitionName` / `esc` | 校验与转义 |

### 3.3 枚举与常量

| 名称 | 值 |
| --- | --- |
| `StorageType` | `UFS("ufs")` · `EMMC("emmc")` · `NAND("nand")` · `NVME("nvme")` · `SPINOR("spinor")` |
| `OplusAuth` | `OFF`（不带授权参数） · `VIP` · `TOKEN` |
| `OUTPUT_DIR` | `/storage/emulated/0/Aurora/qdl` |
| `PARTITIONS_FILE` | `<OUTPUT_DIR>/partitions.txt` |
| `DEFAULT_CHUNK_SIZE` | `16384` |
| `EDL_USB_ID` | `05c6:9008` |
| `EDL_CHANNEL` | `9008/EDL` |
| `LUN_COUNT` | `6` |
| `SKIP_PARTITIONS` | 12 项跳过分区集（含「`my_` 开头」规则） |
| `HOTPLUG_WARNING` | ⚠️EDL 模式不支持热插拔，连接后请勿断开数据线，并确保 USB 连接稳定 |

> `OUTPUT_DIR` 是应用自己的目录，改动只需动 `QdlCommands` 这一个常量；
> 注意 `KernelSuPatcher` 的命令注释里也引用了同一路径，改的时候要同步。

### 3.4 六个「保持原样而非美化」的形态细节

这六条都是工具的**既有行为**，不是遗漏：

1. **`--out-chunk-size=16384` 只有 VIP / Token 两档才有**，`OFF` 档不带。
2. **`authSuffix()` 只认 Token 档** —— 所以 **VIP 档在 `set_slot` / `get_slot` / `reset`
   上不带任何授权参数**。
3. **跳过分区集 12 项**取自既有工具 + 「`my_` 开头」规则。
4. **A/B 配对取 `_a` / `_b` 的基名交集**（**不是并集** ——
   只有一侧不算成对，qdl 会报 `cross-LUN: no _b`）。
   全量包里的镜像名不带槽位、而设备分区带槽位，所以实际靠 `partitions.txt` 显式指定。
5. **`owrite` / `oread` / `oerase` 是「分区名寻址」**；
   另有 `read` / `write` / `erase` 是「扇区地址寻址」。既有工具用开关切，
   **本项目固定用 `o` 前缀版**。
6. **`oreadAll` 多一句 `mkdir -p`，且不带 `--skip-reset`** —— 与其它命令不同。

### 3.5 唯一有意偏离的地方：槽位正则

```java
// 既有工具：slot.*[=:].*([abAB])      ← 第二个量词贪婪
// 本项目：  slot.*[=:].*?([abAB])     ← 改成非贪婪
```

原文的**贪婪**量词在行尾还有别的英文单词时会回溯取错值 ——
例如 `slot: b, inactive` 可能取到 `inactive` 里的 `a`。

**读错槽位会写错分区**，所以这里值得偏离。

### 3.6 `--dry-run` 的价值

`applyDebugFlags(cmd, debug, dryRun)` 会用负向先行断言
`\bqdl\b(?!\s+--debug|\s+--dry-run)` 替换 `qdl` 词，**幂等**（重复调用不会叠加）。

`--dry-run` 是**唯一能在不接设备的情况下验证整条命令拼装是否正确**的手段 ——
这也是 QDL 页「模拟刷写」开关只接 `--dry-run`、**不接 `--debug`** 的原因。

---

## 四、页面：`QdlActivity`

继承 `FlashConsoleActivity`，是一个竖屏独立页（自带返回箭头 + 大标题 + 输出区）。

### 4.1 界面结构（两张 Miuix 卡片，共 11 行）

**卡片一：引导与参数**

| 行 | id | 控件 |
| --- | --- | --- |
| 选择引导文件 | `btnQdlPickProg` + `tvQdlProgSummary` | 选 `prog.mbn`（请求码 `REQ_PICK_PROG=9401`） |
| 存储类型 | `btnQdlStorage` + `tvQdlStorageSummary` | 5 值单选，默认 `UFS` |
| 欧加授权 | `btnQdlAuth` + `tvQdlAuthSummary` | 3 档单选，默认 `OFF` |
| 签名文件 | `btnQdlPickSign` + `tvQdlSignSummary` | `REQ_PICK_SIGN=9402`，**仅 VIP 档可见** |
| 摘要文件 | `btnQdlPickDigest` + `tvQdlDigestSummary` | `REQ_PICK_DIGEST=9403`，**仅 VIP 档可见** |
| 模拟刷写 | `swQdlDryRun` | 追加 `--dry-run` |

**卡片二：分区表与槽位**

| 行 | id | 对应子命令 |
| --- | --- | --- |
| 识别分区表 | `btnQdlRgpt` + `tvQdlRgptSummary` | `rgpt` |
| 识别设备启动槽位 | `btnQdlDetectSlot` + `tvQdlSlotSummary` | `get_slot` |
| 切换至 A | `btnQdlSwitchA` | `set_slot a` |
| 切换至 B | `btnQdlSwitchB` | `set_slot b` |
| 复位设备 | `btnQdlReset` | `reset` |

> 文案**按工具的实际输出写定**
> （`qdl_rgpt_title` / `qdl_select_programmer` / `qdl_storage_type` /
> `qdl_oplus_auth_*` / `qdl_ab_*` / `qdl_reset_device` / `qdl_dry_run*` /
> `qdl_hotplug_warning` / `qdl_slot_fail`）。

### 4.2 三个有意决定

**① 授权档位由用户选，不做自动判断。**

既有工具靠**引导库**（品牌 + `Kinds` 里的 sign / digest 文件）自动判定档位。
本项目**没有搬引导库**（`Loader.7z`，16MB / 265 机型 / 403 文件），
所以宁可让用户手选，也**不替用户猜** —— 猜错会让 `rgpt` 直接失败。

**② 「模拟刷写」只接 `--dry-run`，不接 `--debug`。** 理由见 3.6。

**③ 特意加了「复位设备」入口。**

`QdlCommands` 里**所有**子命令都带 `--skip-reset`（沿用既有工具），
意味着 **qdl 干完不会自动复位设备**。
没有这个入口，用户会**卡在 9008 模式，只能拔线重插**。

### 4.3 结果回填

| 动作 | 解析 | 回填 |
| --- | --- | --- |
| 识别分区表 | `countSavedLuns(output)` | 「已保存 N 个 LUN 的分区表 · `<OUTPUT_DIR>`」 |
| 识别启动槽位 | `parseActiveSlot(output)` | 「当前槽位：A / B」；解析失败显示 `SLOT_PARSE_FAILED` |

两者都用基类的 `confirmThenRun(..., CommandCallback)` 重载拿**本次输出**
（不能拿跨命令累积的 `consoleBuffer`）。

### 4.4 其它实现点

| 项 | 值 / 行为 |
| --- | --- |
| `AUTH_TIERS` | `{OFF, TOKEN, VIP}` |
| `SLOT_PARSE_FAILED` | `"无法获取活跃槽位（qdl get_slot 解析失败）"` |
| `NO_PROG_SELECTED` | `"请先点「选择引导文件」"` |
| `requireProg()` / `requireVipFiles()` | 前置校验（VIP 档必须先选 Sign + Digest） |
| `warnIfUnsupported()` | 非 64 位 ARM 时弹框说明后 `finish()` |
| `busyViews()` | 返回 11 个控件 |
| `logFileTag()` | `"qdl"` |
| `scrollToBottom()` | 覆写为滚 `scrollQdl` |

两张卡片都带 `app:strokeColor="@color/ios_separator"` + `app:strokeWidth="1dp"` 描边
—— 否则在 AMOLED 模式下卡片（纯黑）与页面背景（纯黑）会糊成一片。

---

## 五、打包与 ABI 约束

`bin/qdl` 是 **Android 动态链接**的 ELF64（`PT_INTERP=/system/bin/linker64`），
`DT_NEEDED` 只有 `libm.so` / `libc.so` / `libdl.so` —— **三个系统库**，
所以工具链里**不需要带任何 `.so`**。

| 项 | 值 |
| --- | --- |
| 文件大小 | 5,668,288 字节（压缩后约 2.14MB） |
| `machine` | `0xb7`（aarch64） |
| `md5` | `3ab164cfbae32eae8f1a0ff8c37900bb` |

`FlashToolchain.isQdlSupported()` 检查 `Build.SUPPORTED_64_BIT_ABIS` 是否含
`arm64-v8a`。本应用**未配置 `abiFilters`**，所以在非 64 位 ARM 设备上
`QdlActivity` 会先弹框说明、再 `finish()`，而不是让用户面对 `Exec format error`。

> 打包改动后**必须升 `FlashToolchain.TOOLCHAIN_VERSION`**，否则已装机用户
> 解压不到 `bin/qdl`。

---

## 六、还没做的部分

| 项 | 状态 |
| --- | --- |
| `owrite` / `oread` / `oerase` 系列 | 命令模板**已就绪**，**无调用方**（页面只做了「读」不做「写」） |
| `detectEdl()` | 无调用方（页面靠 `FlashDevice` 的 USB 列表判断） |
| `killResidual()` | 无调用方（`FlashRuntime.cancel()` 内部有另一套清理） |
| `reboot(...)` | 无调用方 |
| `rgptThenSetSlot(...)` | 无调用方（页面把「识别」与「切换槽位」做成两个独立动作） |
| 引导库 `Loader.7z` | **未搬**（16MB / 265 机型 / 403 文件） |
| `list` / `ramdump` / `sha256` | **刻意没写** —— 没有调用证据就不加 API |

> `qdl list` 与 `ramdump` 是刻意不写的：它们没有对应的使用场景，
> 加进来只会增加没人维护的表面。

---

## 七、待真机验证项（截至 2026-10-12）

代码到「编译通过 + 离线断言通过」为止，**下面这些只能上真机验**：

1. **工具链是否成功换到 v3** —— 进刷机页后 `files/flash_tools/bin/qdl` 应存在。
   旧版解压过的用户若不换版会缺这个文件。
2. **qdl 能不能在本机跑起来** —— 这是**唯一没法离线验的一环**
   （aarch64 动态链接 ELF，本机能否 `exec` 只有真机能答）。建议先开「模拟刷写」跑一次 `rgpt`。
3. **「识别设备启动槽位」的摘要是否变成「当前槽位：A/B」** ——
   若显示 `SLOT_PARSE_FAILED` 那句，说明槽位正则要按真实输出再调一次。
   **这是唯一没有真机样本的解析逻辑。**
4. **「识别分区表」输出**是否出现 `Reading GPT tables from all LUNs (0-5)` 与
   逐 LUN 的 `GPT saved to`；`/storage/emulated/0/Aurora/qdl/` 下是否真出
   `gpt_main*.bin` + `partitions.txt`。
5. **「复位设备」**能否让设备退出 9008。
6. **AMOLED 模式下两张卡片的 1dp 描边是否分得开。**
