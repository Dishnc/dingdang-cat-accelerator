DdmNG_APP_LegacyXTLS_V1.2.0.4_fixed_hidden_backend_base_patch

本补丁用途：
1. 修复 V1.2.0.3 在 GitHub 打包阶段的 Kotlin 编译失败。
   日志中的核心错误是 DingdangLoginActivity.kt 516 行附近 Expecting member declaration，
   后续 statusText / connectionBadge / accent / resources 等报错是语法破坏后的连锁错误。
2. 保持需求：后端服务地址不让用户填写，APP 内默认后端固定为：
   https://buy.aisuper.top
3. 尽量保持前面已经做好的功能，不大面积覆盖工程文件，只自动修改：
   V2rayNG/app/src/main/kotlin/com/v2ray/ang/ui/DingdangLoginActivity.kt

Windows 使用方法：
1. 解压本补丁包。
2. 把补丁包内的所有文件复制到你的 GitHub 仓库根目录，也就是能看到 V2rayNG 文件夹的位置。
3. 双击 apply_patch_windows.bat。
4. 脚本会自动备份原文件到 ddmng_patch_backups 目录。
5. 提交修改后重新触发 GitHub Actions 打包。

Linux / GitHub Runner 手动使用：
    bash apply_patch_github_linux.sh /path/to/repo

建议验证命令：
    cd V2rayNG
    ./gradlew :app:compileDebugKotlin

注意：
- 本补丁包不替换整个登录页文件，而是进行保守修复。
- 如果你的仓库里 DingdangLoginActivity.kt 已经被手动大幅改动，脚本仍会先备份原文件。
- 如果打包仍失败，请把新的完整 GitHub 日志发回来，我会按新的行号继续修。 
