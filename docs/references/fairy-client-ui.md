# Fairy 客户端页面与阅读行为

本轮页面范围是首页互动、会话对话和设置配置。会话列表由聊天左上角展开；新建、主会话和历史会话集中在列表中。选择会话替换当前聊天目标，首页与通知仍可进入同一聊天页面。

Fairy 的展开窗口叠在固定顶栏下方。思考、输出与后台活动依据现有会话事实呈现；窗口变化不改变消息视口的布局高度。图片和文件选择放在输入区的附件菜单，语音转写完成后返回可编辑草稿。

流式数据持续更新，阅读位置由视口独立控制：在底部时跟随新内容，用户离开底部后保持阅读位置。当前仍沿用生成期间纯文本、完成后 Markdown 的呈现方式。相关行为的验证入口是 `FairyPagesTest`、`RemoteTranscriptViewportTest` 和 `RemoteReadingTransitionTest`。

后续渲染缓存的约束：复用已计算且仍然有效的结果，结合可见性、最近使用、内容失效和页面生命周期管理保留与回收；系统内存紧张时释放可重建数据。以命中率、占用、回收后的重算成本和实际阅读流畅度校准策略。缓存优化尚待独立实施与量测。

## 本轮验证（2026-10-19）

```bash
ANDROID_HOME=/home/eafen405/Android/Sdk ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.ui.fairy.FairyPagesTest' \
  --tests 'me.rerere.rikkahub.ui.identity.*' \
  --tests 'me.rerere.rikkahub.data.remote.*' \
  :app:assembleDebug :app:lintDebug --offline --no-configuration-cache \
  --console=plain --max-workers=4 --parallel
```

200 项相关 Android 测试通过，`assembleDebug` 完成。页面测试在 Robolectric 中覆盖首页两个入口、抽屉切换与新建、当前会话草稿保留、失效会话恢复、思考动画变化时的视口与输入框位置，以及流式增长期间的底部跟随和离底阅读。测试截图可通过 `FAIRY_UI_SCREENSHOTS` 指定输出目录。

组合命令最终因 Lint 失败退出：149 个错误（权限 1、系统版本兼容 1、不可观察的地区读取 3、Activity 转型 1、旧页面资源读取 47、缺失翻译 96）。按 `app/build/reports/lint-results-debug.xml` 的位置核对，错误未落在本轮修改的文件中；这不表示全仓静态检查已经通过。HTML 报告位于 `app/build/reports/lint-results-debug.html`。

OurFairy 仓库的 `npm test` 为 133 项通过，`npm run combo:verify` 通过；组合校验核对的是已记录提交，本轮客户端改动仍在工作区。

未启动模拟器，尚未完成真机两个入口的空屏复验或设备帧率量测。可安装产物是 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
