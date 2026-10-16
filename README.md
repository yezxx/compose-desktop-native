# maven — fork 的制品分支（GitHub Pages）

本分支只放**本 fork 相对上游 `bitsycore/compose-desktop-native` 有改动**的模块制品，
通过 GitHub Pages 提供给 yezai：`https://yezxx.github.io/compose-desktop-native`。

坐标与上游完全一致，靠仓库顺序覆盖（yezai 的 `settings.gradle.kts` 里有对应的 content 过滤）。

| 模块 | 版本 | 改动 |
| --- | --- | --- |
| `com.bitsycore.compose.ui:ui-text` / `ui-text-linuxx64` | 1.12.1-2 | 段落级 SkFontStyle 带上 wght 字重（中文加粗修复），见 main 分支 `f405692c` |

## 以后改别的模块 / 发新版本

1. 在 fork 的 `main` 上改代码（先同步上游：`git fetch upstream && git merge upstream/main`，
   `upstream` remote 指向 `bitsycore/compose-desktop-native`）。本地构建前需要同步 vendor 源码，见仓库 `TOOLING.md`。
2. 发到 mavenLocal（用 yezai 仓库 dev shell 的 JDK 25）：

   ```bash
   source <yezai>/.direnv/flake-profile-*.rc
   PUBLISH_VERSION=<version> ./gradlew :compose:ui:ui-text:publishLinuxX64PublicationToMavenLocal \
       :compose:ui:ui-text:publishKotlinMultiplatformPublicationToMavenLocal
   ```

3. 把 `~/.m2/repository/com/bitsycore/` 里对应模块、对应版本的文件原样拷进本分支同样的路径
   （新模块就建同样的目录结构），commit + push 本分支。
4. 注意 app 实际请求的版本号 = bridge 插件版本（目前 `1.12.1-2`）。app 升级插件版本时，
   这里要有对应版本的目录，否则 yezai 会静默回落到上游（就没有修复了）。

> 只放有改动的模块；其它模块仍由上游 `maven.bitsycore.com` 提供。
