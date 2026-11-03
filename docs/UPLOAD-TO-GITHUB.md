# 上传到 GitHub（0.3.0-alpha.1）

仓库里那 41MB 基本都是**本机构建用的 jar**（`minecraft-intermediary.jar` 31MB、`baritone` 4.5MB），
已经写进 `.gitignore` 排除了。真正要提交的代码只有几百 KB。

---

## 一、装 Git（当前机器没有）

```powershell
winget install --id Git.Git -e --source winget
```

装完**新开一个终端**（PATH 才会刷新），验证：

```powershell
git --version
```

> 装不上就用 https://git-scm.com/download/win 手动装，一路默认即可。

## 二、在 GitHub 建仓库

1. 打开 https://github.com/new
2. 仓库名：`deepseek-companion`
3. **不要**勾 “Add a README file / .gitignore / license”（我们本地都有了，勾了会冲突）
4. 可见性：Public（想之后发 Modrinth 的话建议 Public）
5. 建好后复制那行 `git remote add origin ...`，下一步用得上

顺便生成一个 token 用于推送（HTTPS 方式必需）：

- https://github.com/settings/tokens → **Generate new token (classic)**
- 勾 `repo` 即可，有效期自己定
- 生成后**只显示一次**，复制下来，等下当密码用

## 三、本地提交并推送

在**你自己的终端**里跑（我这边没有你的凭据，这一步必须你来）：

```powershell
cd D:\Chat\deepseek-companion

git init
git add .
git status                 # ← 先看一眼：不该出现 libs/*.jar、build/、dist/
git commit -m "alpha: companion with perception, craft tree, plugin actions"

git branch -M main
git remote add origin https://github.com/<你的用户名>/deepseek-companion.git
git push -u origin main
```

推送时会要求登录：

- Username：你的 GitHub 用户名
- Password：**粘贴刚才那个 token**（不是账号密码）

## 四、打 tag 并建 release（可选，但建议）

```powershell
git tag -a v0.3.0-alpha.1 -m "0.3.0-alpha.1: perception layer"
git push origin v0.3.0-alpha.1
```

然后在网页上 Releases → Draft a new release → 选这个 tag → 勾 **pre-release** →
上传 `dist\0.3.0-alpha.1\deepseek-companion-0.3.0-alpha.1.jar` 作为附件。

或者用我们写好的脚本（需要 `GITHUB_TOKEN`）：

```powershell
$env:GITHUB_TOKEN = 'ghp_...'
$env:GITHUB_REPO  = '<你的用户名>/deepseek-companion'
D:\Chat\deepseek-companion\tools\publish.ps1 -SkipModrinth -SkipCurseForge
```

## 五、提交前检查清单

- [ ] `git status` 里**没有** `libs/baritone-*.jar`、`libs/minecraft-intermediary.jar`（LGPL + 体积）
- [ ] 没有 `build/`、`dist/`、`run/`
- [ ] 没有 `config/deepseek-companion.json`（里面是你的 **API key**）— 确认 `config/` 不在仓库里
- [ ] `README.md` 顶部提到 1.21.11 和 alpha 状态
- [ ] 万一 key 已经被提交过：立刻去 DeepSeek 后台**吊销重发**，再用 `git filter-repo` 清历史

## 六、之后每次提交

```powershell
git add .
git commit -m "fix: xxx"
git push
```

改了版本号发新版时，重复第四步（tag 换名字）。
