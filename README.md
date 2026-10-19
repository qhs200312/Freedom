<div align="center">
  <img src="V2rayNG/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="Freedom 应用图标">
  <h1>Freedom</h1>
  <p><strong>Xray + sing-box 双内核 Android 客户端</strong></p>
  <p>一套界面，管理节点、路由与连接。为不同协议，选择合适的内核。</p>
  <p>
    <a href="https://github.com/qhs200312/Freedom/releases/latest"><img src="https://img.shields.io/github/v/release/qhs200312/Freedom?display_name=tag&amp;style=flat-square&amp;color=0e7490" alt="Freedom 正式版"></a>
    <img src="https://img.shields.io/badge/Android-7.0%2B-15803d?logo=android&amp;logoColor=white&amp;style=flat-square" alt="Android 7.0 及以上">
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-57606a?style=flat-square" alt="GPL-3.0 许可证"></a>
  </p>
  <p>
    <a href="https://github.com/qhs200312/Freedom/releases/latest"><strong>下载最新版</strong></a>
    &nbsp;&nbsp; <a href="#双内核">双内核</a>
    &nbsp;&nbsp; <a href="https://github.com/qhs200312/Freedom/releases">版本记录</a>
    &nbsp;&nbsp; <a href="https://github.com/qhs200312/Freedom/issues">问题反馈</a>
  </p>
</div>

---

Freedom 是一款独立维护的 Android 网络代理与连接管理工具，集成 **Xray-core** 与 **sing-box**。共享节点、订阅和操作界面，分别管理两套内核的配置与运行，让协议选择、连接状态和日常管理保持清晰。

## 双内核

两套引擎，一套连接体验。无需维护两份订阅，也无需在不同应用之间切换。

| 内核 | Freedom 2.8.0 集成版本 | 默认选择 |
| :--- | :--- | :--- |
| **Xray-core** | `26.10.17` | 按协议与配置兼容性选择 |
| **sing-box** | `1.14.3` | Hysteria2、AnyTLS |

**按协议设置默认值，按节点指定首选内核。** 可选择自动、Xray 或 sing-box；自动模式跟随协议默认设置。两套运行时独立处理配置、启动、停止和网络恢复，共用订阅管理、通知与流量展示。

支持 **VMess、VLESS、Trojan、Shadowsocks、SOCKS、HTTP、WireGuard、Hysteria2、AnyTLS**，以及实验性 **Nowhere v3.0.0** 节点。Hysteria2 支持 Salamander 混淆、Brutal 带宽参数和端口跳跃，具体能力取决于所选内核、节点参数与服务端配置。Nowhere 使用独立本地桥接，并由 Xray 处理路由。

> [!NOTE]
> 内核选择会检查配置兼容性，包含 sing-box 暂不兼容参数的节点会使用 Xray。双内核不是同时运行两条代理连接，也不等同于连接失败后自动切换内核。

## 日常连接

| 能力 | 体验 |
| :--- | :--- |
| **连接仪表盘** | 地图、出口 IP、位置与延迟，实时速率和累计流量集中呈现。 |
| **液态玻璃界面** | 底部导航支持背景模糊、折射与可拖动指示器，适配亮色和深色主题。 |
| **节点与订阅** | 订阅分组、本地节点、扫码和剪贴板导入；独立节点流量统计、Ping 与实际连接测试。 |
| **自定义节点分流** | 为网站、域名或应用选择订阅/本地节点，未指定的代理流量沿用当前节点。 |
| **路由与 DNS** | 预设及自定义路由、分应用代理、本地 DNS、国内外 DNS 分流和非代理 UDP 控制。 |
| **多种运行模式** | Android VPN、Root TUN 与 TProxy，按设备权限和网络环境选择。 |
| **网络恢复** | 按当前内核处理网络变化，提供可选的锁屏断流守护与系统自启动设置入口。 |
| **快速操作** | 快捷设置磁贴、桌面组件、开机连接和可选的启动时订阅更新。 |

## 下载与升级

