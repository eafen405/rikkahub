# OurFairy Android

Fairy 的 Android 客户端，使用 Kotlin、Jetpack Compose 和 Material 3 Expressive。首页互动、远程会话和分组配置共用 Fairy 的主题与动画。

客户端负责身份、会话呈现、附件上传下载、录音转写、后台接收和通知。会话执行及系统配置由 OurFairy 后端提供。正文复用 Markdown、代码高亮、表格、公式、图片和 Mermaid 呈现。

## 构建

使用 JDK 21 和 Android SDK，SDK 版本由 Gradle 配置指定。将 SDK 路径写入未提交的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

```bash
./gradlew assembleDebug
./gradlew test lintDebug
./gradlew assembleRelease -Pourfairy.identityEndpoint=https://your-backend.example
```

Debug 的默认后端为 `http://10.0.2.2:3080`；真机使用可访问的后端地址覆盖 `ourfairy.identityEndpoint`。Release 要求显式配置地址，签名读取 `local.properties` 中的 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。

CI 的后端地址来自仓库变量 `OURFAIRY_IDENTITY_ENDPOINT`，签名来自现有的 `KEY_BASE64` 和 `SIGNING_CONFIG` secrets。

## 源码入口

- `app`：Fairy 页面、远程会话和 Android 平台接入。
- `ui`：Markdown 解析、表格和网页内容呈现。
- `highlight`：代码高亮。
- `app:baselineprofile`：启动测量与 profile 生成。

应用启动装配在 `RikkaHubApp.kt` 和 `di/`，导航在 `RouteActivity.kt`。`data/remote/` 维护会话归属、发送确认、同步水位和通知记账；`ui/identity/RemoteTranscript.kt` 承接消息呈现与阅读位置。

产品规格、术语和版本组合由 [OurFairy](https://github.com/eafen405/OurFairy) 管理。本轮客户端运行组成说明见 [fairy-client-runtime.md](docs/references/fairy-client-runtime.md)。

## 来源与许可证

客户端派生自 [RikkaHub](https://github.com/rikkahub/rikkahub)，研究基线提交为 `0966502c4d65c1ee4b2a3e49a19703bb1978016c`，遵循仓库中的 [AGPL-3.0 许可证](LICENSE)。Fairy 视觉实现包含 Fairybot 的 MIT 许可代码，声明位于 [FAIRYBOT_MIT.txt](app/src/main/assets/licenses/FAIRYBOT_MIT.txt)。各依赖保留其原许可证。
