Contributing to SnowBall
===========

**English** | [中文](CONTRIBUTING_cn.md)

We're glad that you want to contribute to our project!  
In general, our review of pull requests is very lenient.  
And if you can follow the rules below, we can complete the review faster.

## Please fork using your personal account

We regularly merge existing PRs.  
If there're some small problems, we'll help you solve them by editing your PR.

But, if your PR is from a organization, we can NOT edit your PR, so we must merge your PR manually.

So, don't use orgnization accounts for fork!

See also [This issue](https://github.com/isaacs/github/issues/1681), and then you'll know why we can't edit PRs from organizations.

## Development Environment

Before coding, you need these softwares / tools as Dev Environment.

- `git`
- JDK 25 (use the Gradle wrapper).

If you're using Windows Operating System, you can use `WSL` to speed up building.

## Understanding "Patches"

After cloning your fork, run `./gradlew applyAllPatches` from the repository root. SnowBall's Gradle modules and the generated Git source trees are not the same thing. Choose where to edit based on who owns the source:

| Source | Edit and commit in | Rebuild from the repository root | Tracked output |
| --- | --- | --- | --- |
| SnowBall-owned source | `snowball-api/src/main`, `snowball-server/src/main`, or the corresponding test sources, in the main Git repository | No patch rebuild | The source files themselves |
| Paper API (including Bukkit and Spigot API) | `paper-api/` | `./gradlew :rebuildPaperApiPatches` | `snowball-api/paper-patches/` |
| Paper server (including CraftBukkit) | `paper-server/` | `./gradlew :snowball-server:rebuildPaperServerPatches` | `snowball-server/paper-patches/` |
| Minecraft (NMS) | `snowball-server/src/minecraft/java/` | `./gradlew :snowball-server:rebuildMinecraftPatches` | `snowball-server/minecraft-patches/` |

For changes to the generated module build scripts (`snowball-api/build.gradle.kts` or `snowball-server/build.gradle.kts`), edit the script and run `./gradlew :rebuildPaperSingleFilePatches` from the root. Submit the corresponding tracked `build.gradle.kts.patch`, not the generated script.

Before staging or committing in a patch tree, run `git -C <tree> rev-parse --show-toplevel` and confirm it reports that exact tree. Running Git in `snowball-api/` or `snowball-server/` alone selects the main repository. The generated patch trees have their own `base` (upstream source) and `file` (file patch layer) tags. Feature patch commits are above `file`; inspect them with `git -C <tree> log --oneline file..HEAD`. Do not rebase the main repository onto these tags.

## Adding or modifying feature patches

1. Edit in the generated tree listed above. Stage only the relevant files there and commit with `git -C <tree> commit -m "Describe the change"`.
2. To update an existing feature patch rather than adding one, use `git -C <tree> commit --fixup <patch-commit>` instead, then run `git -C <tree> rebase -i --autosquash file`. Review the rebase plan and resolve any conflicts before continuing.
3. From the main repository root, run the rebuild task for every modified patch tree. Run `git status` and `git diff` there to inspect the tracked patch outputs; rebuilds can rewrite unrelated patch metadata. Include only the intended changes in your PR.
4. Once your generated-tree edits are safely captured in the tracked patches, run `./gradlew applyAllPatches` again to verify they apply, then run the relevant tests. Commit the tracked patches and any directly tracked SnowBall source changes in the main repository. Do not submit generated Git trees or cache files.
