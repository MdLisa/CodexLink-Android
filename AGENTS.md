# CodexLink Android 项目约定

## 后续更新和发版

- 关联电脑后端仓库名为 `CodexLink-Bridge`。本机仓库与工具路径先读被 Git 忽略的 `LOCAL_DEVELOPMENT.md`（如存在）；否则从当前工作区及工具配置中定位。
- 用户希望助手完成修改、验证、打包与所请求的发布步骤。处理发版前，先读后端仓库的 `ANDROID_UPDATES.md`（含完整操作清单），并查阅本仓库 `PUBLISHING.md`。若本机路径变化，先定位对应后端仓库；不要另起一套发布机制。
- 只有要求修改/打包时先交付可验收结果；用户要求发布新版时，再使用既有后端发布脚本。不要把“只修改代码”当作已经授权上线分发。
- 保持 `com.xukaizhe.codexlink` 包名和原签名。当前历史版本使用本机 Android Debug 密钥，不能重建或替换密钥。以实际构建配置及后端 `updates/android/latest.json` 确定下一版 versionCode，必须递增，不复用已发布内部版本号。
- 验证后同步有版本名的 APK 至后端仓库根目录，并通过后端 `scripts/publish-android-update.mjs` 生成分发文件；不要仅复制 APK 后宣称发布成功，也不要删除版本清单来绕过脚本的防降级/重复发布检查。
- 固定更新源是 `https://codex.shbj.site/updates/android/`，复用现有隧道。用户暂不需要额外测试环境或 PC 自动更新。
- 单纯发布 APK 不需要重启电脑后端。不要为此停止真实服务，不清除手机数据、设备密钥或既有连接。
- 构建验证命令及本机 JDK/SDK 路径见后端 `ANDROID_UPDATES.md`。旧验收记录不代表新版本已测试；区分自动化结果和真机系统安装结果。

## Git 授权

- 可提交文档不写真实姓名、Windows 用户名或本机绝对路径；机器专属位置放在被 Git 忽略的 `LOCAL_DEVELOPMENT.md`。不得提交密码、令牌、运行数据和签名私钥；提交前核对实际收录文件，不能只依赖 .gitignore。

- **commit 和 push 均须用户针对本次操作明确授权。** 修改、打包、发布不自动包含 Git 授权，历史版本授权不延续。
- 提交前展示本次范围和英文 commit message；未获授权不提交、不推送。
