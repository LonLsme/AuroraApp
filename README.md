# Aurora（极光）

一款面向高级玩家 / Root 用户的 **Android 玩机工具箱**，在 [violet_Box（紫罗兰Box）](https://github.com/Smart-Paocai/violet_Box)（GPL-3.0）基础上二次开发，并融入 Aurora Design System（Liquid Glass 液态玻璃设计语言）。

> Performance. Beautifully Controlled. —— 掌控每一瓦性能。

## 功能

- **Root 检测**：Magisk / KernelSU / APatch 等多方案检测
- **SUSFS 配置**：SUSFS 开关管理
- **Tricky Store**：配置、应用列表、Hash、Security Patch
- **隐藏应用**：隐藏应用列表配置
- **内核伪装 / 全局设备伪装 / 设备 ID 修改**
- **模块管理 / 模块备份**（含 Magisk 核心扩展模块 `aurora_box_module`）
- **分区管理 / SELinux 管理**
- **字体库备份 / 应用管理（备份、冻结、卸载）**
- **Play 完整性检测 / Payload 工具**
- 液态玻璃底部导航（Bubble Morph + 阻尼拖拽 + iOS 风格毛玻璃）

## 构建

```bash
# 需要 Android SDK（API 36）
# 1. 在项目根目录创建 local.properties：
#    sdk.dir=E\:\\DEV\\Android\\Sdk
# 2. 构建
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 技术栈

- Java + Kotlin（Compose 用于底部导航等组件）
- Material 3 + 自研 iOS 风格组件
- libsu / Rootbeer / OkHttp / commons-compress

## 致谢

- [violet_Box](https://github.com/Smart-Paocai/violet_Box)（GPL-3.0）—— 上游开源项目
- 本仓库保持 GPL-3.0 协议，修改代码请遵守协议并保留上游版权声明