# 时界性能、日历与提示实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留旧规则行为的前提下，消除小窗场景的无障碍事件放大，增加中国法定工作日和 A 股交易日规则，并提供可自定义的文字提示与可选 TTS 播报。

**Architecture:** 规则和日历通过 Room Flow 维护进程内只读快照；`ScheduleEvaluator` 使用快照计算当前状态和下一边界。无障碍服务使用单消费者合并队列和窗口去重门，热路径不读 Room。日历以项目维护的版本化 JSON 为 HTTPS 更新源，APK raw 资源和 Room 缓存作为离线兜底；拦截反馈由同一个渲染结果驱动浮层、通知和 TTS。

**Tech Stack:** Kotlin 2.0.21、Android Gradle Plugin 8.7.3、Gradle 8.9、Android API 35、Jetpack Compose Material 3、Room 2.6.1、Kotlin Coroutines 1.9.0、`kotlinx-serialization-json` 1.7.3、WorkManager 2.10.0、JUnit 4。

---

## 文件结构

```text
data/calendar/source/2026.json                 # 官方公告输入与来源地址
data/calendar/zh-CN.json                      # 远程更新文件
tools/calendar/generate_calendar.py            # 确定性生成器，断言 248/242
app/src/main/res/raw/zh_cn_calendar.json      # APK 内置同一份日历
app/src/main/java/com/cwenhe/timefence/
  calendar/
    CalendarMode.kt                            # 每周、工作日、交易日枚举
    CalendarSnapshot.kt                        # 内存日历查询与未知态
    CalendarDocument.kt                        # JSON 领域文档
    CalendarDocumentParser.kt                  # 严格文档校验
    CalendarRemoteDataSource.kt                # HTTPS、ETag、超时和大小限制
    CalendarRepository.kt                      # 内置资源、Room、同步与状态
    CalendarSyncWorker.kt                      # WorkManager 周期同步
  data/local/
    CalendarEntities.kt                        # 日历逐日与元数据实体
    CalendarDao.kt                             # 日历查询和原子替换
    TimeFenceDatabase.kt                       # 版本 2 迁移
  enforcement/
    ConflatedSignalProcessor.kt                # 容量为 1 的单消费者队列
    VisibleWindowBlockGate.kt                  # 可见窗口 HOME 去重
    BlockFeedback.kt                            # 规则命中后的统一反馈
    BlockFeedbackFormatter.kt                  # 占位符替换和长度校验
    BlockSpeechController.kt                   # TTS 生命周期与节流
```

## 任务 1：建立规则模式和 Room v1→v2 迁移

**文件：**

- 修改：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleRule.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/data/local/RuleEntities.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/data/ScheduleRepository.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/data/local/TimeFenceDatabase.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/data/local/CalendarEntities.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/data/local/CalendarDao.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/data/ScheduleRepositoryMapperTest.kt`
- 测试：`app/src/androidTest/java/com/cwenhe/timefence/data/TimeFenceDatabaseMigrationTest.kt`

- [ ] **步骤 1：先扩展领域模型和映射测试**

新增 `CalendarMode` 枚举：`WEEKLY`、`CN_STATUTORY_WORKDAY`、`CN_A_SHARE_TRADING_DAY`；`ScheduleRule` 增加 `calendarMode`、`notificationMessage`、`speakNotification`，默认值分别为 `WEEKLY`、空字符串和 `false`。映射测试断言这些字段与已有时间、星期、应用、启用和锁定字段完整往返。

- [ ] **步骤 2：运行映射测试确认缺少实现**

运行：`./gradlew :app:testDebugUnitTest --tests '*ScheduleRepositoryMapperTest'`

预期：新增字段相关断言失败，旧字段测试仍能编译或明确报告构造函数参数缺失。

- [ ] **步骤 3：实现实体字段和正式迁移**

`RuleEntity` 增加三个非空列；`TimeFenceDatabase` 版本改为 `2`，加入：

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE rules ADD COLUMN scheduleMode TEXT NOT NULL DEFAULT 'WEEKLY'")
        database.execSQL("ALTER TABLE rules ADD COLUMN notificationMessage TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE rules ADD COLUMN speakNotification INTEGER NOT NULL DEFAULT 0")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS calendar_days (" +
                "date TEXT NOT NULL PRIMARY KEY, " +
                "isStatutoryWorkday INTEGER NOT NULL, " +
                "isAShareTradingDay INTEGER NOT NULL, " +
                "sourceVersion INTEGER NOT NULL, " +
                "updatedAtEpochMillis INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS calendar_metadata (" +
                "id INTEGER NOT NULL PRIMARY KEY, " +
                "revision INTEGER NOT NULL, " +
                "locale TEXT NOT NULL, " +
                "coveredFrom TEXT NOT NULL, " +
                "coveredTo TEXT NOT NULL, " +
                "etag TEXT, " +
                "lastSuccessfulSyncAt INTEGER, " +
                "lastAttemptAt INTEGER, " +
                "lastError TEXT)",
        )
    }
}
```

