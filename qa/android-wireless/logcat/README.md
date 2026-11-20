# Android 主控日志实测

2026-11-21 使用签名发布包 RProbeDesk 1.1.1（版本码 4），主控是 Android 15 `emulator-5588`，目标是已通过无线配对与通知核对的 Android 15 `emulator-5586`。在主控的「日志」页手动开始采集，通过目标的系统日志和操作界面交叉核对。

## 缓冲淘汰与主控进程内存

在目标 `main` 缓冲区用 `RPDQ09` 标签连续写入 `q09-014220 seq=1` 至 `seq=2000`，历时 27.86 秒。写入器从目标 logcat 逐行读回，确认 2,000 个编号按序出现且各出现一次。复跑时在主控「日志」中先开始采集，再在开发机运行下列命令，每次指定新的标识。

```text
python qa/android-wireless/logcat/load.py --adb C:/Android/Sdk/platform-tools/adb.exe --run q09-run2
```

主控按 Tag `RPDQ09` 筛选时可见 `seq=2000`，筛选命中 1,397 行，界面显示已淘汰 704 行。解除筛选后，界面显示 **1,500 行、已淘汰 719 行**。这两次读数之间仍在采集系统日志。筛选命中数随条件变化，淘汰数统计整个会话缓冲区。[按标签查看末尾序号](load-filtered.png) · [解除筛选后的缓冲区状态](load-unfiltered.png)

两组内存读数都来自主控 `dev.rprobedesk` 进程（PID 1992），日志页在前台、采集运行、筛选 Tag 相同。写入前先显示一条预热记录，写入后等界面读到末尾序号再取样。每组三次执行 `adb -s emulator-5588 shell dumpsys meminfo dev.rprobedesk`，读取 App Summary 的 `TOTAL PSS`，间隔约半秒。PSS 将共享物理页按比例计入进程，覆盖整个应用及其 UI。这里记录单轮观测，不能据此判断长期增长趋势。[Android 内存指标说明](https://developer.android.com/tools/dumpsys)

| 采样时点 | 三次 TOTAL PSS（KiB） | 中位数（KiB） |
|---|---|---:|
| 写入前 | 61,574、60,868、60,888 | 60,888 |
| 2,000 条写入后 | 69,874、66,514、66,470 | 66,514 |

中位数增加 **5,626 KiB（5.49 MiB）**。

## 从日志切到镜像

1. 保持采集，在目标打开仓库内的测试应用。主控切到「镜像」并打开视频，在镜像画面点击「写入测试日志」。目标响应点击，输出一条信息记录和一条附异常堆栈的错误记录。
2. 关闭镜像并切回「日志」，按等级 `E`、Tag `RProbeFixture` 筛选。界面显示 `sample error`、`IllegalStateException: QA sample` 和触发异常的 `TargetsActivity` 调用位置。[镜像期间收到的错误](mirror-error.png) · [测试应用事件](../../fixture/src/main/java/dev/rprobedesk/fixture/TargetsActivity.java)
3. 点「暂停显示」，向目标追加同标签、同等级的 `after-pause-Q09`，再经 Android 系统文件选择器导出。导出的 16 行均属于原错误和它的堆栈，排除了暂停后追加的错误以及较低等级的输入记录。[导出快照](mirror-filtered.txt)

## 退后台与手动恢复

在日志页开始采集，向目标写入前台标记，再按主控系统 Home。通过主控系统 Activity 状态确认 RProbeDesk 已到 `STOPPED` 后，目标原有的 `logcat -v threadtime` 进程（PID 5979）已经结束。此时才写入 `Q09stopped-014919`。返回原 Activity 后，主控显示「主控进入后台，采集中断」和「开始采集」。另一次停留后台较久的检验中，Android 关闭了连接，主控回到离线状态，仍保留已有日志和中断标记。[进入后台的标记](background-stop.png) · [断开后保留的记录](background-offline.png)

手动点「开始采集」后，目标出现新的 logcat 进程（PID 5994）。写入 `Q09manual-014919` 后停止采集。按 `RPDQ09` 和对应关键词查找，后台期间的标记命中 **0 行**，手动恢复后的标记命中 **1 行**。目标系统内可以读回前台、后台、手动恢复后的全部三个标记。最后点主控「清屏」，本地读数变为 0 行，目标 logcat 仍可读到恢复标记。[后台标记的筛选结果](background-absent.png) · [恢复后的标记](manual-resume.png) · [目标 logcat 的对应记录](target-lifecycle.txt)
