# AutoSign · 自动签到

用 Android 无障碍服务在指定 App 里每天自动走一遍签到流程，跑完把结果写日志。纯本地，无网络请求。

## 功能

- **定时触发**：每天固定时间（默认 08:30）由 AlarmManager 精确闹钟拉起，`RECEIVE_BOOT_COMPLETED` 保证重启后恢复
- **无障碍自动操作**：启动目标应用 → 进入签到入口 → 沿导航路径点击 → 完成签到 → 校验结果
- **手动执行**：主界面「立即执行一次」，方便调试
- **界面扫描校准**：把当前界面的可点节点 dump 出来，用于调整选择器
- **日志与状态**：最近一次结果、运行日志可直接在 App 内查看与清空
- **抗杀**：可一键跳转「忽略电池优化」设置，避免后台被系统回收

## 结构

```
src/ai/zachshi/autocheckin/MainActivity.java     主界面（开关、时间、触发、日志）
src/ai/zachshi/autocheckin/AutoSignService.java  无障碍服务与签到流程状态机
src/ai/zachshi/autocheckin/Scheduler.java        AlarmManager 定时调度
src/ai/zachshi/autocheckin/BootReceiver.java     开机恢复
src/ai/zachshi/autocheckin/AlarmReceiver.java    闹钟触发
src/ai/zachshi/autocheckin/Prefs.java            配置与日志存储
```

目标应用包名默认值在 `Prefs.DEFAULT_PKG`，也可以在 App 内改。选择器按目标 App 的 `text` / `contentDescription` / 类名匹配，换 App 需要按「扫描当前界面」的结果调整。

## 使用

1. 安装 `AutoSign-v1.5.apk`
2. 打开「无障碍服务」设置，找到「自动签到」并启用
3. 回到 App，设置时间、确认目标包名，打开「每日自动签到」
4. 点「立即执行一次」验证，观察日志

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh    # 需设置 KS_PASS 或首次运行自动生成 keystore 口令
```

仓库不含签名文件。

## 注意

无障碍服务权限很高，请只在自己可信的设备上使用，并且只针对自己有权自动化的应用。

## 许可

MIT
