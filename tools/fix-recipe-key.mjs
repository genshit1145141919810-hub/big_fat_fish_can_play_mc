// Node (UTF-8 safe) fix: CraftRecipes.Recipe has no name(); identify a recipe by its shape.
import { readFileSync, writeFileSync } from 'node:fs';

const src = 'D:/Chat/deepseek-companion/tools/AcquireTask2.java';
const dst = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';

let s = readFileSync(src, 'utf8');
s = s.replaceAll('recipe.name()', 'recipeKey(recipe)');

if (!s.includes('private static String recipeKey')) {
  s = s.replace(
    '    private static int recipeCost(CraftRecipes.Recipe recipe) {',
    `    /** 配方唯一标识，用来在顶层强制"就试这一条"。 */
    private static String recipeKey(CraftRecipes.Recipe recipe) {
        return recipe.result() + "|" + recipe.width() + "x" + recipe.height() + "|"
                + String.join(",", recipe.cells());
    }

    private static int recipeCost(CraftRecipes.Recipe recipe) {`
  );
}

writeFileSync(dst, s, 'utf8');
console.log('wrote', dst, s.length, 'chars');
