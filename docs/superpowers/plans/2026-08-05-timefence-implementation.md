# 时界 Android 应用实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 构建一个可安装的 Android 应用，在规则时间边界主动退出已位于前台的受限应用，并支持 GitHub Tag 自动生成可下载的签名 APK。

**架构：** 使用单 `app` 模块和手工依赖容器。Room 保存规则，纯 Kotlin 的 `ScheduleEvaluator` 负责时间计算，`AlarmManager` 与 `AccessibilityService` 组成时间触发和窗口触发两条拦截路径；Compose 只负责状态展示与规则编辑。GitHub Actions 分别执行持续集成、文档发布和版本 APK 发布。

**技术栈：** Kotlin 2.0.21、Android Gradle Plugin 8.7.3、Gradle 8.9、Android API 35、Jetpack Compose Material 3、Room 2.6.1、Kotlin Coroutines 1.9.0、JUnit 4、GitHub Actions。

---

## 文件结构

```text
timefence-android/
  .github/workflows/
    ci.yml                         # 单测、Lint、Debug APK
    docs.yml                       # MkDocs GitHub Pages
    release.yml                    # v* Tag 签名并发布 APK
  app/
    build.gradle.kts               # Android、Compose、Room、签名配置
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/com/cwenhe/timefence/
        TimeFenceApplication.kt    # 进程级依赖容器入口
        MainActivity.kt            # Compose 宿主与权限状态刷新
        core/AppContainer.kt       # 数据、调度和权限依赖装配
        data/local/
          RuleEntity.kt            # Room 规则与应用关联实体
          RuleDao.kt               # 规则流和事务写入接口
          TimeFenceDatabase.kt     # Room 数据库
        data/ScheduleRepository.kt # 数据实体与领域模型转换
        rules/
          ScheduleRule.kt          # 规则领域模型
          ScheduleEvaluator.kt     # 生效判断与下一边界计算
        apps/
          InstalledApp.kt
          InstalledAppRepository.kt
        enforcement/
          BoundaryAlarmScheduler.kt
          BoundaryAlarmReceiver.kt
          SystemChangeReceiver.kt
          BoundaryCheckStore.kt
          EnforcementBridge.kt
          BlockAccessibilityService.kt
          BlockOverlay.kt
        permissions/
          PermissionStatus.kt
          PermissionStatusRepository.kt
          SystemSettingsNavigator.kt
        ui/
          TimeFenceApp.kt
          TimeFenceTheme.kt
          TimeFenceViewModelFactory.kt
          dashboard/DashboardScreen.kt
          dashboard/DashboardViewModel.kt
          rules/RulesScreen.kt
          rules/RulesViewModel.kt
          editor/RuleEditorScreen.kt
          editor/RuleEditorViewModel.kt
          picker/AppPickerScreen.kt
          settings/SettingsScreen.kt
      res/
        drawable/ic_timefence.xml
        mipmap-anydpi-v26/ic_launcher.xml
        values/colors.xml
        values/strings.xml
        values/themes.xml
        xml/accessibility_service_config.xml
    src/test/java/com/cwenhe/timefence/
      rules/ScheduleEvaluatorTest.kt
      data/ScheduleRepositoryMapperTest.kt
    src/androidTest/java/com/cwenhe/timefence/
      ui/RuleEditorScreenTest.kt
  docs/
    index.md
    honor-setup.md
    release-guide.md
    diagnostics.md
  gradle/wrapper/
    gradle-wrapper.jar
    gradle-wrapper.properties
  AGENTS.md
  README.md
  mkdocs.yml
  requirements-docs.txt
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradlew
  gradlew.bat
```

所有新增函数、构造函数、覆写方法和测试辅助方法在声明处添加中文 KDoc 或职责注释。自动生成的 Gradle Wrapper 文件除外。

### 任务 1：建立可复现的 Android 工程

**文件：**
- 新建：`settings.gradle.kts`
- 新建：`build.gradle.kts`
- 新建：`gradle.properties`
- 新建：`app/build.gradle.kts`
- 新建：`app/proguard-rules.pro`
- 新建：`app/src/main/AndroidManifest.xml`
- 新建：`app/src/main/java/com/cwenhe/timefence/TimeFenceApplication.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/MainActivity.kt`
- 新建：`app/src/main/res/values/strings.xml`
- 新建：`app/src/main/res/values/themes.xml`
- 新建：`.gitignore`

