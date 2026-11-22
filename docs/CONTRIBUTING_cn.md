为 SnowBall 贡献代码
===========

[English](CONTRIBUTING.md) | **中文**

我们很开心您想为我们的项目做出贡献！一般来说，我们对PR的审核是十分宽松的；
但是如果您可以遵守下列的规则，我们可以更快地完成审核。

## 使用个人账户进行 Fork

我们会定期尝试合并已有的 PR，如果有一些小问题，会尝试帮您解决这些问题。

但是如果您使用了组织账号进行 PR，我们就不能对您的 PR 进行修改了。因此我们只能关闭你的PR然后进行手动合并。

所以，请不要使用组织账号进行 Fork！

您可以看看 [这个 Issue](https://github.com/isaacs/github/issues/1681) 来了解一下我们为什么无法修改组织账号的 PR。

## 开发环境

在开始开发之前，您首先需要拥有以下软件作为开发环境：

- `git`
- JDK 25（使用项目的 Gradle Wrapper）。

如果你使用 Windows 系统进行开发，那么你可以使用 `WSL` 来加速构建。

## 了解补丁（Patches）

克隆你的 Fork 后，先在主仓库根目录运行 `./gradlew applyAllPatches`。SnowBall 的 Gradle 模块和任务生成的 Git 源码树并不是一回事。请按源码归属选择编辑位置：

| 源码归属 | 编辑和提交位置 | 在主仓库根目录执行的重建命令 | 纳入主仓库的文件 |
| --- | --- | --- | --- |
| SnowBall 自有源码 | 主仓库中的 `snowball-api/src/main`、`snowball-server/src/main` 或相应测试源码 | 不用重建补丁 | 源码本身 |
| Paper API（含 Bukkit 和 Spigot API） | `paper-api/` | `./gradlew :rebuildPaperApiPatches` | `snowball-api/paper-patches/` |
| Paper 服务端（含 CraftBukkit） | `paper-server/` | `./gradlew :snowball-server:rebuildPaperServerPatches` | `snowball-server/paper-patches/` |
| Minecraft（NMS） | `snowball-server/src/minecraft/java/` | `./gradlew :snowball-server:rebuildMinecraftPatches` | `snowball-server/minecraft-patches/` |

如果要修改生成的模块构建脚本（`snowball-api/build.gradle.kts` 或 `snowball-server/build.gradle.kts`），编辑脚本后在主仓库根目录运行 `./gradlew :rebuildPaperSingleFilePatches`。提交对应的 `build.gradle.kts.patch`，而不是生成的脚本。

在补丁源码树暂存或提交前，先运行 `git -C <源码树> rev-parse --show-toplevel`，确认输出就是该源码树。仅在 `snowball-api/` 或 `snowball-server/` 里运行 Git，仍然操作的是主仓库。各生成树分别有自己的 `base`（上游源码）和 `file`（文件补丁层）标签；功能补丁提交位于 `file` 之后，可用 `git -C <源码树> log --oneline file..HEAD` 查看。不要把主仓库变基到这些标签上。

## 新增或修改功能补丁

1. 在上表对应的生成树中编辑，只暂存本次涉及的文件，再运行 `git -C <源码树> commit -m "说明改动"`。
2. 若要更新现有功能补丁而不是新增一个，请改用 `git -C <源码树> commit --fixup <补丁提交>`，然后执行 `git -C <源码树> rebase -i --autosquash file`。核对变基计划，如有冲突则解决后再继续。
3. 回到主仓库根目录，对每个修改过的源码树执行对应的重建命令。运行 `git status` 和 `git diff` 检查补丁；重建可能会改写无关补丁的元数据，请仅将预期改动纳入 PR。
4. 确认生成树中的改动已保存到主仓库跟踪的补丁后，再运行 `./gradlew applyAllPatches` 验证补丁能否应用，并运行相关测试。最后在主仓库提交补丁与直接跟踪的 SnowBall 源码改动，不要提交生成的 Git 树或缓存。
