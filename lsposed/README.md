# LSPosed 模块仓库提交材料

这个目录里的东西**不是给源码仓库用的**，是提交到 LSPosed 官方模块仓库时要放过去的。

## LSPosed 官方仓库的机制

LSPosed 的模块仓库（<https://modules.lsposed.org>）**不接受源码**，官方原话：

> Please do not upload your source code here, we don't need your source code

它的流程是：

1. 在 <https://modules.lsposed.org/submission/> 开一个 issue，标题写成 `[submission] <包名>`
2. bot 在 `Xposed-Modules-Repo` 组织下建一个**以包名为名**的仓库，并把维护者权限发给你
3. 你往那个仓库里只放两个文件：`SUMMARY`（一句话简介，显示在首页）和 `README.md`（完整说明）
4. APK 走 Release，不放仓库

这个源码仓库（`wuqiao309/clear-tomato`）和那个仓库是**两个独立的仓库**，源码留在这里。

## 收录要求（对照检查）

| 要求 | 本项目 |
| --- | --- |
| 包名用自有域名，或 `io.github.<用户名>` | ✅ `io.github.wuqiao309.clearapp` |
| 仓库名 = 包名 | ⬜ 由 bot 建 |
| 仓库描述 = 模块名 | ⬜ `ClearApp` |
| 至少一个带 APK 附件的 release | ⬜ 见下 |
| Release tag 格式 `[versionCode]-[versionName]` | ⬜ `1-1.0.0` |
| 仓库内有 `SUMMARY` + `README.md` | ✅ 本目录 |

## 要提交的文件

- [`SUMMARY`](SUMMARY) —— 直接放到 LSPosed 仓库根目录
- `README.md` —— 用源码仓库根目录的那份（整份复制过去即可）

## Release tag

LSPosed 的 bot 期望 tag 形如 `[versionCode]-[versionName]`。本项目 v1.0.0 的 versionCode=1、
versionName=1.0.0，所以 tag 是：

```
1-1.0.0
```

> bot 会在你发布 release 后自动把 tag 改成这个格式，但自己先写对更省事。
