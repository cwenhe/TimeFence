# 时界项目协作规则

## 项目边界

- 本项目是普通 Android 应用，不依赖 APS/SCH 产品。
- 技术栈固定为 Kotlin、Jetpack Compose、Material 3、Room 和 Kotlin Coroutines，单模块入口为 `app`。
- 项目仅处理本地定时应用限制；网络只用于读取项目维护的公开日历，不增加账号、云同步、使用统计、广告或付费能力。
- 不声称强制结束目标应用进程；默认核心行为是通过 `AccessibilityService` 返回桌面并持续拦截。可选 Shizuku 模式只调用系统包暂停能力，并明确说明其授权、兼容性和恢复边界。

## 环境与命令

- 使用 JDK 17、Gradle Wrapper、`compileSdk 35` 和 `targetSdk 35`。
- 完整本地验证命令：`./gradlew testDebugUnitTest lintDebug assembleDebug`。
- 涉及界面测试时追加：`./gradlew assembleDebugAndroidTest`。
- 文档验证命令：`python3 -m mkdocs build --strict -d /tmp/timefence-docs`。
- 不提交 `local.properties`、密钥、APK、AAB、构建目录、日志或本地 SDK 路径。

## 实现约束

- 规则区间采用左闭右开语义；结束时间早于开始时间表示跨午夜，并归属于开始日。
- 拦截必须同时覆盖窗口事件和精确时间边界，不能只依赖应用重新进入前台。
- 规则、开机、日期、时间、时区和权限变化后，需要重新计算当前状态及下一条边界。
- 不申请 `QUERY_ALL_PACKAGES` 或使用情况访问权限；`INTERNET` 只允许更新固定 HTTPS 日历，应用列表通过桌面启动 Intent 查询。
- 不上传规则、包名、自定义提示、屏幕内容或使用记录；远程日历响应必须严格校验并在 Room 中原子替换。
- 无障碍服务不得读取、记录或上传页面节点文字与输入内容。
- 新增或修改函数、构造函数、覆写方法、回调和测试辅助函数时，添加简洁的中文 KDoc 或职责注释。
- 文档、面向用户的文本和提交说明默认使用简体中文；技术标识和协议关键字保持原文。

## 测试要求

- 时间计算测试覆盖同日、跨午夜、跨周、边界包含关系、禁用规则、时区和夏令时。
- 数据测试覆盖星期位掩码、应用集合和规则状态的无损转换。
- 界面测试覆盖规则必填校验、权限降级和保存状态。
- 交付前至少运行单元测试、Android Lint 和 Debug APK 构建。
- 编译成功不能替代荣耀真机验收；未连接设备时必须明确记录未验证项。

## 文档与发版

- 用户文档入口是 `README.md` 和 `docs/index.md`；设计档案保存在 `docs/superpowers/`，不要与用户操作指南混写。
- MkDocs 依赖必须在 `requirements-docs.txt` 中使用确定版本。
- `v<主版本>.<次版本>.<修订版本>` 标签触发签名 APK 发布，发布签名只能由 GitHub Actions Secrets 注入。
- 发布前保留签名密钥的离线备份；签名密钥丢失后，已安装用户无法直接升级到使用新密钥签名的 APK。