实际 SQL 必须与 `CalendarEntities` 的列、主键和索引完全一致；数据库 builder 使用 `.addMigrations(MIGRATION_1_2)`，禁止 destructive migration。为迁移测试提供版本 1 fixture，断言旧规则默认 `WEEKLY`、空提示、关闭朗读且应用关联仍存在。

- [ ] **步骤 4：实现日历 DAO 的事务接口**

`CalendarDao` 提供 `observeDays()`、`getMetadata()` 和 `replaceDataset(days, metadata)`；替换方法在单个 Room 事务中删除旧日期、插入新日期并更新 metadata。`CalendarRepository` 尚未实现前，先让迁移和实体编译通过。

- [ ] **步骤 5：运行数据层验证**

运行：`./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest`

预期：单元测试通过，AndroidTest APK 成功生成；连接设备后再执行迁移仪器测试。

## 任务 2：生成并严格解析中国 2026 日历

**文件：**

- 新建：`data/calendar/source/2026.json`
- 新建：`tools/calendar/generate_calendar.py`
- 新建：`data/calendar/zh-CN.json`
- 新建：`app/src/main/res/raw/zh_cn_calendar.json`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarDocument.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarDocumentParser.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/calendar/CalendarDocumentParserTest.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/calendar/CalendarAssetTest.kt`

- [ ] **步骤 1：写入官方来源和输入数据**

`source/2026.json` 保存国务院、上交所和深交所公告 URL、2026 休假区间和调休上班日期。休假区间为 `01-01..01-03`、`02-15..02-23`、`04-04..04-06`、`05-01..05-05`、`06-19..06-21`、`09-25..09-27`、`10-01..10-07`；调休上班日为 `01-04`、`02-14`、`02-28`、`05-09`、`09-20`、`10-10`。

- [ ] **步骤 2：实现生成器并先验证红灯**

生成器使用标准库 `datetime` 和 `json` 展开全年连续日期：工作日为周一至周五减去休假日，再加调休日期；交易日为周一至周五且不在任一交易所休市区间。函数前添加中文职责注释，并断言 365 条日期、248 个工作日和 242 个交易日。运行：

```bash
python3 tools/calendar/generate_calendar.py
```

在输出文件不存在或计数实现不完整时预期失败。

- [ ] **步骤 3：生成远程与 APK 数据**

输出文档固定为 `schemaVersion=1`、`locale=zh-CN`、单调 `revision`、`generatedAt` 和 `years` 数组；2026 年必须含 365 条连续唯一日期，2027 不得伪造。生成后将 JSON 字节复制到 `data/calendar/zh-CN.json` 和 `res/raw/zh_cn_calendar.json`。

- [ ] **步骤 4：实现解析器和异常测试**

`CalendarDocumentParser` 拒绝错误 locale、旧 schema、回退 revision、重复/缺失日期、错误闰年天数、交易日落在周末或非工作日、超过 1 MiB 的响应。测试覆盖合法 2026、缺一天、重复一天、非法布尔值和未知年份。

- [ ] **步骤 5：运行日历单测**

运行：`./gradlew :app:testDebugUnitTest --tests '*Calendar*'`

预期：解析器和 2026 资源测试全部通过，并固定报告 `365/248/242`。

## 任务 3：实现 Room 日历缓存、HTTPS 更新和规则求值

**文件：**

- 修改：`app/build.gradle.kts`
- 修改：`app/src/main/AndroidManifest.xml`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarSnapshot.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarRemoteDataSource.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarRepository.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/calendar/CalendarSyncWorker.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleEvaluator.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleRule.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/calendar/CalendarRepositoryTest.kt`
- 修改：`app/src/test/java/com/cwenhe/timefence/rules/ScheduleEvaluatorTest.kt`

- [ ] **步骤 1：增加固定依赖和权限**

在 `app/build.gradle.kts` 增加 `kotlinx-serialization-json:1.7.3` 和 `work-runtime-ktx:2.10.0`；Manifest 增加 `android.permission.INTERNET`，`<queries>` 增加 `android.intent.action.TTS_SERVICE`。不增加 `QUERY_ALL_PACKAGES` 或使用情况权限。

- [ ] **步骤 2：实现快照三态**