- [ ] **步骤 1：写入固定版本的 Gradle 配置**

根插件固定为：

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
```

`app/build.gradle.kts` 使用 `namespace = "com.cwenhe.timefence"`、`compileSdk = 35`、`minSdk = 26`、`targetSdk = 35`，默认版本为 `0.1.0`/`1`。版本允许由 `TIMEFENCE_VERSION_NAME` 和 `TIMEFENCE_VERSION_CODE` 环境变量覆盖。

- [ ] **步骤 2：生成 Gradle Wrapper**

运行：`gradle-8.9/bin/gradle wrapper --gradle-version 8.9`

预期：生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 和 `gradle-wrapper.properties`。

- [ ] **步骤 3：建立最小可启动 Activity**

```kotlin
class MainActivity : ComponentActivity() {
    /** 创建 Compose 内容并在回到前台时刷新系统权限状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text(text = stringResource(R.string.app_name)) }
    }
}
```

- [ ] **步骤 4：验证工程骨架**

运行：`./gradlew :app:assembleDebug`

预期：生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **步骤 5：提交工程骨架**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat app
git commit -m "build(android): 初始化时界 Android 工程"
```

### 任务 2：用测试固定规则时间语义

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleRule.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/rules/ScheduleEvaluator.kt`
- 新建：`app/src/test/java/com/cwenhe/timefence/rules/ScheduleEvaluatorTest.kt`

- [ ] **步骤 1：先写失败的同日、跨午夜与下一边界测试**

```kotlin
class ScheduleEvaluatorTest {
    private val evaluator = ScheduleEvaluator()
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 验证同日规则开始包含、结束不包含。 */
    @Test
    fun `同日规则使用左闭右开区间`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)
        assertTrue(evaluator.evaluate(at(8, 0), listOf(rule)).blockedPackages.contains("demo.app"))
        assertFalse(evaluator.evaluate(at(10, 0), listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证周一开始的跨午夜规则会持续到周二。 */
    @Test
    fun `跨午夜规则归属于开始日`() {
        val rule = rule(startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(evaluator.evaluate(at(6, 59, day = 6), listOf(rule)).blockedPackages.contains("demo.app"))
        assertFalse(evaluator.evaluate(at(7, 0, day = 6), listOf(rule)).blockedPackages.contains("demo.app"))
    }

    /** 验证下一边界同时考虑开始和结束时间。 */
    @Test
    fun `返回当前时刻之后最近的规则边界`() {
        val rule = rule(startMinute = 8 * 60, endMinute = 10 * 60)
        assertEquals(at(8, 0), evaluator.evaluate(at(7, 30), listOf(rule)).nextBoundary)
    }

    /** 构造固定星期一生效的测试规则。 */
    private fun rule(startMinute: Int, endMinute: Int) = ScheduleRule(
        id = 1,
        name = "测试规则",
        startMinute = startMinute,
        endMinute = endMinute,
        days = setOf(DayOfWeek.MONDAY),
        packages = setOf("demo.app"),
        enabled = true,
        lockWhileActive = false,
    )

    /** 构造 2026 年 1 月指定日期和时间的上海时区时刻。 */
    private fun at(hour: Int, minute: Int, day: Int = 5): ZonedDateTime =
        ZonedDateTime.of(2026, 1, day, hour, minute, 0, 0, zone)
}
```

- [ ] **步骤 2：运行测试并确认红灯**

运行：`./gradlew :app:testDebugUnitTest --tests '*ScheduleEvaluatorTest'`

预期：因领域类型尚未实现而编译失败。

- [ ] **步骤 3：实现最小领域模型和纯计算器**

```kotlin
data class ScheduleRule(
    val id: Long,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek>,
    val packages: Set<String>,
    val enabled: Boolean,
    val lockWhileActive: Boolean,
)

data class RuleEvaluation(
    val activeRules: List<ScheduleRule>,
    val blockedPackages: Set<String>,
    val nextBoundary: ZonedDateTime?,
)
```

`ScheduleEvaluator.evaluate(now, rules)` 必须处理同日、跨午夜、跨周、禁用规则、夏令时缺口和重复时间，所有时间基于传入 `ZonedDateTime`，不直接读取系统时钟。夏令时缺口通过 `ZoneRules.getTransition()` 选择缺口后的第一个有效时刻，不能依赖会把分钟偏移平移到缺口之后的默认 `atZone()` 行为；重复时间明确选择第一个 offset。

- [ ] **步骤 4：运行规则测试并确认绿灯**

运行：`./gradlew :app:testDebugUnitTest --tests '*ScheduleEvaluatorTest'`

预期：全部通过。

- [ ] **步骤 5：提交规则引擎**

```bash
git add app/src/main/java/com/cwenhe/timefence/rules app/src/test/java/com/cwenhe/timefence/rules
git commit -m "feat(rules): 实现定时规则计算引擎"
```

### 任务 3：持久化规则并提供状态流

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/data/local/RuleEntity.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/data/local/RuleDao.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/data/local/TimeFenceDatabase.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/data/ScheduleRepository.kt`
- 新建：`app/src/test/java/com/cwenhe/timefence/data/ScheduleRepositoryMapperTest.kt`

- [ ] **步骤 1：先写实体转换测试**

测试必须断言七天位掩码、应用包名集合、启用状态和锁定状态能够无损往返，且空应用集合不会被保存成有效规则。

- [ ] **步骤 2：运行测试并确认红灯**

运行：`./gradlew :app:testDebugUnitTest --tests '*ScheduleRepositoryMapperTest'`

预期：因映射器尚未实现而失败。

- [ ] **步骤 3：实现 Room 表和事务 DAO**

```kotlin
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val enabled: Boolean,
    val lockWhileActive: Boolean,
)

@Entity(primaryKeys = ["ruleId", "packageName"], tableName = "rule_apps")
data class RuleAppEntity(val ruleId: Long, val packageName: String)

data class RuleWithApps(
    @Embedded val rule: RuleEntity,
    @Relation(parentColumn = "id", entityColumn = "ruleId")
    val apps: List<RuleAppEntity>,
)

@Dao
interface RuleDao {
    @Transaction
    @Query("SELECT * FROM rules ORDER BY startMinute, id")
    fun observeRules(): Flow<List<RuleWithApps>>

    @Upsert
    suspend fun upsertRule(rule: RuleEntity): Long

    @Query("DELETE FROM rule_apps WHERE ruleId = :ruleId")
    suspend fun deleteApps(ruleId: Long)

    @Insert
    suspend fun insertApps(apps: List<RuleAppEntity>)

    /** 在同一事务内保存规则并替换应用关联。 */
    @Transaction
    suspend fun replaceRule(rule: RuleEntity, packages: Set<String>): Long {
        val storedId = upsertRule(rule)
        val ruleId = if (rule.id == 0L) storedId else rule.id
        deleteApps(ruleId)
        insertApps(packages.sorted().map { RuleAppEntity(ruleId, it) })
        return ruleId
    }
}
```

- [ ] **步骤 4：实现仓库并验证测试**

仓库暴露 `observeRules()`、`getRules()`、`saveRule()`、`setEnabled()` 和 `deleteRule()`；每个写操作完成后由调用方重建下一次边界。

运行：`./gradlew :app:testDebugUnitTest`

预期：全部通过。

- [ ] **步骤 5：提交数据层**

```bash
git add app/src/main/java/com/cwenhe/timefence/data app/src/test/java/com/cwenhe/timefence/data
git commit -m "feat(data): 添加本地规则存储"
```

### 任务 4：发现可选择应用并汇总权限状态

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/apps/InstalledApp.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/apps/InstalledAppRepository.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/permissions/PermissionStatus.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/permissions/PermissionStatusRepository.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/permissions/SystemSettingsNavigator.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：声明最小包可见性和系统权限**

Manifest 仅声明启动器 Intent 查询、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM` 和 Android 13 通知权限，不声明网络、使用情况访问或 `QUERY_ALL_PACKAGES`。启动器 Intent 查询使用 `<queries>` 声明 `ACTION_MAIN` + `CATEGORY_LAUNCHER`，确保 Android 11 及以上能够发现可启动应用。

- [ ] **步骤 2：实现启动器应用查询**

`InstalledAppRepository.loadLaunchableApps()` 使用 `ACTION_MAIN` + `CATEGORY_LAUNCHER`，按本地化名称排序并去重包名。排除项通过 `PackageManager` 动态取得时界自身、所有 HOME 处理器、系统设置、权限控制器和安装器，不只依赖荣耀厂商包名硬编码。

- [ ] **步骤 3：实现权限快照和设置导航**

```kotlin
data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val exactAlarmAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    val protectionReady: Boolean
        get() = accessibilityEnabled && accessibilityConnected && exactAlarmAllowed
}
```

通知权限是可选能力，拒绝后不影响 `protectionReady`。设置导航分别打开无障碍、精确闹钟、通知详情、电池优化和应用详情页；荣耀专属页只做可用 Intent 探测，失败时回退标准设置页。Android 13 及以上侧载 APK 的引导补充“应用信息右上角 -> 允许受限设置”，否则系统可能禁止开启无障碍服务。

- [ ] **步骤 4：验证编译与 Manifest 合并**

运行：`./gradlew :app:processDebugMainManifest :app:testDebugUnitTest`

预期：Manifest 合并成功且测试通过。

- [ ] **步骤 5：提交应用发现和权限能力**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/cwenhe/timefence/apps app/src/main/java/com/cwenhe/timefence/permissions
git commit -m "feat(system): 添加应用发现与权限检查"
```

### 任务 5：实现精确时间边界调度

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BoundaryAlarmScheduler.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BoundaryAlarmReceiver.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/SystemChangeReceiver.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BoundaryCheckStore.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/EnforcementBridge.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：实现唯一下一边界闹钟**

`BoundaryAlarmScheduler.reschedule(rules, now)` 先取消固定的显式 `PendingIntent`，然后调用 `ScheduleEvaluator` 取得下一边界。允许精确闹钟时使用 `RTC_WAKEUP + setExactAndAllowWhileIdle()`；Android 12 及以上未授权时使用 `setAndAllowWhileIdle()` 降级并向权限状态写入“可能延迟”。`PendingIntent` 固定使用 `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`。

- [ ] **步骤 2：实现边界接收器**

接收器先同步通过 `EnforcementBridge.requestBoundaryCheck()` 通知已连接的服务，再使用 `goAsync()` 和应用级协程读取规则并注册下一条边界，最后在 `finally` 中调用 `PendingResult.finish()`。服务实例不存在时，`BoundaryCheckStore` 用应用私有 `SharedPreferences` 持久化一次待补检标记；服务重连后立即消费，不能尝试通过 `startService()` 启动无障碍服务。

- [ ] **步骤 3：实现系统变化恢复**

`SystemChangeReceiver` 处理 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`、`TIME_SET`、`TIMEZONE_CHANGED`、`DATE_CHANGED` 和精确闹钟授权变化。保存或启用当前已经生效的规则、服务重连、开机、时间变化及重新授权时都执行“立即检查一次 + 重建下一边界”，不能只安排未来闹钟。

- [ ] **步骤 4：验证接收器与 Lint**

运行：`./gradlew :app:testDebugUnitTest :app:lintDebug`

预期：测试通过，接收器导出状态和 `PendingIntent` 可变性无 Lint 错误。

- [ ] **步骤 5：提交边界调度**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/cwenhe/timefence/enforcement
git commit -m "feat(enforcement): 添加精确时间边界调度"
```

### 任务 6：实现无障碍拦截和静止前台退出

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockAccessibilityService.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/enforcement/BlockOverlay.kt`
- 新建：`app/src/main/res/xml/accessibility_service_config.xml`
- 修改：`app/src/main/AndroidManifest.xml`
- 修改：`app/src/main/java/com/cwenhe/timefence/enforcement/EnforcementBridge.kt`

- [ ] **步骤 1：声明最小无障碍能力**

服务监听 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOWS_CHANGED`，启用 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 和 `canRetrieveWindowContent=true`，不读取或记录节点文字。服务使用 `android:exported="true"` 并由 `android.permission.BIND_ACCESSIBILITY_SERVICE` 保护；内部 Receiver 均使用显式组件且 `android:exported="false"`。

- [ ] **步骤 2：实现窗口与边界双入口**

```kotlin
class BlockAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastForegroundPackage: String? = null
    private val appContainer: AppContainer
        get() = (application as TimeFenceApplication).container
    private val blockOverlay by lazy { BlockOverlay(this) }

    /** 在窗口变化时记录前台包名并执行规则校验。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        lastForegroundPackage = packageName
        serviceScope.launch { enforceIfBlocked(packageName) }
    }

    /** 无障碍服务无需处理系统中断回调。 */
    override fun onInterrupt() = Unit

    /** 时间边界到达时主动读取活动窗口，不等待新的窗口事件。 */
    fun checkActiveWindowAtBoundary() {
        val packageName = rootInActiveWindow?.packageName?.toString()
            ?: windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused)
            }?.root?.packageName?.toString()
            ?: lastForegroundPackage
            ?: return
        serviceScope.launch { enforceIfBlocked(packageName) }
    }

    /** 当前包命中有效规则时返回桌面并展示短时说明。 */
    private suspend fun enforceIfBlocked(packageName: String) {
        val evaluation = appContainer.scheduleEvaluator.evaluate(
            now = ZonedDateTime.now(),
            rules = appContainer.scheduleRepository.getRules(),
        )
        if (packageName in evaluation.blockedPackages) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            blockOverlay.show(evaluation.activeRules, packageName)
        }
    }
}
```

每次窗口或边界检查都以当前 `ZonedDateTime` 重新运行 `ScheduleEvaluator`，不能由闹钟维护一个可能过期的开关。边界检查按 `0/100/300/700ms` 做最多四次有限重试，首次确认命中后停止；活动根节点为空时再查 active/focused 应用窗口，最后才在屏幕点亮且未锁屏时使用最近可靠包名。锁屏时不执行 HOME，解锁窗口事件到达后立即重查。

命中后先调用 `performGlobalAction(GLOBAL_ACTION_HOME)`，成功后显示短时 `TYPE_ACCESSIBILITY_OVERLAY`。同一包名在短时间内去抖，避免事件风暴，但规则有效期间再次打开仍会拦截。返回桌面不保证关闭画中画、分屏或锁定任务模式，首版将这些行为列入已知限制和真机验收，不能声称已经终止目标进程。

- [ ] **步骤 3：处理服务重连和边界竞态**

`EnforcementBridge` 使用弱引用保存已连接服务并向权限仓库发布“服务已连接”状态；`onDestroy()` 清理引用。没有服务实例时写入 `BoundaryCheckStore`；`onServiceConnected()` 绑定后立即消费持久化补检标记并重建闹钟。

- [ ] **步骤 4：验证服务编译和无障碍配置**

运行：`./gradlew :app:assembleDebug :app:lintDebug`

预期：Debug APK 构建成功，无无障碍服务声明错误。

- [ ] **步骤 5：提交核心拦截能力**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml app/src/main/java/com/cwenhe/timefence/enforcement
git commit -m "feat(enforcement): 实现受限应用前台拦截"
```

### 任务 7：装配依赖并实现规则状态 ViewModel

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/core/AppContainer.kt`
- 修改：`app/src/main/java/com/cwenhe/timefence/TimeFenceApplication.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceViewModelFactory.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/dashboard/DashboardViewModel.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/rules/RulesViewModel.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/editor/RuleEditorViewModel.kt`

- [ ] **步骤 1：建立单一应用容器**

`AppContainer` 只负责构造数据库、仓库、计算器、权限仓库和闹钟调度器，不引入 Hilt。`TimeFenceApplication.onCreate()` 创建容器并启动规则流监听，每次规则变化重建下一边界。

- [ ] **步骤 2：实现首页状态组合**

`DashboardViewModel` 合并规则流、每分钟时钟和权限状态，输出 `ProtectionReady`、`Active`、`Waiting` 三类不可变界面状态；时间计算只调用 `ScheduleEvaluator`。

- [ ] **步骤 3：实现规则写操作**

`RulesViewModel` 负责启停和删除；`RuleEditorViewModel` 校验名称、日期、应用与时间，并在规则正生效且 `lockWhileActive` 为真时拒绝修改。

- [ ] **步骤 4：验证所有单元测试**

运行：`./gradlew :app:testDebugUnitTest`

预期：全部通过。

- [ ] **步骤 5：提交依赖装配和状态管理**

```bash
git add app/src/main/java/com/cwenhe/timefence/core app/src/main/java/com/cwenhe/timefence/TimeFenceApplication.kt app/src/main/java/com/cwenhe/timefence/ui
git commit -m "feat(state): 装配应用依赖与规则状态"
```

### 任务 8：实现 Material 3 操作界面

**文件：**
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceTheme.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/TimeFenceApp.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/dashboard/DashboardScreen.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/rules/RulesScreen.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/editor/RuleEditorScreen.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/picker/AppPickerScreen.kt`
- 新建：`app/src/main/java/com/cwenhe/timefence/ui/settings/SettingsScreen.kt`
- 新建：`app/src/main/res/values/colors.xml`
- 修改：`app/src/main/res/values/strings.xml`
- 新建：`app/src/main/res/drawable/ic_timefence.xml`
- 新建：`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- 修改：`app/src/main/java/com/cwenhe/timefence/MainActivity.kt`
- 新建：`app/src/androidTest/java/com/cwenhe/timefence/ui/RuleEditorScreenTest.kt`

- [ ] **步骤 1：先写规则表单 Compose 测试**

测试覆盖未选日期、未选应用、相同起止时间时保存按钮不可用，以及有效输入时触发保存回调。每个测试函数添加中文职责注释。

- [ ] **步骤 2：实现主题和三入口导航**

主题使用石墨黑、柔和白、青绿、砖红和琥珀色，不使用渐变；底部导航固定为“今天”“规则”“设置”，图标按钮提供内容描述和稳定 `48dp` 点击区域。

- [ ] **步骤 3：实现首页与规则列表**

首页展示保护状态、下一边界和今天规则。规则行包含时间、星期、应用图标、启用开关和编辑入口；不嵌套卡片，圆角不超过 `8dp`。

- [ ] **步骤 4：实现编辑器、应用选择和设置页**

编辑器使用 Material 时间选择器、星期分段选择和应用选择入口；应用页支持搜索和复选框；设置页显示每项权限的真实状态及系统设置图标按钮，荣耀设备附加后台设置路径。

- [ ] **步骤 5：构建 UI 测试 APK**

运行：`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`

预期：应用 APK 和测试 APK 均生成，界面代码编译通过。

- [ ] **步骤 6：提交完整界面**

```bash
git add app/src/main/java/com/cwenhe/timefence/ui app/src/main/java/com/cwenhe/timefence/MainActivity.kt app/src/main/res app/src/androidTest
git commit -m "feat(ui): 完成时界规则与权限界面"
```

### 任务 9：补齐中文项目文档与诊断说明

**文件：**
- 新建：`README.md`
- 新建：`AGENTS.md`
- 新建：`docs/index.md`
- 新建：`docs/honor-setup.md`
- 新建：`docs/diagnostics.md`
- 新建：`docs/release-guide.md`
- 新建：`mkdocs.yml`
- 新建：`requirements-docs.txt`

- [ ] **步骤 1：编写用户安装和授权说明**

README 写清楚支持版本、APK 安装、无障碍与精确闹钟授权、Android 13+“允许受限设置”、画中画/分屏限制和本地构建命令。荣耀文档写清楚 MagicOS 自启动、后台运行与电池优化检查，不承诺所有系统版本菜单名称完全一致。

- [ ] **步骤 2：编写诊断说明**

诊断文档列出版本、权限状态、下一边界和本地日志的采集方式，并明确日志不包含屏幕文本、输入内容或账号信息。

- [ ] **步骤 3：编写维护规则和文档站配置**

`AGENTS.md` 固定 JDK 17、`./gradlew testDebugUnitTest lintDebug assembleDebug`、中文文档与函数注释要求。MkDocs 导航包含首页、荣耀设置、诊断和发版。

- [ ] **步骤 4：验证文档**

运行：`python3 -m pip install -r requirements-docs.txt`

运行：`python3 -m mkdocs build --strict -d /tmp/timefence-docs`

预期：严格模式构建成功。

- [ ] **步骤 5：提交文档**

```bash
git add README.md AGENTS.md docs mkdocs.yml requirements-docs.txt
git commit -m "docs(project): 添加安装、诊断与维护文档"
```

### 任务 10：配置 GitHub 持续集成、Pages 与 Tag 发版

**文件：**
- 新建：`.github/workflows/ci.yml`
- 新建：`.github/workflows/docs.yml`
- 新建：`.github/workflows/release.yml`
- 修改：`app/build.gradle.kts`
- 修改：`docs/release-guide.md`

- [ ] **步骤 1：配置普通提交验证**

`ci.yml` 在 push 和 pull request 上使用 JDK 17，运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`，并上传 Debug APK 和 Lint 报告。

- [ ] **步骤 2：配置 GitHub Pages**

`docs.yml` 在 `main` 分支文档变化时安装锁定的 MkDocs 依赖，执行严格构建并通过官方 Pages Actions 发布，不在仓库提交 `site/` 或 `public/`。

- [ ] **步骤 3：接入环境变量签名**

`app/build.gradle.kts` 仅当以下四个环境变量都存在时创建 `release` 签名配置：

```text
TIMEFENCE_KEYSTORE_FILE
TIMEFENCE_KEYSTORE_PASSWORD
TIMEFENCE_KEY_ALIAS
TIMEFENCE_KEY_PASSWORD
```

任何密码、Base64 密钥或 `*.jks` 都加入 `.gitignore`，不写入 Gradle 文件、文档示例值或 Actions 日志。

- [ ] **步骤 4：配置 Tag 自动发布**

`release.yml` 仅匹配 `v*` 标签，先验证 `TIMEFENCE_KEYSTORE_BASE64` 等 Secrets，解码到 Runner 临时目录，再将标签去掉 `v` 作为 `versionName`、运行序号作为 `versionCode`，执行测试、Lint 和 `assembleRelease`。最后生成 SHA-256 文件并通过 `softprops/action-gh-release@v2` 发布 APK。

- [ ] **步骤 5：检查 Actions YAML 和本地构建**

运行：`ruby -e 'require "yaml"; Dir[".github/workflows/*.yml"].each { |f| YAML.load_file(f); puts "#{f} ok" }'`

运行：`./gradlew testDebugUnitTest lintDebug assembleDebug`

预期：三个 YAML 可解析，完整本地验证通过。

- [ ] **步骤 6：提交自动化**

```bash
git add .github app/build.gradle.kts .gitignore docs/release-guide.md
git commit -m "ci(github): 添加构建、文档与 APK 发版流程"
```

### 任务 11：生成本地可安装 APK 并执行最终验收

**文件：**
- 修改：`README.md`，仅在实际产物名称与文档不一致时修改
- 产物：`app/build/outputs/apk/debug/timefence-debug.apk`

- [ ] **步骤 1：运行完整静态验证**

运行：`./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`

预期：命令退出码为 0，单元测试无失败，Lint 无错误，两个 APK 均生成。

- [ ] **步骤 2：检查 APK 元数据**

运行：`$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/*.apk`

预期：包名为 `com.cwenhe.timefence`，版本为 `0.1.0`，应用名为“时界”，最低 SDK 为 26，目标 SDK 为 35。

- [ ] **步骤 3：检查新增函数注释与仓库安全**

审阅 `git diff` 和所有新增 Kotlin 函数，确认每个函数、构造函数、覆写方法和测试函数有中文职责注释。搜索仓库，确认没有密钥、密码、Token、`local.properties`、APK 或 Android SDK 路径进入 Git 暂存区。

- [ ] **步骤 4：执行荣耀真机验收**

设备可连接时运行 `adb install -r app/build/outputs/apk/debug/timefence-debug.apk`，完成授权后逐项验证：

1. 设置两分钟后开始的规则，提前打开目标应用并保持静止，到点后不触摸屏幕也返回桌面。
2. 规则生效期间重新打开目标应用，再次被拦截。
3. 规则结束后目标应用恢复可用。
4. 锁屏跨过开始时间后解锁，目标应用不能继续停留。
5. 重启、修改时区和关闭权限后，状态与行为符合规格。
6. Android 13 及以上从 GitHub 安装发行 APK 后，按引导允许受限设置并成功开启无障碍。
7. 单独记录目标应用处于画中画、分屏和锁定任务模式时的实际行为，不把普通全屏场景结果外推到这些模式。

没有连接设备时，明确记录真机验收未执行，不以模拟器或编译成功替代。

- [ ] **步骤 5：生成校验值并记录交付路径**

运行：`sha256sum app/build/outputs/apk/debug/timefence-debug.apk`

预期：输出可复核的 SHA-256；最终回复同时给出 APK 绝对路径、GitHub Secrets 配置入口和仍需真机验证的风险。

## 计划自审结果

- 规格中的界面、规则语义、双触发拦截、权限降级、荣耀适配、诊断、自动化测试和 APK 交付均有对应任务。
- GitHub Tag 发版已包含签名、版本号、校验文件与 Release 上传，不依赖 GitLab。
- 首版没有加入账号、网络、统计、网站过滤、远程控制或防卸载能力。
- 时间计算、数据转换和核心表单均先写失败测试，再实现最小代码。
- 计划中没有待定占位项；无法自动完成的荣耀真机步骤被明确列为条件验收。
