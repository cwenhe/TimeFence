# 时界 Shizuku 系统暂停模式实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有无障碍保护之上增加可选的 Shizuku 系统暂停模式，使规则内应用停止已有界面并无法再次启动，同时提供关键包保护和可靠恢复入口。

**Architecture:** 使用 Shizuku 13.1.5 UserService 在 shell/root 进程执行单包 `pm suspend/unsuspend`。应用进程中的 `SystemSuspendController` 串行协调规则目标、关键包策略、系统实际状态与持久化恢复责任；Binder 不可用时停止新增暂停，但无障碍服务继续工作。

**Tech Stack:** Kotlin 2.0.21、Android Gradle Plugin 8.7.3、Android API 35、Jetpack Compose Material 3、Kotlin Coroutines 1.9.0、Shizuku API/Provider 13.1.5、AIDL、JUnit 4。

---

## 文件结构

```text
app/src/main/aidl/com/cwenhe/timefence/suspension/
  ISuspendUserService.aidl                 # shell/root UserService IPC
app/src/main/java/com/cwenhe/timefence/
  apps/
    ProtectedPackageResolver.kt            # 动态关键包集合
    InstalledAppRepository.kt              # 复用关键包过滤
  suspension/
    PackageNameValidator.kt                # 远端命令参数白名单
    SuspendModels.kt                       # 网关、模式和操作状态
    SystemSuspendSettingsStore.kt          # 模式和恢复责任持久化
    PackageSuspensionInspector.kt           # Android 当前暂停状态适配
    ShizukuSuspendGateway.kt                # Binder、授权和 UserService 连接
    SuspendUserService.kt                   # 受控执行 pm 命令
    SystemSuspendController.kt              # 规则目标校正和紧急恢复
  ui/settings/SettingsScreen.kt            # 高级拦截设置区
```

## 任务 1：固定依赖和远端命令边界

**文件：**

- 修改：`app/build.gradle.kts`
- 修改：`app/src/main/AndroidManifest.xml`
- 修改：`app/proguard-rules.pro`
- 新建：`app/src/main/aidl/com/cwenhe/timefence/suspension/ISuspendUserService.aidl`
- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/PackageNameValidator.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/SuspendUserService.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/suspension/PackageNameValidatorTest.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/suspension/PmCommandResultTest.kt`

- [ ] **步骤 1：添加包名和命令结果失败测试**

测试合法包名 `com.example.app`，并拒绝空字符串、以点开头、空格、斜杠、分号和换行；命令结果测试覆盖 exit 0、非零退出、超时和异常的中文摘要。

- [ ] **步骤 2：运行定向测试确认红灯**

运行：`./gradlew :app:testDebugUnitTest --tests '*PackageNameValidatorTest' --tests '*PmCommandResultTest'`

预期：测试因目标类型尚不存在而编译失败。

- [ ] **步骤 3：接入 Shizuku 与 AIDL**

启用 `buildFeatures.aidl = true`，加入固定版本依赖 `api:13.1.5` 和 `provider:13.1.5`。Manifest 声明 `${applicationId}.shizuku` Provider，并在 `<queries>` 中声明 `moe.shizuku.privileged.api`。

AIDL 固定为：

```aidl
interface ISuspendUserService {
    String setPackageSuspended(String packageName, int userId, boolean suspended);
    void destroy() = 16777114;
}
```

- [ ] **步骤 4：实现受控 pm 执行**

`SuspendUserService` 只接受严格包名和非负用户 ID，使用参数数组调用 `/system/bin/pm suspend|unsuspend --user <id> <package>`，等待最多 10 秒并限制返回文本。所有新增构造函数和方法添加中文职责注释。

- [ ] **步骤 5：运行命令边界测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*PackageNameValidatorTest' --tests '*PmCommandResultTest'`

预期：全部通过。

## 任务 2：实现关键包保护和持久化状态

**文件：**

- 新建：`app/src/main/java/com/cwenhe/timefence/apps/ProtectedPackageResolver.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/apps/InstalledAppRepository.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/SuspendModels.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/SystemSuspendSettingsStore.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/PackageSuspensionInspector.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/suspension/ProtectedPackagePolicyTest.kt`

- [ ] **步骤 1：先写关键包策略测试**

测试静态关键包、动态桌面、电话和输入法始终从候选集合移除，普通包保留；无效包名也必须移除。

- [ ] **步骤 2：运行测试确认缺少策略实现**

运行：`./gradlew :app:testDebugUnitTest --tests '*ProtectedPackagePolicyTest'`

预期：测试编译失败。

- [ ] **步骤 3：实现动态解析和选择器复用**

解析时界、Shizuku、Android、System UI、设置、权限控制器、包安装器、所有 HOME Activity、默认拨号器和输入法包；`InstalledAppRepository` 使用该集合过滤应用选择列表。

- [ ] **步骤 4：实现同步恢复责任存储**

SharedPreferences 默认关闭高级模式、管理集合为空；认领、撤销和模式写入使用 `commit()`，并同步更新 `StateFlow`。`PackageSuspensionInspector` 使用 `PackageManager.isPackageSuspended`，查询失败按未知处理而不是擅自恢复。

- [ ] **步骤 5：运行策略及现有应用测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*ProtectedPackagePolicyTest'`

预期：全部通过。

## 任务 3：以测试驱动实现暂停协调器

**文件：**

- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/SystemSuspendController.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/suspension/SystemSuspendControllerTest.kt`

- [ ] **步骤 1：建立网关、存储和 Inspector 假实现**

测试 helper 提供可控的 Shizuku 状态、实际暂停集合、命令结果和持久化模式；每个 helper 添加中文职责注释。

- [ ] **步骤 2：写协调行为失败测试**

覆盖：首次暂停、过期恢复、外部已暂停不认领、活动包被外部恢复后重试、关键包过滤、服务离线不新增、部分失败保留、全部恢复后才关闭模式。

- [ ] **步骤 3：运行协调器测试确认红灯**

运行：`./gradlew :app:testDebugUnitTest --tests '*SystemSuspendControllerTest'`

预期：因协调器不存在而编译失败。

- [ ] **步骤 4：实现串行和持久化顺序**

使用 `Mutex` 串行处理。每轮先恢复 `managed - desired`，再暂停安全的 `desired`；新暂停先认领再调用网关，失败时撤销；恢复成功后才移除认领。状态提供模式、Shizuku 阶段、后端、管理数量、忙碌标记和最后错误。

- [ ] **步骤 5：运行协调器测试**

运行：`./gradlew :app:testDebugUnitTest --tests '*SystemSuspendControllerTest'`

预期：全部通过且无协程泄漏。

## 任务 4：接入 Shizuku 生命周期和系统校正信号

**文件：**

- 新建：`app/src/main/java/com/cwenhe/timefence/suspension/ShizukuSuspendGateway.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/core/AppContainer.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/TimeFenceApplication.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/MainActivity.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/BoundaryAlarmReceiver.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/SystemChangeReceiver.kt`

- [ ] **步骤 1：实现 Binder 状态机**

监听 Binder received/dead 和授权结果；区分未运行、版本过旧、未授权、连接中、ADB 就绪、Root 就绪和错误。仅在授权后绑定非 daemon UserService，远端异常立即清空代理并进入可恢复状态。

- [ ] **步骤 2：将规则目标接入协调器**

`AppContainer` 从现有内存 `ruleSnapshot`、`CalendarSnapshot` 和 `ScheduleEvaluator.evaluateActive` 提供目标集合。规则或日历变化、网关恢复和模式变化写入合并信号。

- [ ] **步骤 3：补齐边界与系统广播**

边界闹钟、开机、应用升级、时间、时区和日期变化在重排闹钟的同一后台任务中调用同步校正；失败写短日志但不能阻止通知和无障碍边界检查。

- [ ] **步骤 4：处理前后台刷新**

应用启动时开始监听；`MainActivity.onResume` 同时刷新普通权限和 Shizuku 状态，覆盖用户从授权页返回的场景。

- [ ] **步骤 5：编译主程序**

运行：`./gradlew :app:compileDebugKotlin`

预期：AIDL Stub、Shizuku 调用和 Kotlin 主源码编译通过。

## 任务 5：完成高级拦截设置界面

**文件：**

- 修改：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceViewModel.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceApp.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/permissions/SystemSettingsNavigator.kt`
- 测试：`app/src/androidTest/java/com/cwenhe/timefence/ui/SettingsScreenTest.kt`

- [ ] **步骤 1：写设置页状态测试**

覆盖服务未运行、未授权、ADB/Root 就绪、管理数量、启用确认和解除全部确认；回调测试不得实际调用 Shizuku。

- [ ] **步骤 2：实现 ViewModel 操作**

将协调器状态合并进 `TimeFenceUiState`，提供刷新/授权、打开 Shizuku、切换模式和解除全部方法；失败进入现有 Snackbar，并保留设置区持久错误。

- [ ] **步骤 3：实现 Material 3 设置区**

使用开关表达模式、状态图标行表达 Shizuku、带恢复图标的文字按钮表达紧急命令。启用和解除操作均显示确认对话框，所有按钮在忙碌时禁用且文本不溢出。

- [ ] **步骤 4：生成界面测试 APK**

运行：`./gradlew :app:assembleDebugAndroidTest`

预期：Compose 界面测试源码编译并生成 APK。

## 任务 6：更新文档并完成交付验证

**文件：**

- 修改：`README.md`
- 修改：`docs/index.md`
- 修改：`docs/diagnostics.md`
- 修改：`docs/honor-setup.md`
- 修改：`app/src/main/res/values/strings.xml`

- [ ] **步骤 1：补充安装和恢复说明**

说明 Shizuku 是可选高级能力、Android 11+ 手机端启动、Android 10 及以下电脑要求、非 Root 重启后需重启服务、暂停可能持续以及一键恢复流程。

- [ ] **步骤 2：检查新增函数注释**

检查 Git diff 中所有新增函数、构造函数、回调和测试 helper，确保紧邻中文职责注释，且已有函数行为变化时同步更新说明。

- [ ] **步骤 3：运行完整自动化验证**

运行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
./gradlew lintRelease assembleRelease
python3 -m mkdocs build --strict -d /tmp/timefence-docs
git diff --check
```

预期：命令全部以 0 退出，Debug APK、AndroidTest APK 和未签名或本地签名 Release APK 均生成。

- [ ] **步骤 4：记录真机边界**

若当前未连接具备 Shizuku 的荣耀设备，交付说明明确列出系统暂停、重启恢复和小窗场景尚待真机验收。

- [ ] **步骤 5：提交、推送与发版**

使用中文 Conventional Commits 分离设计、核心实现、界面文档提交，推送 `main`。在所有验证通过后创建下一个 SemVer 标签触发签名 Release，不覆盖 `v1.1.0`；等待 GitHub Actions 完成并独立校验 APK SHA-256 与签名证书。