`CalendarSnapshot` 提供 `WORKDAY`、`TRADING_DAY` 的 `MATCH`、`NO_MATCH`、`UNKNOWN` 查询；未知日期不激活对应规则。快照从内置资源初始化，再由 Room 成功同步结果替换。

- [ ] **步骤 3：实现 HTTPS 数据源和仓库**

固定访问 `https://raw.githubusercontent.com/cwenhe/TimeFence/main/data/calendar/zh-CN.json`，使用 `HttpsURLConnection`，连接和读取超时均为 10 秒，禁止重定向，保存并发送 `ETag`，接受 raw GitHub 的 `text/plain` 类型，按解压后的字节数限制 1 MiB。仅在完整解析和 revision 高于本地后，通过 Room 事务替换数据；HTTP 304、断网和非法响应保留旧快照。

- [ ] **步骤 4：实现 WorkManager 同步策略**

增加网络已连接约束的唯一周期任务，周期为 30 天；应用启动且最近成功同步超过 30 天时 enqueue 一次性任务；设置页“立即更新”复用唯一任务名，避免并发下载。Worker 测试只验证约束、重试和失败保留快照，不连接真实网络。

- [ ] **步骤 5：扩展求值器**

增加 `evaluateActive(now, rules, calendar)`，只求当前活动规则；原 `evaluate` 复用当前求值并在日历覆盖范围内寻找下一边界。每周规则不依赖日历，工作日和交易日使用开始日期的三态结果。测试覆盖 2026-01-04 工作日但非交易日、2026-01-05 二者皆为真、2026-01-01 二者皆为假、未知年份和跨午夜。

- [ ] **步骤 6：验证日历与规则**

运行：`./gradlew :app:testDebugUnitTest`

预期：原有星期、跨午夜、时区和 DST 测试保持通过，新增日历测试全部通过。

## 任务 4：优化无障碍小窗热路径

**文件：**

- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/ConflatedSignalProcessor.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/VisibleWindowBlockGate.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/core/AppContainer.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockAccessibilityService.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleEvaluator.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/enforcement/ConflatedSignalProcessorTest.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/enforcement/VisibleWindowBlockGateTest.kt`

- [ ] **步骤 1：先写合并队列和窗口门测试**

测试连续请求 1000 次时最大并发处理数为 1，处理挂起期间最多保留一个待处理信号；测试 HOME 成功后同一窗口不再返回候选，窗口消失后可再次拦截，HOME 失败时下一次仍可重试。

- [ ] **步骤 2：实现规则快照**

在 `AppContainer` 中将唯一 `observeRules()` 收集为 `StateFlow<RuleSnapshot>`，`TimeFenceViewModel`、保护通知和无障碍服务共用它。初始状态必须区分“尚未加载”和“数据库为空”，服务在未加载时不扫描窗口和执行 HOME。

- [ ] **步骤 3：实现单消费者处理器**

`ConflatedSignalProcessor` 内部使用 `Channel<Unit>(Channel.CONFLATED)` 和一个消费协程，`request()` 只发送信号；不把 `AccessibilityEvent` 对象放入队列。服务销毁时关闭处理器并取消作用域。

- [ ] **步骤 4：重写服务事件路径**

回调只复制包名、事件类型和可用的窗口变化位，然后请求处理器；不启动新协程、不读 Room。消费者读取一次规则快照、一次 `evaluateActive` 和一次可见窗口集合，将受限包集合与窗口集合求交。保留 `0/100/300/700ms` 边界补检，但每次只触发同一处理器。

- [ ] **步骤 5：过滤事件并去重 HOME**

`TYPE_WINDOW_STATE_CHANGED` 始终处理；`TYPE_WINDOWS_CHANGED` 只处理新增、移除、活动、焦点和画中画变化，厂商返回 0 时仍处理。窗口 ID 与包名作为身份，无 ID 时退化为包名。只有 HOME 返回 `true` 才标记已处理；活动规则消失或窗口离开时清理门状态。

- [ ] **步骤 6：验证性能单测和构建**

运行：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

预期：无障碍服务编译通过，性能测试证明热路径规则读取次数为 0；Lint 不报告协程、Manifest 或 API 级别问题。

## 任务 5：实现统一文字反馈、通知和 TTS

**文件：**

- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockFeedback.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockFeedbackFormatter.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockSpeechController.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockOverlay.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/notifications/ProtectionNotifier.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockAccessibilityService.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/enforcement/BlockFeedbackFormatterTest.kt`
- 测试：`app/src/test/java/com/cwenhe/timefence/enforcement/BlockSpeechControllerTest.kt`

