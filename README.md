# 手表 QQ 音乐播放器

方形 Wear OS 表盘上的在线音乐播放器。纯原生 Android View 实现，不使用 WebView，APK 不到 100KB。

<img src="docs/player-volume.png" width="200" /> <img src="docs/lyric.png" width="200" /> <img src="docs/login-wx.png" width="200" />

## 主要功能

- **扫码登录** — QQ / 微信扫码，登录态存在本地，重启 App 不用重扫
- **在线听歌** — 拖动进度条、上下滑切歌，表冠旋转滚动列表
- **滚动歌词** — 独立歌词页，当前行高亮、相邻行渐隐
- **搜索与歌单** — 关键词搜索、热门歌单、歌单详情、我的歌单
- **收藏** — 与云端「我喜欢」合并去重
- **离线缓存** — 播放页一键下载，断网可听，可管理删除
- **音量调节** — 底部滑块直接调系统媒体音量，配静音键
- **后台播放** — 前台服务 + MediaSession，息屏继续放，控制中心可控

## 安装

手表开好无线调试，然后：

```bash
./install.sh 192.168.1.50        # 换成手表 IP
```

或者手动：

```bash
adb connect 192.168.1.50:5555
adb install -r watch-music.apk
```

`-r` 覆盖安装会保留登录态、收藏和离线缓存，不需要卸载重装。

## 构建

不依赖 Gradle，用 `aapt2 → javac → d8 → zipalign → apksigner` 手工串起来：

```bash
export ANDROID_SDK=/path/to/android-sdk    # 需要 build-tools 34.0.0 与 android-34 平台
cd android-native
./build-native.sh                           # 调试打包，版本号不变
./build-native.sh --release                 # 交付打包，成功后版本号 +1
```

签名密钥放在 `android-native/keystore/watch.jks`，构建脚本存在即复用并打印指纹。**这个文件不进仓库**，自己打包时务必固定保存，否则签名一变就只能卸载重装。

## 目录结构

```
android-native/    独立版源码（纯 View，直连接口）—— 装这个
android/           WebView 版外壳（需配合 server.js 跑在电脑上）
public/            WebView 版前端页面
server.js          单端口服务：静态托管 + 接口代理
qq.js              接口适配层（Cookie 罐、双通道扫码、取流、歌词）
install.sh         一键覆盖安装
```

`android-native` 里几个关键文件：`MainActivity.java`（界面）、`Player.java`（播放队列）、`PlayService.java`（前台服务）、`Qq.java`（接口层）、`LyricView.java` / `BarView.java`（歌词与进度条自绘）。

## 注意

- 接口是 QQ 音乐和微信网页端的私有接口，不是开放平台 API，对方改参数就会失效。
- 未登录拿不到播放地址；需要有账号，部分曲目还要求会员。
- 收藏只写本地，不会回写云端。

## 免责声明

- 与腾讯、QQ 音乐、微信无任何关联，非官方产品。
- 仓库不含任何账号凭据、Cookie、签名密钥，请勿提交自己的登录态。
- 仅供个人学习与自己设备的自用，禁止用于公开服务、商业分发或其他侵犯版权与服务条款的用途。
- 使用本代码产生的后果由使用者自行承担。如权利人认为内容不妥，请通过 Issue 联系，会及时处理。

## License

[MIT](LICENSE)
