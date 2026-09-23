# ClearApp

一个 LSPosed 模块，用来把 app 里用不到的东西去掉：多余的 tab、悬浮球、页面组件。

不做去广告 —— 只做「界面上这些我不看」。每条规则都挂在宿主自己的开关上，关掉之后对应的视图和
请求根本不会产生，而不是事后去隐藏，所以不会留下空位或半截界面。

## 功能

### 番茄免费小说 `com.dragon.read`

| 位置 | 处理 |
| --- | --- |
| 底部主 tab | 只保留 **书城 / 书架 / 我的**（去掉短剧、赚钱、分类） |
| 书城顶部子 tab | 去掉 **看剧 / 视频 / 商城** |
| 书架顶部子 tab | 去掉 **收藏** |
| 「我的」页 | 去掉 **金币 / 现金余额 / 微信提现 / 福利** 整块，以及底部 **推荐流** |
| 悬浮球 | 金币挂件、发帖球、继续看悬浮窗、冷启增长悬浮条 全部去掉 |

### 红果免费短剧 `com.phoenix.read`

| 位置 | 处理 |
| --- | --- |
| 底部主 tab | 只保留 **首页 / 剧场 / 我的**（去掉商城、赚钱） |
| 悬浮球 | 金币挂件（KMP 版 + 老式版）、一键领挂件、主页面悬浮窗、冷启增长悬浮条 全部去掉 |

「我的」页的金币和提现入口**没有动** —— 需求里没要求。

### 保留的东西

番茄听书的「边听边读」悬浮球**没有拦**。它是跳回阅读器继续听看的功能入口，不是引流组件。

## 环境

### 设备端

| 项 | 要求 |
| --- | --- |
| Android | 8.0+（API 24+） |
| Root | KernelSU / Magisk，需临时或永久 root |
| Xposed 框架 | **Vector 2.2** 或 **LSPosed**，Xposed API 101+（模块按 API 102 构建） |

实测环境：红米 K70 至尊版（Android 16）+ KernelSU（月虹提权助手临时 root）+ Vector 2.2。

### 适配的 app 版本

规则是照着**具体版本**的类名和方法名写的，下面这两个版本实测通过：

| app | 包名 | 版本 |
| --- | --- | --- |
| 番茄免费小说 | `com.dragon.read` | 7.3.7.33（versionCode 73733） |
| 红果免费短剧 | `com.phoenix.read` | 7.3.7.32（versionCode 73732） |

> **app 升级后部分规则可能失效。** 模块对这些情况是容错的：目标方法找不到就跳过那一条并写日志，
> 不会崩溃也不会影响别的规则。日志里搜 `跳过` 就能看出哪条失效了。

## 安装

1. 装 APK：
   ```bash
   adb install app-release.apk
   ```
2. 在 Vector / LSPosed 管理器里**启用模块**，作用域勾选 `com.dragon.read` 和 `com.phoenix.read`
3. 强制停止目标 app 再打开

模块自带 `staticScope`，作用域已经写死在这两个包上，命令行走一遍也行：

```bash
adb shell "su -c '/data/adb/lspd/cli modules enable io.github.wuqiao309.clearapp'"
adb shell "su -c '/data/adb/lspd/cli scope set io.github.wuqiao309.clearapp com.dragon.read/0 com.phoenix.read/0'"
```

> 装完模块后**第一次冷启可能仍跑的是旧版本**，Vector 需要一点时间重新读模块。再 force-stop 重启
> 一次即可，别以为是规则写错了。

## 排查

模块把自己挂上的每一条规则都写进日志，同时写一份到 logcat：

```bash
adb logcat -d | grep "ClearApp/"
```

关注三类行：

- `已挂载 <规则> -> <类>#<方法>` —— 找到了目标并挂上
- `跳过 <规则>：目标方法不存在` —— **这条在这个版本里失效了**
- `屏蔽 xxx` / `保留 xxx` —— 规则实际触发

## 从源码构建

| 工具 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle | 8.11.1（wrapper 自带） |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.20 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |

需要一个 Android SDK（platform-35 + build-tools 35.0.0），路径写在 `local.properties`：

```properties
sdk.dir=C\:\\Users\\<你>\\Android\\Sdk
```

```bash
export JAVA_HOME="C:\Program Files\Java\jdk-21"
./gradlew.bat assembleRelease
```

签名的密钥不进仓库。没配 `keystore.properties` 时 release 包会用 debug 签名 —— 能装能用，但换签名
覆盖安装会失败。要自己签名就照 `keystore.properties.example` 生成一份。

## 目录

```
app/src/main/kotlin/io/github/wuqiao309/clearapp/
  ClearAppModule.kt     入口：进程判定、装载对应 app 的规则
  HookManager.kt        挂 hook、记录、热重载时统一卸载
  ClassResolver.kt      按名字找类/方法/静态字段
  Fields.kt             读宿主对象字段
  ModuleLog.kt          日志（LSPosed + logcat 各一份）
  hooks/
    Rules.kt            规则数据：每个 app 拦哪些
    FanqieHooks.kt      番茄的规则装配
    HongguoHooks.kt     红果的规则装配
    BottomTabs.kt       底部主 tab（两个 app 共用）
    BookstoreTabs.kt    书城子 tab       ┐
    BookshelfTabs.kt    书架子 tab       ├ 番茄专属
    ProfilePage.kt      我的页           ┘
    FloatingBall.kt     悬浮球 / 悬浮窗（两个 app 共用）

docs/实现说明.md        每条规则挂在哪个方法上、为什么这么挂
```

改「拦哪些」只需要动 `Rules.kt`。

## 说明

- 只作用于自己设备上已安装的 app，不修改任何 app 的安装包。
- 规则基于对目标 app 的静态分析得出，app 升级后需要跟着维护。
- 请自行确认在当地使用 root / Xposed 类工具符合相关规定。
