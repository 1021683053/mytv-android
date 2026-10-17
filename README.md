<div align="center">
    <h1>电视直播（china-iptv）</h1>

[![Android Sdk Require](https://img.shields.io/badge/Android-5.0%2B-informational?logo=android)](https://apilevels.com/#:~:text=Jetpack%20Compose%20requires%20a%20minSdk%20of%2021%20or%20higher)

    <p>使用 Android 原生（Kotlin + Jetpack Compose）开发的 IPTV 直播播放软件</p>
</div>

## 功能

- **电视直播**：通过订阅 IPTV 直播源观看电视频道，支持多线路切换
- **多格式源解析**：支持 M3U、TXT、TVBox 等常见直播源格式
- **节目单（EPG）**：显示频道节目预告，支持多天节目单与预约
- **多播放内核**：Media3/ExoPlayer（HLS、RTSP、DASH）、IJKPlayer、WebView 直播源
- **频道收藏**：收藏常用频道，快速访问
- **频道搜索**：按名称搜索频道
- **多画面**：支持多频道同时观看
- **网页自定义设置**：局域网内访问 `http://<设备IP>:10481` 配置订阅源等
- **云同步**：通过 GitHub/Gitee Gist、WebDAV 等同步配置
- **主题与界面**：多种主题、经典/新版界面切换、密度与字体缩放

## 操作方式

> 遥控器操作方式与主流视频播放软件类似；

- 频道切换：使用上下方向键，或者数字键切换频道；屏幕上下滑动；
- 频道选择：OK键；单击屏幕；
- 线路切换：使用左右方向键；屏幕左右滑动；
- 设置页面：按下菜单、帮助键，长按OK键；双击、长按屏幕；

### 触摸键位对应

- 方向键：屏幕上下左右滑动
- OK键：点击屏幕
- 长按OK键：长按屏幕
- 菜单、帮助键：双击屏幕

## 说明

- 仅支持 Android 5 及以上
- 网络环境必须支持 IPv6（默认订阅源）
- 仅在部分电视设备上测试过，其他设备稳定性未知

## 构建

推送 `v*` 格式的 tag 即可通过 GitHub Actions 自动打包 APK，构建完成后在 Actions 页面下载。

## 更新日志

[更新日志](./CHANGELOG.md)

## 声明

此项目（电视直播 / china-iptv）是个人为了兴趣而开发，仅用于学习和测试。所用API皆从官方网站收集，不提供任何破解内容。

## 致谢

- [my-tv](https://github.com/lizongying/my-tv)
- [参考设计稿](https://github.com/lizongying/my-tv/issues/594)
- [live](https://github.com/fanmingming/live)
- 等等
