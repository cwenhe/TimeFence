# 首版不启用代码压缩；保留文件作为发布构建的显式入口。
# Shizuku 通过类名在独立 shell/root 进程实例化 UserService。
-keep class com.cwenhe.timefence.suspension.SuspendUserService { *; }
-keep interface com.cwenhe.timefence.suspension.ISuspendUserService { *; }
-keep class com.cwenhe.timefence.suspension.ISuspendUserService$Stub { *; }
