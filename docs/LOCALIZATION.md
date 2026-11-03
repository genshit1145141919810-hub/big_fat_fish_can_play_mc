# 多语言（i18n）

## 现在的做法

中文是**源语言**，其它语言用一张「中文 → 译文」的表替换。这样做不用改几百个调用点：

```
LocalCommandHandler.say / BotBrain.say / BotBrain.tell   ← 所有给玩家看的文字都走这三处
        └─ Lang.t(text)  按当前游戏语言查表
```

- 语言代码来自游戏设置（`en_us`、`zh_cn`…）。中文环境直接用原文，不做任何查找。
- 没有对应文件的语言先**回退英文**（`en_us`），英文也缺条目就保留中文原文。
- 拼出来的句子（`"弄不到 " + item + "：" + reason`）用 `rules` 里的 `%s` 模板兜底。

## 文件位置

```
src/main/resources/assets/big_fat_fish_can_play_mc/lang/en_us.json
```

结构：

```json
{
  "strings": { "中文原文": "English text" },
  "rules":   [ { "zh": "弄不到 %s：%s", "en": "Cannot get %s: %s" } ]
}
```

- `strings`：整句精确匹配（键就是代码里的中文原文，必须**一字不差**）。
- `rules`：带占位符的句子，`%s` 按出现顺序对应。

## 加一种语言

1. 复制 `en_us.json` 为 `ja_jp.json`（或其它语言代码）。
2. 翻译 `strings` 的值；`rules` 的 `zh` 不要改。
3. 重启游戏，日志里会出现 `language ja_jp loaded (N strings)`。

## 检查进度

```powershell
node tools\check-lang.cjs
```

它会告诉你：表里有多少条、代码里还有多少条中文没翻译、以及**哪些键在代码里并不存在**（防手滑写错字）。

## 已知限制（alpha）

- 只翻译了「整句」和少数模板；大量**拼接句**（`"身上没有 " + id`、状态行、日志）仍是中文。
  想彻底解决需要把拼接改成 `Lang.f("模板 %s", 参数)`，属于逐步迁移。
- 只翻译**玩家可见**的文字；`[XiaoD] ...` 的日志刻意保持中文，方便排查。
- 聊天里模型生成的内容本来就是中文/英文由模型决定，不经过这张表。
- `§` 颜色代码要原样保留在译文里，否则颜色会丢。
