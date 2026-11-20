# 安全报告 / Security Policy

## 中文

RProbeDesk 借助用户已授权的 ADB 会话操作所选目标。请私下报告会越过授权边界、把输入或写操作发往另一台设备、误覆盖文件或泄露设备数据的问题。普通连接失败和工具找不到等问题可按[支持指南](SUPPORT.md)反馈。

在本仓库的 **Security → Advisories** 中查找 **Report a vulnerability**。若页面没有此按钮，使用维护者公开列出的私下联系渠道；尚无可用渠道时，可在 Issue 中仅请求私下联系。请在建立私下渠道后再提供受影响版本、主控与目标系统、预期边界、实际影响，以及用自有测试数据重现的最少步骤。

请移除配对码、ADB 私钥、APK 签名材料、含令牌的下载地址和他人的设备数据。需要一起跟踪公开进展时，先同维护者商定哪些信息适合披露。

## English

RProbeDesk operates on the selected target through an ADB session authorized by the user. Please report violations of the device authorization boundary, input or writes reaching the wrong target, unintended overwrites and device data disclosure privately. For ordinary connection or tool-discovery problems, see [Support](SUPPORT.md).

Look for **Report a vulnerability** under this repository's **Security → Advisories**. If the button is unavailable, use a private contact channel published by the maintainer. If none is listed, open an issue asking for one, without posting details. Once a private channel is available, include the affected version, host and target systems, the expected boundary, the impact and a minimal reproduction using your own test data.

Redact pairing codes, ADB private keys, APK signing material, download URLs containing tokens and other people's device data. Agree with the maintainer about what can be shared before tracking the report publicly.
