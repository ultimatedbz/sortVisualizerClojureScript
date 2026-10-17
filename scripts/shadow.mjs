import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { delimiter, resolve } from 'node:path';

// Homebrew Java is not always registered with macOS's /usr/bin/java launcher.
const env = { ...process.env };
if (!env.JAVA_HOME && process.platform === 'darwin') {
  for (const root of ['/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home', '/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home', '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home', '/usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home']) {
    if (existsSync(`${root}/bin/java`)) { env.JAVA_HOME = root; break; }
  }
}
if (env.JAVA_HOME) env.PATH = `${env.JAVA_HOME}/bin${delimiter}${env.PATH}`;
const result = spawnSync(process.execPath, [resolve('node_modules/shadow-cljs/cli/runner.js'), ...process.argv.slice(2)], { env, stdio: 'inherit' });
if (result.error) console.error(result.error.message);
process.exit(result.status ?? 1);
