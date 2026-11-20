# 贡献指南 / Contributing

问题报告、文档修正、设备兼容性记录和代码修改都可以提交。参与讨论时请遵守[行为准则](CODE_OF_CONDUCT.md)。涉及未经授权操作、数据泄露或跨设备误操作的缺陷，请先阅读[安全报告指引](SECURITY.md)。

Bug reports, documentation fixes, device compatibility reports and code changes are welcome. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md). For unintended access, information disclosure or operations reaching the wrong device, use the [security reporting guide](SECURITY.md).

## 提交修改

1. 先搜索已有 Issue。报告问题时区分**主控**与**目标**，例如「Windows 11 x64 主控 → Android 15 平板」。写出软件版本、无线调试连接方式、出问题的工作区与可重复的最少步骤。Windows 工具相关问题附 `adb version` 或 `scrcpy --version`。
2. 双端业务放在 `app/src/shared/kotlin`。Windows 进程与文件选择器适配位于 `desktopMain`，Android 无线传输与系统文档选择器位于 `androidMain`。构建前先按 [README](README.md) 准备工具链。
3. 使用受影响路径验证修改。共享文件、Shell、日志或应用操作可先运行 `.\gradlew.bat :app:desktopTest`。Android 修改可运行 `.\gradlew.bat :app:assembleDebug :app:lintDebug`。涉及真实配对、镜像、文件覆盖或设备切换时，用自有测试设备并写明实测组合。测试程序和可复用样本在 [qa](qa/)。
4. 一个 PR 处理一个可说明的变化。写清改前和改后的行为、使用的检查、仍待实机验证的部分。界面修改可附同一页面的截图；发布说明或使用步骤变更时同步两种语言。

提交前移除地址、配对码、签名密钥、个人文件名及日志中的业务内容。请确认有权按本项目许可证提交自有修改；引入第三方代码、字体、二进制或新依赖时，标出来源、版本与许可，更新随包声明。[项目许可证](LICENSE.txt) · [第三方清单](THIRD-PARTY-NOTICES.md)

## Submitting a change

1. Search existing issues. Identify both the **host** and the **target**, for example “Windows 11 x64 host → Android 15 tablet”. Include the app version, wireless connection method, affected workspace and shortest repeatable steps. For Windows tool issues, include the output of `adb version` or `scrcpy --version`.
2. Shared behavior lives in `app/src/shared/kotlin`. Windows process and file-picker integration live in `desktopMain`. Android transport and document-picker integration live in `androidMain`. See the [English README](README_EN.md) for build prerequisites.
3. Run the checks that exercise the change. Start with `.\gradlew.bat :app:desktopTest` for shared file, Shell, log or package operations. For Android changes, run `.\gradlew.bat :app:assembleDebug :app:lintDebug`. For pairing, mirroring, overwrites or target switching, record the host and target used for a device check. Use your own test devices and data. Reusable checks and fixtures live in [qa](qa/).
4. Keep each PR focused. Describe the behavior before and after, the checks performed and any remaining device coverage. For UI changes, a screenshot of the affected page helps. Update both languages when changing release notes or user instructions.

Remove addresses, pairing codes, signing keys, personal filenames and sensitive log content. Submit original changes you have the right to offer under the project license. For third-party source, fonts, binaries or new dependencies, identify the source, version and license and update the distributed notices. [Project license](LICENSE.txt) · [Third-party notices](THIRD-PARTY-NOTICES.md)
