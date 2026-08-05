# 发版指南

GitHub Actions 在推送符合规则的 `v*` 标签后构建签名 Release APK、验证签名、生成 SHA-256，并创建 GitHub Release。仓库和 Workflow 不保存真实签名文件或密码。

## 版本规则

- 标签必须严格使用 `vMAJOR.MINOR.PATCH`，例如 `v0.1.0`；不支持预发布后缀。
- 每个版本段只能包含十进制数字，除数字 `0` 外不能有前导零。
- `MAJOR` 范围为 `0..2099`，`MINOR` 和 `PATCH` 范围为 `0..999`；`v0.0.0` 无效。
- APK 的 `versionName` 是去掉前导 `v` 的标签值。
- APK 的 `versionCode` 按 `MAJOR * 1000000 + MINOR * 1000 + PATCH` 计算。例如 `v1.2.3` 对应 `1002003`。
- 安装包名称为 `timefence-<versionName>.apk`，校验文件追加 `.sha256`。

确定性 `versionCode` 保证按三段版本递增时可以覆盖升级。发布过的版本号不要重复使用，也不要倒序发布较小版本；修复后应增加版本号并创建新标签，使安装包、源码和校验值保持一一对应。

## 首次配置签名

### 1. 创建并离线保存密钥

在可信设备上运行：

```bash
keytool -genkeypair -v \
  -keystore timefence-release.jks \
  -alias timefence \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

将 JKS 文件和密码保存在受控的离线备份中。签名密钥丢失后，使用新密钥生成的 APK 不能直接覆盖升级现有安装。

### 2. 生成单行 Base64

```bash
openssl base64 -A -in timefence-release.jks
```

该命令的输出就是密钥材料，不要写入仓库、Issue、Actions 日志或普通聊天记录。

### 3. 创建受保护的 release Environment

进入仓库的“Settings -> Environments”，创建名为 `release` 的 Environment，并配置：

1. 启用 Required reviewers，至少由一名维护者确认每次签名构建。
2. 在 Deployment branches and tags 中选择 Selected branches and tags，并添加 `v*` 标签规则。
3. 将以下四项配置为该 Environment 的 Secrets，不要使用全仓库可访问的 Repository secrets。

| Secret | 内容 |
| --- | --- |
| `TIMEFENCE_KEYSTORE_BASE64` | JKS 文件的单行 Base64 |
| `TIMEFENCE_KEYSTORE_PASSWORD` | JKS 密码 |
| `TIMEFENCE_KEY_ALIAS` | 密钥别名，例如创建时使用的别名 |
| `TIMEFENCE_KEY_PASSWORD` | 私钥密码 |

Workflow 会先检查四项是否存在，再把 JKS 解码到 Runner 的临时目录。Gradle 只接收临时路径和环境变量；APK 验签结束后会先清理 JKS，再调用 Artifact 上传或 GitHub Release 发布步骤。

### 4. 配置签名证书摘要

`TIMEFENCE_CERT_SHA256` 是可选但强烈建议配置的 Environment variable。它是公开证书的 SHA-256，不是私钥；Workflow 会用它阻止误用另一份有效 JKS。

先对使用正式密钥构建的 APK 执行：

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify \
  --verbose \
  --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

复制 `Signer #1 certificate SHA-256 digest` 的 64 位十六进制值，进入 `release` Environment 的 Variables 添加 `TIMEFENCE_CERT_SHA256`。摘要可使用大小写字母，也可包含冒号。

### 5. 保护发版标签

进入“Settings -> Rules -> Rulesets”创建 Tag ruleset，目标包含 `refs/tags/v*`，限制创建、更新和删除发版标签的角色。签名 Workflow 还会校验标签最终指向的提交属于 `origin/main`；功能分支上的标签不会获得发布产物。

## 发布步骤

1. 确认目标提交已进入 `main`、CI 已通过，并完成所需真机验收。
2. 更新版本相关发布说明，但不要在源码中临时写入密码或密钥。
3. 创建并推送带注释标签：

    ```bash
    git tag -a v0.1.0 -m "发布 v0.1.0"
    git push origin v0.1.0
    ```

4. 在 GitHub Actions 中批准 `release` Environment，并检查“发布签名 APK”任务。
5. 在 Releases 页面下载 APK 和 `.sha256`，执行校验并安装验证。

```bash
sha256sum -c timefence-0.1.0.apk.sha256
```

发布任务先在只读 Job 中运行单元测试、Release Lint、`assembleRelease`、APK 验签和可选证书连续性校验，清理 JKS 后才上传 APK。第二个无密钥 Job 单独获得 `contents: write`，复核 SHA-256 后使用 GitHub CLI 创建 Release。任何验证失败、Secret 缺失、标签来源不合法或签名异常都会阻止 Release 创建。

## 日历数据维护

法定工作日和 A 股交易日只使用已经正式发布的年度公告。新增年份时：

1. 在 `data/calendar/source/<年份>.json` 保存国务院、上交所和深交所公告地址，以及已人工复核的休假区间和调休日期。
2. 更新 `tools/calendar/generate_calendar.py` 的输入年份、revision 和官方计数断言。
3. 运行 `python3 tools/calendar/generate_calendar.py`，确认 `data/calendar/zh-CN.json` 与 `app/src/main/res/raw/zh_cn_calendar.json` 的 SHA-256 完全相同。
4. 运行日历单测和完整 Android 验证后再推送 `main`；联网设备会从仓库 raw 文件更新，新 APK 同时携带最新兜底数据。

官方年度尚未发布时不要用普通周一至周五生成完整年份。缺失年份应保持未知，使界面提示更新而不是误判规则。

## 文档站发布

首次配置仓库时，进入“Settings -> Pages”，将 Source 设置为“GitHub Actions”。之后 `main` 分支中的 `README.md`、`docs/`、`mkdocs.yml` 或 `requirements-docs.txt` 发生变化时，“发布项目文档”Workflow 会严格构建并部署站点；仓库不提交生成的 `site/` 目录。

## 本地签名构建

本地需要签名 Release 时，通过环境变量提供同样的信息：

```bash
export TIMEFENCE_KEYSTORE_FILE=/安全路径/timefence-release.jks
export TIMEFENCE_KEYSTORE_PASSWORD='由安全终端输入或密钥工具注入'
export TIMEFENCE_KEY_ALIAS='由安全终端输入或密钥工具注入'
export TIMEFENCE_KEY_PASSWORD='由安全终端输入或密钥工具注入'
export TIMEFENCE_VERSION_NAME=0.1.0
export TIMEFENCE_VERSION_CODE=1000
./gradlew clean testDebugUnitTest lintRelease assembleRelease
```

上述占位文字不能直接使用。更推荐由密码管理器或 CI 注入敏感环境变量，避免把密码保存在 shell 历史、脚本或 `.env` 文件中。

## 安装验收

至少检查以下内容：

1. APK 签名验证和 SHA-256 校验通过。
2. 包名为 `com.cwenhe.timefence`，最低系统版本为 Android 8。
3. 覆盖安装上一版后规则数据仍在。
4. 侧载 APK 在 Android 13 及以上能够按说明允许受限设置并开启无障碍。
5. 荣耀真机上完成“提前打开目标应用并静止等待，到点自动返回桌面”。
6. 规则期间再次打开会拦截，结束后恢复可用。

编译和自动化测试不能替代第 4 至第 6 项真机验收。