**[从 GitHub Releases 下载正式版](https://github.com/qhs200312/Freedom/releases/latest)**

| 项目 | 要求 |
| :--- | :--- |
| 系统 | Android 7.0 及以上 |
| 当前发布架构 | `arm64-v8a` |
| 安装包 | `Freedom_<version>-fdroid_arm64-v8a.apk` |
| 文件校验 | 同名 `.apk.sha256` 文件 |

Freedom `2.8.0` 使用原正式签名和递增的内部版本码。请从本仓库下载、核对 SHA-256，并通过覆盖安装升级；不要先卸载应用，以免丢失本地配置。

完整更新内容见 [版本记录](https://github.com/qhs200312/Freedom/releases)。

## 配置与连接

1. 安装应用，按提示授予 VPN 与通知权限。
2. 使用订阅链接、二维码、剪贴板或手动方式添加节点。
3. 按需在设置中指定各协议的默认内核，或在节点编辑页指定首选内核。
4. 选择节点，在首页连接；需要后台或开机运行时，再按设备要求放行相应权限。

在路由设置中添加网站或应用规则，并为规则选择目标节点。分应用指定节点需要 Android 10 及以上的普通 VPN 模式，应用须纳入 VPN；修改后重新连接。独立节点流量是本机统计，不代表订阅套餐余额。

Freedom 不提供代理节点或订阅服务。请使用你信任且有权使用的网络服务，并遵守所在地法律法规。

## 隐私与控制

- **配置留在设备上。** 节点、订阅和设置本地保存，不要求注册 Freedom 账号。
- **网络请求有明确用途。** 代理连接、DNS 解析、订阅更新、连通性检测、出口 IP 查询和版本检查会访问对应服务；第三方服务不属于 Freedom 的控制范围。
- **分享日志前先检查。** 日志可能包含服务器地址等敏感信息，提交反馈前请移除订阅链接、凭据和其他个人信息。

详见 [Freedom 隐私政策](CR.md)。

<details>
<summary><strong>路由资源</strong></summary>

Xray 可使用兼容的 `geoip.dat` 与 `geosite.dat`，sing-box 使用其配置所需的规则集。请根据所选内核使用相应格式，两者不可直接互换。

部分路由资源由应用下载或导入，外部目录可能因系统而异：

```text
Android/data/com.v2ray.ang.fdroid/files/assets
```

相关资源：[Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)、[Loyalsoldier/geoip](https://github.com/Loyalsoldier/geoip)。

</details>

<details>
<summary><strong>开发与构建</strong></summary>

Android 工程位于 `V2rayNG`，构建需要 JDK 17、Android SDK 和工程依赖的原生内核库。

```bash
cd V2rayNG
./gradlew assembleFdroidRelease -PABI_FILTERS=arm64-v8a
```

Windows 使用 `gradlew.bat`。自行构建的 APK 需要使用自己的签名证书；仓库不包含 Freedom 发布私钥，自签名版本不能直接覆盖 Freedom 正式签名版本。

当前版本的内核对应源码、依赖锁定和构建说明位于
[`V2rayNG/native/cores`](V2rayNG/native/cores)，Nowhere 对应源码及许可证位于
[`V2rayNG/native/nowhere`](V2rayNG/native/nowhere)。
先按说明构建原生组件并准备 `app/libs`，再执行 Android 构建。历史的根目录
`AndroidCoreBundle` 和云端构建工作流尚未迁移到这套本地构建流程。

运行时架构见 [双内核技术说明](docs/core-runtime.md)。

</details>

## 开源与致谢

Freedom 以 [GPL-3.0](LICENSE) 发布，集成 [Xray-core](https://github.com/XTLS/Xray-core) 与 [sing-box](https://github.com/SagerNet/sing-box)，并使用、改造了 [v2rayNG](https://github.com/2dust/v2rayNG) 的开源代码。感谢这些项目及所有依赖项目的贡献者，各组件遵循其各自许可证。

Freedom 由本仓库独立维护，不是上述项目的官方发行版，也不代表其开发团队。

## 问题反馈

请通过 [GitHub Issues](https://github.com/qhs200312/Freedom/issues) 提供应用版本、内核选择、Android 版本、设备型号和复现步骤。涉及协议兼容性的问题，请同时说明节点协议与传输方式，勿公开提交服务器凭据。
