# 时界自限制功能实施计划

> **For agentic workers:** 按任务清单逐项执行；每项先补测试，再做最小实现，并在提交前运行完整验证。

**目标：** 让时界自身出现在“受限应用”列表中，并在规则生效时仅通过无障碍返回桌面，同时保持 Shizuku 永不暂停时界。

**架构：** 将“可由选择器展示的应用”与“系统暂停保护包”分开处理。选择器不再过滤当前应用包；无障碍窗口解析允许自身参与 `VisibleWindowBlockGate`；Shizuku 保护集合和远端包名校验保持不变。

**技术栈：** Kotlin、Android PackageManager、AccessibilityService、Room/Flow、JUnit 4、Gradle Wrapper。

---

## 文件结构与影响范围

- 修改 `app/src/main/java/com/cwenhe/timefence/apps/InstalledAppRepository.kt`：只过滤关键系统包，不过滤时界自身。
- 修改 `app/src/main/java/com/cwenhe/timefence/enforcement/BlockAccessibilityService.kt`：允许自身包进入可见窗口候选；保留服务内部事件来源的安全过滤，避免无障碍服务自身事件造成循环。
- 修改 `app/src/test/java/com/cwenhe/timefence/apps/InstalledAppRepositoryTest.kt`：用可注入查询/保护依赖验证自身保留和关键包排除；若现有工程没有该测试夹具，使用最小 fake `ProtectedPackageResolver`/PackageManager 边界，不引入新依赖。
- 修改 `app/src/test/java/com/cwenhe/timefence/enforcement/VisibleWindowBlockGateTest.kt`：覆盖自身包命中、成功返回桌面后去重、规则移除后重新允许处理。
- 修改 `app/src/test/java/com/cwenhe/timefence/suspension/ProtectedPackagePolicyTest.kt`：确认候选集合含自身时仍被系统暂停保护集合过滤。
- 修改 `app/src/test/java/com/cwenhe/timefence/suspension/PackageNameValidatorTest.kt`：保留并明确自身不能进入暂停命令的回归断言。

不修改 `ProtectedPackageResolver.kt`、`PackageNameValidator.kt`、`SuspendUserService.kt` 和 `ShizukuSuspendGateway.kt` 的安全逻辑；只用测试证明它们仍拒绝暂停自身。

## 任务 1：候选应用列表允许自身

**文件：** `InstalledAppRepository.kt` 及对应测试。

- [x] 写失败测试：给定桌面入口包含时界、普通应用和设置包，加载结果包含时界和普通应用，但不包含设置包。
- [x] 运行定向测试，确认当前实现因 `protectedPackageResolver.resolve()` 包含自身而失败。
- [x] 实现最小改动：从选择器排除集合中移除当前应用包，仅保留关键系统包；不要硬编码一个不一定有启动入口的自身条目。
- [x] 重新运行定向测试，确认自身保留且关键包仍排除。

## 任务 2：无障碍服务允许拦截自身前台窗口

**文件：** `BlockAccessibilityService.kt`、`VisibleWindowBlockGateTest.kt`。

- [x] 写失败测试：将 `com.cwenhe.timefence` 作为活动窗口和阻止包传入 Gate，首次调用返回自身窗口，标记成功后立即调用不重复返回，规则移除后清理状态。
- [x] 运行测试确认现有行为/辅助边界不足。
- [x] 修改 `resolveActiveWindows()`：不再无条件过滤 `this.packageName`；保留仅针对服务事件跟踪和系统不可交互状态的已有保护。
- [x] 确认 `processVisibleWindows()` 仍复用现有返回桌面、反馈、退避逻辑，不新增第二套状态机。
- [x] 运行无障碍相关定向测试和现有 Gate 全量测试。

## 任务 3：Shizuku 自身保护回归

**文件：** `ProtectedPackagePolicyTest.kt`、`PackageNameValidatorTest.kt`。

- [x] 添加断言：候选集合同时含自身和普通包时，暂停目标只保留普通包。
- [x] 添加断言：`PackageNameValidator.canSuspend("com.cwenhe.timefence")` 为 `false`。
- [x] 运行暂停模块测试，确认无自身 `suspend` 命令路径。

## 任务 4：全量验证与交付检查

- [x] 运行 `./gradlew testDebugUnitTest`，记录失败测试和总结果。
- [x] 运行 `./gradlew lintDebug`，确认无新增 lint 错误。
- [x] 运行 `./gradlew assembleDebug`，确认 Debug APK 可编译。
- [x] 运行 `git diff --check`，并检查新增/修改函数附近的中文职责注释。
- [x] 检查需求矩阵：自身可选、前台返回桌面、规则结束恢复、Shizuku 不暂停自身、普通包回归、权限降级均有测试或明确真机未验证项。

## 任务 5：提交与推送

- [ ] 提交单一逻辑变更：`feat(timefence): 支持限制时界自身前台使用`，正文说明只返回桌面且保留 Shizuku 安全保护。
- [ ] 用 `git show --check` 和状态检查确认提交完整、工作树干净。
- [ ] 推送 `develop` 到 `origin/develop`；若远程没有该分支，使用 `git push -u origin develop` 创建并建立跟踪。
- [ ] 推送后读取 `git status --short --branch` 和远程跟踪信息，报告 commit、分支和验证结果。
