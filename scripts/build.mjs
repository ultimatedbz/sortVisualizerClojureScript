import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
const result = spawnSync(process.execPath, ['scripts/shadow.mjs', 'release', 'app', 'worker'], { stdio: 'inherit' });
if (result.status !== 0) process.exit(result.status ?? 1);
rmSync('dist', { recursive: true, force: true });
mkdirSync('dist', { recursive: true });
for (const file of ['index.html', 'styles.css', 'favicon.svg', 'js', 'worker']) cpSync(`public/${file}`, `dist/${file}`, { recursive: true });
writeFileSync('dist/.nojekyll', '');
