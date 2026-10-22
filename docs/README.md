# Aurora 项目文档

> 本目录是 AuroraApp（`com.aurora.app`）的**长期技术文档**。
> 这里的文件**描述当前代码的真实状态**，随代码演进同步更新。
> 某一次性的分析快照在 [`archive/`](archive/)，那里的数字会过期，只在追溯历史决策时看。

---

## 文档清单

| 文档 | 回答什么问题 | 什么时候看 |
| --- | --- | --- |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | 项目长什么样？分了几层？启动链路怎么走？四个 tab 是怎么拼起来的？ | **第一次接触代码时先读这份** |
| [`DEVELOPMENT.md`](DEVELOPMENT.md) | 怎么编译？有哪些不能碰的约定？踩过哪些坑？改完怎么验证？ | 动手改代码之前 |
| [`FEATURES.md`](FEATURES.md) | 现在到底有哪些功能？每个页面是干嘛的？ | 想知道「有没有做 X」「这个页面在哪」时 |
| [`subsystems/FLASH.md`](subsystems/FLASH.md) | 刷机链路怎么设计的？命令怎么发出去的？ | 改刷机相关功能时 |
| [`subsystems/QDL.md`](subsystems/QDL.md) | 高通 9008 深刷是怎么实现的？ | 改 QDL 页或接写入类命令时 |
| [`subsystems/THEME.md`](subsystems/THEME.md) | 主题/强调色/AMOLED 是怎么算出来又套上去的？ | 改配色、加语义色、调字号时 |
| [`subsystems/MONITOR.md`](subsystems/MONITOR.md) | 监视悬浮窗的窗口体系与采集器怎么组织的？ | 加监视指标、调悬浮窗交互时 |
| [`subsystems/SYSTEM_DATA.md`](subsystems/SYSTEM_DATA.md) | 为什么某些指标读不到？Android 各版本限制了哪些路径？ | 遇到「数据全是 0 / 显示 --」时 |
| [`subsystems/UPDATE.md`](subsystems/UPDATE.md) | 自动更新怎么检查、下载、校验、装上？发版流水线有哪些约定？ | 改更新逻辑、发版、排查「检查更新失败」时 |

---

## 三条推荐阅读路径

**① 新人上手（想快速读懂整体）**
`ARCHITECTURE.md` → `FEATURES.md` → `DEVELOPMENT.md`

**② 准备改代码（怕踩雷）**
`DEVELOPMENT.md`（尤其「铁律」与「踩坑」两节）→ 对应子系统的文档 → `ARCHITECTURE.md` 的「关键机制」一节

**③ 排查线上问题（某个功能不对）**
先看 `FEATURES.md` 定位到页面 → 再进对应子系统文档 → 如果是「数据不对」先查 `SYSTEM_DATA.md`

---

## 维护约定

1. **文档与代码同一次改动内更新**。改了架构性的事实（分层、页面顺序、工具链版本号、权限边界）就必须改这里，
   否则文档比代码更危险。
2. **只写已经过核对的结论**。类名、行数、常量值、id 名一律从源码/产物里查证后再写，
   不从记忆或推测落笔。
3. **写「为什么」比写「是什么」值钱**。代码本身能说明它做了什么，
   文档的价值在于记下「为什么必须这么写」「不这么写会怎样」。
4. **不写会过期的数字**。行数、APK 体积这类每天在变的量，
   只在与「体积/规模」结论直接相关时才写，并注明测量时间与测量方法。

---

## 相关但不属于本目录的文档

| 位置 | 内容 |
| --- | --- |
| [`../README.md`](../README.md) | 面向使用者的项目介绍：功能概述、构建方式、协议与致谢 |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | 环境要求、代码约定、提交规范、PR 流程 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 版本变更记录 |
| [`../SECURITY.md`](../SECURITY.md) | 安全漏洞的报告范围与渠道 |
| [`archive/`](archive/) | 一次性分析快照（2026-08-29 的代码评审与 vtools 对比），**数字已过期** |
| [`../aurora_box_module/README.md`](../aurora_box_module/README.md) | Aurora 核心扩展模块（Magisk / KernelSU）的说明 |
| [`../CODE_OF_CONDUCT.md`](../CODE_OF_CONDUCT.md) / [`../LICENSE`](../LICENSE) | 社区准则与 GPL-3.0 协议 |
