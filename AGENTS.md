# OurFairy Android

这是 OurFairy 的 Android 客户端。产品规则、术语与版本组合参见相邻 `../OurFairy/` 的 `AGENTS.md`、`CODING_STANDARDS.md`、`GLOSSARY.md`；跨仓集成时再读取 `work/implementation/CONVENTIONS.md` 和 `combo.json`。

## 实现

- 围绕首页互动、会话对话、分组配置实现产品能力。代码是否保留以当前产品用途和维护收益判断。
- `data/remote/` 中的 `AccountSession`、`OwnedSessionState` 和 `RemoteReceiveState` 管理身份、会话归属与接收；页面订阅其状态。会话激活包含回复、后台任务及投递输出。
- `ChatSyncStore` 的持久消费水位、待确认发送和通知记账用于重连恢复；消息正文来自远程会话。
- 修改呈现时保留原文及最终格式语义：输出期间可显示纯文本，结束、停止和错误后的正文使用 Markdown；阅读位置由会话呈现维护。
- 装配与依赖以 `settings.gradle.kts`、`app/build.gradle.kts` 和 `di/` 为准。新增依赖须对应当前功能。

## 界面

根主题是 `ui/fairy/FairyAppTheme.kt` 的 Material 3 Expressive 主题。沿用 Fairy 视觉、现有窗口和折叠配置组，使用 expressive motion、较大圆角及整行点击区域。

显示偏好只由 `DisplayPreferencesStore` 管理。按当前页面需要扩展本地偏好。

## 验证

```bash
ANDROID_HOME=/home/eafen405/Android/Sdk ./gradlew test lintDebug assembleDebug
ANDROID_HOME=/home/eafen405/Android/Sdk ./gradlew assembleRelease -Pourfairy.identityEndpoint=https://your-backend.example
```

测试通过、APK 构建和设备验收分别报告。涉及包体时读取实际 APK 和 merged manifest；比较时说明构建类型、ABI 和源码基线。

## 文案

现有字符串用 `stringResource`。未要求本地化时，优先实现功能。需要 `locale-tui` 操作时读取 `locale-tui-localization` skill。
