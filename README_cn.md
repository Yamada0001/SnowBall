# SnowBall

[English](README.md) | **中文**

SnowBall 是基于 [Leaves](https://github.com/LeavesMC/Leaves) 和 [Paper](https://github.com/PaperMC/Paper) 的 Minecraft Java 版服务端，保留原版机制修复、假人及可选性能优化。当前游戏版本为 **26.3**，Paper 的源码提交固定在 [gradle.properties](gradle.properties)。

## 构建与运行

安装 JDK 25，然后在仓库根目录运行：

```bash
./gradlew applyAllPatches
./gradlew :snowball-server:createLeavesclipJar --rerun
bash scripts/GetReleaseInfo.sh --artifact-path
```

Windows 可以将 `./gradlew` 换成 `gradlew.bat`，最后一条命令在 Git Bash 中执行，也可以直接查看 `snowball-server/build/libs`。可运行的服务端包名为 `SnowBall-{游戏版本}-{Git 短提交号}.jar`，例如 `SnowBall-26.3-2c62d77.jar`。从未提交的工作区构建时，文件名仍使用当前 HEAD 的短提交号。

也可以从本仓库的 Releases 页面下载已发布的构建。运行 `java -jar SnowBall-26.3-2c62d77.jar --nogui`，将示例文件名换成实际下载或构建的文件名。

升级前请备份服务端。原有的 `config/leaves.yml` 和 `config/SnowBall.yml` 都应保留；`/leaves` 命令仍然可用。若旧服务端只识别旧格式的发布文件名，第一次升级到 SnowBall 需要手动换包。

## 插件开发

Gradle 模块为 `snowball-api` 和 `snowball-server`。只使用 Bukkit 或 Paper API 的插件无需修改导入；使用旧扩展 API 的插件要把导入更新为 `org.snowballmc.snowball` 并重新编译，旧插件二进制不会自动映射到新包名。

应用补丁后，可用 `./gradlew :snowball-api:publishToMavenLocal` 发布到本地 Maven。在插件工程中添加 `mavenLocal()` 和以下依赖，版本号与 [gradle.properties](gradle.properties) 保持一致：

```kotlin
dependencies {
    compileOnly("org.snowballmc.snowball:snowball-api:26.3-R0.1-SNAPSHOT")
}
```

Maven 版本后缀不是 Paper 上游的发布渠道。GitHub Releases 用于分发；只有显式设置 `snowballMavenUrl` 时才配置远程 Maven 发布，所用凭据变量为 `SNOWBALL_MAVEN_USERNAME` 和 `SNOWBALL_MAVEN_PASSWORD`。

通过 `./gradlew :snowball-api:test :snowball-server:test` 验证改动。补丁流程见[贡献须知](docs/CONTRIBUTING_cn.md)，许可与上游署名见 [LICENSE](LICENSE.md)。

## 鸣谢

[Leaves](https://github.com/LeavesMC/Leaves) 是服务端的基础；[Leaf](https://github.com/Winds-Studio/Leaf) 提供了快速路径和修复，并为异步寻路提供了思路；[EtheriumMC](https://github.com/deivaxxx/EtheriumMC) 为独立实现的实体剔除和传感器排序实验提供了思路。

共审查了 20 个模组：9 个已移植、3 个由上游覆盖、5 个未移植、3 个暂缓。[早期 19 个模组的审查记录](docs/optimization-mod-porting-spec.md#47-最终裁决总表v13)不含后来加入的 BiomeSpy。[MoePus 的 BiomeSpy 26.1 源码](https://github.com/MoePus/BiomeSpy/tree/77249eff02fb7cf9e971108309cfd2337753fe22)为 SnowBall 在 26.3 上独立实现的生物群系定位与逐列采样提供了思路。
