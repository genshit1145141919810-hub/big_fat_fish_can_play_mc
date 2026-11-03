// Verification must tolerate pickup delay: mined blocks drop as item entities, so the
// inventory check right after a step can read zero. Compare against a baseline and give
// the drop a few seconds to be collected before declaring the step failed.
import { readFileSync, writeFileSync } from 'node:fs';

const p = 'D:/Chat/deepseek-companion/src/main/java/io/github/xiaod/deepseekcompanion/bot/AcquireTask.java';
let s = readFileSync(p, 'utf8');
const out = [];

if (!s.includes('baseline')) {
  s = s.replace(
    '    private static boolean stepStarted;',
    '    private static boolean stepStarted;\n    private static int baseline;\n    private static long verifyUntil;'
  );
  out.push('ok fields');
}

// armed baseline when a step starts
s = s.replace(
  '        stepStarted = true;\n        stepSince = now;',
  '        stepStarted = true;\n        stepSince = now;\n        baseline = count(mc, step.item());\n        verifyUntil = 0L;'
);

// completion check: baseline + grace window
const from = `            boolean produced = count(mc, step.item()) > 0;
            PLAN.poll();
            stepStarted = false;
            stepSince = now;
            if (!produced && step.op() != Op.PLACE && step.op() != Op.HUNT) {
                nextCandidate(mc, step + " 没有产出");
            }
            return;`;
const to = `            boolean produced = count(mc, step.item()) > baseline;
            if (!produced && step.op() != Op.PLACE && step.op() != Op.HUNT) {
                // 挖下来的东西掉在地上，拾取要几秒
                if (verifyUntil == 0L) {
                    verifyUntil = now + 5000L;
                    return;
                }
                if (now < verifyUntil) {
                    return;
                }
                PLAN.poll();
                stepStarted = false;
                verifications++;
                nextCandidate(mc, step + " 没有产出");
                return;
            }
            PLAN.poll();
            stepStarted = false;
            stepSince = now;
            verifyUntil = 0L;
            return;`;
if (s.includes(from)) {
  s = s.replace(from, to);
  out.push('ok verification grace');
} else {
  out.push('MISS verification');
}

if (!s.includes('verifications')) {
  s = s.replace(
    '    private static int baseline;',
    '    private static int baseline;\n    private static int verifications;'
  );
  out.push('ok counter');
}

writeFileSync(p, s, 'utf8');
console.log(out.join('\n'));