- [ ] **步骤 1：先写文本格式化测试**

断言默认文本、`{rule}`、`{app}`、`{until}` 替换、空白裁剪、120 字符上限和未知占位符保留行为；不把自定义文本写入日志。

- [ ] **步骤 2：实现统一反馈模型**

`BlockFeedback` 保存规则 ID、包名、规则名、显示应用名、结束时间和渲染文本。沿用当前“结束时间剩余最长”的命中规则选择逻辑，浮层、事件通知和 TTS 必须接收同一实例。

- [ ] **步骤 3：实现事件通知和浮层**

保留低优先级常驻保护状态通知；新增拦截事件通知渠道，使用自定义文本和 `setOnlyAlertOnce`。通知权限不足时忽略通知异常；浮层显示同一文本和关闭按钮，四秒后自动消失。

- [ ] **步骤 4：实现 TTS 生命周期**

`BlockSpeechController` 异步初始化 `TextToSpeech`，设置跟随系统或中文普通话语言，使用 `QUEUE_FLUSH`，按规则 ID、包名和文本节流 10 秒；无引擎、语言不可用、初始化失败或服务销毁时只降级为文字反馈，并调用 `stop()`、`shutdown()`。

- [ ] **步骤 5：验证反馈**

运行：`./gradlew :app:testDebugUnitTest :app:lintDebug`

预期：占位符和节流测试通过，TTS 失败不会抛出到拦截主流程。

## 任务 6：完成 Compose 规则编辑和设置界面

**文件：**

- 修改：`app/src/main/java/com/cwenhe/timefence/ui/editor/RuleEditorScreen.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/components/RuleRow.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/RuleFormatting.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/dashboard/DashboardScreen.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceApp.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceViewModel.kt`
- 测试：`app/src/androidTest/java/com/cwenhe/timefence/ui/RuleEditorScreenTest.kt`

- [ ] **步骤 1：扩展 ViewModel 状态**

将 `CalendarStatus`、全局 TTS 开关和语言选择合并到 `TimeFenceUiState`；保存规则时传递 `calendarMode`、提示文本和朗读开关；规则流和日历快照只订阅一次。

- [ ] **步骤 2：实现重复方式控件**

编辑器使用互斥分段控件“每周 / 工作日 / 交易日”；每周显示七个星期按钮，另外两项显示当前日历覆盖范围和未知日期警告。旧规则打开时默认选中每周。

- [ ] **步骤 3：实现提示编辑和预览**

新增多行提示输入、字符计数、三个占位符快捷插入、使用首个已选应用的实时预览和规则级语音开关。空文本保存为默认值；保存按钮继续校验名称、时间、重复方式和应用。

- [ ] **步骤 4：实现设置页日历和语音区域**

显示日历版本、覆盖年份、最近同步时间、失败原因和立即更新按钮；显示全局语音开关、跟随系统/中文普通话/关闭选项和 TTS 系统设置入口。

- [ ] **步骤 5：补齐 Compose 回归测试**

新增测试覆盖三种重复方式切换、工作日/交易日保存、提示文本预览、空文本默认值、规则级语音开关和有效规则保存。运行：`./gradlew :app:assembleDebugAndroidTest`

## 任务 7：同步文档、完成全量验证并交付

**文件：**

- 修改：`README.md`
- 修改：`docs/diagnostics.md`
- 修改：`docs/release-guide.md`
- 修改：`data/calendar/zh-CN.json`
- 修改：`app/src/main/res/raw/zh_cn_calendar.json`

- [ ] **步骤 1：更新用户说明**

说明联网只用于日历、工作日包含调休、交易日为沪深共同开市日、断网使用缓存、2027 未发布前显示未知、TTS 可能由系统引擎处理，以及小窗目标延迟和荣耀真机验收边界。

- [ ] **步骤 2：运行完整验证**

运行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleDebugAndroidTest
python3 -m mkdocs build --strict -d /tmp/timefence-docs
```

预期：所有 Gradle 任务退出码为 0，Debug APK 和 AndroidTest APK 生成，MkDocs 严格构建成功。

- [ ] **步骤 3：检查新增函数注释和差异**

检查所有新增 Kotlin、Python 函数都有中文职责注释，运行 `git diff --check`，扫描密钥、日志中的提示文本和不必要的权限。

- [ ] **步骤 4：提交并推送**

将实现按“规则与日历”“性能拦截”“提示与界面”“文档与数据”分成逻辑提交，使用中文 Conventional Commit；验证通过后推送 `main` 到 `origin`。不创建新 Release Tag，除非用户另行要求发版。
