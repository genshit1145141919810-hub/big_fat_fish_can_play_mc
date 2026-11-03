# 发布清单（Modrinth / CurseForge）

这份文件是给"人"看的操作步骤；脚本已经就绪，缺的只是平台凭据与两个项目页。

## 0. 先决条件

| 项目 | 状态 |
|---|---|
| jar 构建 | ✅ `build/libs/big-fat-fish-can-play-mc-0.4.0-alpha.2.jar` |
| 发布前检查 | ✅ `tools/release-check.ps1` 输出 `CHECK OK - ready to publish` |
| 发布包 | ✅ `dist/0.4.0-alpha.2/`（jar + sources + README + LICENSE + CHANGELOG + checksums + 商店文案） |
| 上传脚本 | ✅ `tools/publish.ps1`（支持 `-DryRun`） |
| Modrinth 项目页 | ❌ 需要你在网站上创建（API 不允许建项目） |
| CurseForge 项目页 | ❌ 同上 |
| 平台 Token | ❌ 需要你提供 |

## 1. 创建 Modrinth 项目（约 3 分钟）

<https://modrinth.com/create> → **Project**，填写：

- **Name**：`大肥鱼能玩MC` / `Big Fat Fish Can Play MC`
- **Slug**：`big-fat-fish-can-play-mc`
- **Summary**：`A DeepSeek-powered Minecraft companion that plays on a real second account.`
- **Project type**：Mod
- **Environment**：Client（必须只勾 Client）
- **Loaders**：Fabric
- **Game versions**：1.21.11
- **License**：MIT
- **Description**：把 `docs/modrinth-description.md` 全文粘贴进去
- **Tags 建议**：`utility`、`adventure`、`management`、`technology`、`mobs`（按平台可选项挑）
- **Links**：Source = GitHub 仓库地址

创建完成后，页面右上角 **Settings → Access tokens** 生成一个带 `Create versions` 权限的 token，
然后：

```powershell
$env:MODRINTH_TOKEN = 'mrp_xxxxxxxx'
$env:MODRINTH_PROJECT = 'big-fat-fish-can-play-mc'
powershell -File tools\publish.ps1 -Version 0.4.0-alpha.2 -SkipGitHub -SkipCurseForge -Proxy ''
```

（有代理就填 `-Proxy http://127.0.0.1:7890`。）

## 2. 创建 CurseForge 项目（约 5 分钟，人工审核可能更久）

<https://authors.curseforge.com/#/projects/create> → **Minecraft → Mods**，填写：

- **Name**：`Big Fat Fish Can Play MC`
- **Summary**：同上
- **Game version**：1.21.11，**Loader**：Fabric，**Environment**：Client
- **License**：MIT
- **Description**：同样粘贴商店文案
- 分类建议：`Utility & QoL`、`Adventure and RPG`、`Server Utility`

项目建好后，在 **Account → API Tokens**（作者后台）拿到 API Key，并记下项目数字 ID：

```powershell
$env:CURSEFORGE_TOKEN = 'xxxxxxxx-xxxx-xxxx'
$env:CURSEFORGE_PROJECT = '1234567'
powershell -File tools\publish.ps1 -Version 0.4.0-alpha.2 -SkipGitHub -SkipModrinth -Proxy ''
```

> 注意：CurseForge 的公开 API 只能"上传文件"，**不能创建项目**，所以上面这一步必须手工。
> 另外它家的 1.21.11 版本号与 Fabric 标记要等平台收录后才会出现在下拉框里；如果上传时报
> `gameVersions` 无效，把 `gameVersions` 改成 `@('1.21.11')` 只传游戏版本重试一次。

## 3. 上传前自检（可选，但建议）

```powershell
powershell -File tools\publish.ps1 -Version 0.4.0-alpha.2 -DryRun
```

它会打印：jar 路径/大小/SHA1、请求地址、将发送的 JSON、文件字段名，不会真的联网。

## 4. 上传之后

- 在 Modrinth 项目页确认：Loader `Fabric`、Game version `1.21.11`、Environment `Client`、
  版本类型 `alpha`、文件名为 `big-fat-fish-can-play-mc-0.4.0-alpha.2.jar`。
- CurseForge 上确认版本类型为 `Alpha`、`Game Version: 1.21.11`、`Mod Loader: Fabric`。
- 两个平台都建议把 GitHub Release 链接放到 "Links" 里。

## 5. 本次发布内容摘要（可复制到 Release Notes）

见 `CHANGELOG.md` 的 `0.4.0-alpha.2` 一节：掉落表驱动取材、需求图批量规划、里程碑分阶段、
失败重算、深部下挖、工作台/熔炉回收、OpenAI 兼容接口、借鉴 KillAura 的出手纪律、进食优先。
