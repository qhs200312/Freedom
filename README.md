<div align="center">
  <img src="V2rayNG/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="104" alt="Freedom icon">
  <h1>Freedom</h1>
  <p><strong>清晰、稳定，为 Android 日常连接而设计。</strong></p>
  <p>A focused Android proxy client built for reliable everyday connectivity.</p>

  [![Latest release](https://img.shields.io/github/v/release/qhs200312/Freedom?display_name=tag&style=flat-square)](https://github.com/qhs200312/Freedom/releases/latest)
  [![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white&style=flat-square)](https://developer.android.com)
  [![License](https://img.shields.io/github/license/qhs200312/Freedom?style=flat-square)](LICENSE)
  [![Downloads](https://img.shields.io/github/downloads/qhs200312/Freedom/total?logo=github&style=flat-square)](https://github.com/qhs200312/Freedom/releases)
</div>

---

Freedom 是一款独立维护的 Android 网络代理与连接管理工具。它把节点、订阅、路由、DNS 和运行状态整理在一套简洁的界面里，重点解决移动网络切换、连接恢复和日常使用中的稳定性问题。

> [!IMPORTANT]
> Freedom 由本仓库独立开发和发布。本项目只代表 Freedom 及其维护者，不代表任何其他客户端、组织或开发团队。

## 功能特点

- **多种运行模式**：支持 Android VPN、Root TUN 和 TProxy，适应不同设备环境。
- **清晰的连接状态**：首页展示连接状态、出口 IP、位置、延迟、实时速率和累计流量。
- **可靠的订阅管理**：支持订阅分组、扫码导入和本地节点；更新订阅时不会误删手动添加的节点。
- **灵活的路由与 DNS**：提供预设和自定义路由、本地 DNS、国内外 DNS 分流及非代理 UDP 控制。
- **移动网络恢复**：网络切换或短暂断流后自动尝试恢复连接，减少手动重启服务。
- **按需自动化**：可选择打开应用时更新订阅、开机自动连接，并可直接进入系统自启动权限设置。
- **快速控制**：支持快捷设置磁贴和桌面组件，常用连接操作不必反复进入应用。

## 下载

从 [GitHub Releases](https://github.com/qhs200312/Freedom/releases/latest) 获取最新正式版。
版本变化可查看 [更新日志](CHANGELOG.md)。

当前发布包面向 `arm64-v8a` Android 设备：

```text
Freedom_<version>_fdroid_release_arm64-v8a.apk
```

安装新版本时可直接覆盖旧版本，应用数据、订阅和节点配置会保留。建议只从本仓库 Release 页面下载，并在安装前核对版本和文件校验值。

## 开始使用

1. 安装 Freedom，并授予 VPN 和通知权限。
2. 通过订阅链接、二维码、剪贴板或手动方式添加节点。
3. 选择节点，在首页点击连接。
4. 需要开机连接时，在高级设置中开启“开机时自动连接”，并进入“申请自启动权限”完成系统放行。

> Freedom 不提供代理节点或订阅服务。请仅使用你信任且有权使用的网络服务，并遵守所在地法律法规。

## GeoIP 与 GeoSite

路由数据文件存放在应用的 assets 目录中，部分系统上的实际路径可能不同：

```text
Android/data/com.v2ray.ang.fdroid/files/assets
```

应用支持更新或导入兼容的 `geoip.dat` 与 `geosite.dat`。可参考：

- [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
- [Loyalsoldier/geoip](https://github.com/Loyalsoldier/geoip)

## 构建

要求 JDK 17 和 Android SDK。Android 工程位于 `V2rayNG` 目录：

```bash
cd V2rayNG
./gradlew assembleFdroidRelease -PABI_FILTERS=arm64-v8a
```

Windows 可使用 `gradlew.bat`。正式发布前还需要使用自己的签名证书对 APK 进行签名；仓库不包含发布私钥。

## 隐私与安全

- Freedom 不要求注册账号，也没有用于收集用户配置的开发者服务器。
- 节点、订阅和设置保存在设备本地；只有用户主动更新订阅、测试连接或查询出口 IP 时才会发起相应网络请求。
- 日志可能包含服务器地址等敏感信息，请检查后再主动分享。

完整说明见 [Freedom 隐私政策](CR.md)。

## 开源说明

Freedom 以 [GPL-3.0](LICENSE) 许可证发布，并使用 [Xray-core](https://github.com/XTLS/Xray-core)、[v2fly/v2ray-core](https://github.com/v2fly/v2ray-core) 等开源生态组件。

本代码库在演进过程中使用并改造了 [v2rayNG](https://github.com/2dust/v2rayNG) 的开源代码。感谢原项目及所有依赖项目的贡献者。Freedom 由本仓库独立维护，并非上述项目的官方发行版。

## 反馈

遇到问题时，请在 [Issues](https://github.com/qhs200312/Freedom/issues) 中提供 Freedom 版本、Android 版本、设备型号和可复现步骤。提交日志前请先移除订阅链接、服务器地址和其他敏感信息。
