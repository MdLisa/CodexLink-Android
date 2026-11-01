# CodexLink Android

个人安卓客户端，连接自己电脑上的 Codex 手机后台。Kotlin / Jetpack Compose，最低 Android 8.0，当前版本 0.3.0。

## 使用

1. 在电脑后台设置账号密码、允许操作的项目目录，启动后台与外网连接。
2. 手机填写自己的电脑后台地址、账号和密码。首次登录后，在电脑控制台核对并批准手机，然后手机再次登录。
3. 查看或新建聊天，发送文字和图片，接收实时进度、处理授权和停止任务。

手机保存加密的连接凭据，不保存登录密码；设备私钥保存在 Android Keystore。更换服务器地址时填写自己的固定域名或电脑内网地址，项目没有内置开发者的服务器账号。

## 构建与源码

使用 Android Studio 打开项目，通过本机 SDK 和 JDK 构建。Gradle wrapper、版本目录和源码应提交；local.properties、构建缓存、APK、IDE 私有设置及签名密钥已由 .gitignore 排除。

安装包可以单独放在 GitHub Releases。覆盖升级应沿用你自己的签名，否则 Android 可能要求卸载原应用，导致本机设备密钥丢失并需要重新批准。

更多本地上传准备说明见 PUBLISHING.md，开发待办见 TODO.md。
