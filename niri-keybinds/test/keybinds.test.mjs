import assert from 'node:assert/strict';
import {execFileSync, spawnSync} from 'node:child_process';
import {mkdtemp, mkdir, readFile, rm, symlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
import {formatTable, inspectConfig} from '../list-keybinds.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
const fixture = path.join(directory, 'fixtures/config.kdl');
const cli = path.resolve(directory, '../list-keybinds.mjs');

async function config(t, files) {
  const folder = await mkdtemp(path.join(tmpdir(), 'niri-keybinds-'));
  t.after(() => rm(folder, {recursive: true, force: true}));
  await Promise.all(Object.entries(files).map(async ([name, source]) => {
    await mkdir(path.dirname(path.join(folder, name)), {recursive: true});
    await writeFile(path.join(folder, name), source);
  }));
  return {folder, root: path.join(folder, 'config.kdl')};
}

test('expands nested includes positionally and preserves repeated visits and overridden declarations', async () => {
  const report = await inspectConfig(fixture);
  assert.equal(report.scope, 'declared');
  assert.deepEqual(report.bindings.map(bind => bind.key), [
    'Mod+WheelScrollDown', 'Mod+T', 'Mod+1',
    'Mod+T', 'Mod+Shift+P', 'Mod+H', 'Mod+Q', 'Mod+U',
    'Mod+WheelScrollDown', 'Mod+T', 'Mod+1', 'Alt+Tab',
  ]);
  assert.equal(report.files.length, 5);
  assert.match(report.warnings[0], /optional include not found/);
  assert.equal(report.bindings.at(-1).scope, 'recent-windows');
  assert.equal(report.bindings.at(-1).actions[0].properties[0].value, 'workspace');
});

test('real KDL parsing handles quoted keys, raw strings, disabled nodes and disabled properties', async () => {
  const {bindings} = await inspectConfig(fixture);
  const shell = bindings.find(bind => bind.key === 'Mod+Shift+P');
  assert.equal(shell.actions[0].arguments[0].value, "printf 'a;{b}' | cat");
  assert.deepEqual(shell.properties.map(entry => [entry.name, entry.value]), [['repeat', false], ['cooldown-ms', 200]]);
  assert.deepEqual(bindings.find(bind => bind.key === 'Mod+H').properties, []);
  assert.ok(!bindings.some(bind => ['Mod+X', 'MouseBack', 'Fake'].includes(bind.key)));
});

test('uses overlay titles, preserves null titles, and retains accurate source line/column', async () => {
  const {bindings} = await inspectConfig(fixture);
  const terminal = bindings.find(bind => bind.description === 'Terminal');
  assert.deepEqual(terminal.source, {file: fixture, line: 4, column: 5});
  const hidden = bindings.find(bind => bind.key === 'Mod+Q');
  assert.equal(hidden.properties[0].value, null);
  assert.equal(hidden.description, 'Close the focused window');
});

test('unknown actions and large number literals survive without invented descriptions or rounding', async () => {
  const report = await inspectConfig(fixture);
  const future = report.bindings.find(bind => bind.key === 'Mod+U');
  assert.equal(future.actions[0].name, 'future-action');
  assert.equal(future.actions[0].arguments[0].value, 'keep // this');
  assert.equal(future.actions[0].properties[0].literal, '9_007_199_254_740_993');
  assert.match(future.description, /^Configured action: future-action/);
  assert.match(future.actions[0].raw, /9_007_199_254_740_993/);
  assert.match(formatTable(report), /value=9_007_199_254_740_993/);
});

test('handles multiline, continued, typed and escaped KDL values', async t => {
  const {root} = await config(t, {'config.kdl': String.raw`binds {
    "Mod+Return" hotkey-overlay-title="Line\nTwo" \
        repeat=false { spawn "printf" (text)"hello\"world"; }
}
`});
  const report = await inspectConfig(root);
  const bind = report.bindings[0];
  assert.equal(bind.description, 'Line\nTwo');
  assert.equal(bind.actions[0].arguments[1].type, 'text');
  assert.equal(bind.actions[0].arguments[1].value, 'hello"world');
  assert.match(formatTable(report), /Line\\nTwo/);
});

test('resolves absolute and ~/ include paths; does not expand environment variables', async t => {
  const {folder, root} = await config(t, {
    'config.kdl': '',
    'home/keys.kdl': 'binds { Mod+H { focus-column-left; }; }\n',
    '$CONFIG/keys.kdl': 'binds { Mod+L { focus-column-right; }; }\n',
  });
  await writeFile(root, `include "~/keys.kdl"\ninclude ${JSON.stringify(path.join(folder, '$CONFIG/keys.kdl'))}\n`);
  const report = await inspectConfig(root, {homeDirectory: path.join(folder, 'home')});
  assert.deepEqual(report.bindings.map(bind => bind.key), ['Mod+H', 'Mod+L']);
});

test('required missing includes fail with including source location', async t => {
  const {root} = await config(t, {'config.kdl': 'include "missing.kdl"\n'});
  await assert.rejects(inspectConfig(root), /config\.kdl:1:1: could not include.*ENOENT/);
});

test('optional missing files warn while optional malformed files fail', async t => {
  const {root} = await config(t, {
    'config.kdl': 'include optional=true "bad.kdl"\n',
    'bad.kdl': 'binds { Mod+A { spawn "broken; } }',
  });
  await assert.rejects(inspectConfig(root), /bad\.kdl: invalid KDL v1/);
});

test('optional does not suppress missing required descendants', async t => {
  const {root} = await config(t, {
    'config.kdl': 'include optional=true "child.kdl"\n',
    'child.kdl': 'include "missing.kdl"\n',
  });
  await assert.rejects(inspectConfig(root), /child\.kdl:1:1: could not include.*missing\.kdl/);
});

test('rejects invalid include arguments and non-boolean optional properties', async t => {
  for (const include of ['include 123', 'include "a" "b"', 'include optional="true" "a"', 'include glob=true "a"']) {
    const {root} = await config(t, {'config.kdl': `${include}\n`});
    await assert.rejects(inspectConfig(root), /expected include/);
  }
});

test('detects direct, indirect and symlink include cycles', async t => {
  const {folder, root} = await config(t, {
    'config.kdl': 'include "child.kdl"\n',
    'child.kdl': 'include "alias.kdl"\n',
  });
  await symlink(root, path.join(folder, 'alias.kdl'));
  await assert.rejects(inspectConfig(root), /Include cycle:.*config\.kdl.*child\.kdl.*alias\.kdl/);
  await writeFile(root, 'include "config.kdl"\n');
  await assert.rejects(inspectConfig(root), /Include cycle/);
});

test('bounds deep include chains and rejects nested includes', async t => {
  const {root} = await config(t, {
    'config.kdl': 'include "child.kdl"\n',
    'child.kdl': 'include "leaf.kdl"\n',
    'leaf.kdl': 'binds {}\n',
  });
  await assert.rejects(inspectConfig(root, {maxDepth: 2}), /Include depth limit/);
  await writeFile(root, 'layout { include "child.kdl"; }\n');
  await assert.rejects(inspectConfig(root), /include is only valid at the top level/);
});

test('preserves every declared action and nested action node, warning about invalid counts', async t => {
  const {root} = await config(t, {'config.kdl': `binds {
    Mod+A { close-window; spawn "foot"; }
    Mod+B {}
    Mod+C { future-action { nested "argument"; }; }
}\n`});
  const report = await inspectConfig(root);
  assert.deepEqual(report.bindings[0].actions.map(action => action.name), ['close-window', 'spawn']);
  assert.equal(report.bindings[2].actions[0].children[0].arguments[0].value, 'argument');
  assert.equal(report.warnings.length, 2);
  assert.match(report.warnings[0], /declares 2 actions/);
  assert.match(report.warnings[1], /declares 0 actions/);
});

test('a disabled binds block and disabled child block contribute no bindings/actions', async t => {
  const {root} = await config(t, {'config.kdl': `/- binds { Mod+A { spawn "ignored"; }; }
binds { Mod+B /- { spawn "ignored"; }; }
`});
  const report = await inspectConfig(root);
  assert.deepEqual(report.bindings.map(bind => bind.key), ['Mod+B']);
  assert.deepEqual(report.bindings[0].actions, []);
});

test('CLI emits valid JSON and a readable table without writing to input files', async () => {
  const before = await readFile(fixture, 'utf8');
  const invocation = spawnSync(process.execPath, [cli, '--json', fixture], {encoding: 'utf8'});
  assert.equal(invocation.status, 0, invocation.stderr);
  assert.match(invocation.stderr, /^Warning:/);
  const report = JSON.parse(invocation.stdout);
  assert.equal(report.bindings.length, 12);
  const table = execFileSync(process.execPath, [cli, fixture], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
  assert.match(table, /KEY\s+SCOPE\s+DESCRIPTION\s+ACTION\s+OPTIONS\s+SOURCE/);
  assert.match(table, /12 binding\(s\); 5 file visit\(s\)/);
  assert.equal(await readFile(fixture, 'utf8'), before);
});

test('CLI rejects missing path, unknown option and malformed KDL with no partial stdout', async t => {
  const {root} = await config(t, {'config.kdl': 'binds { broken {\n'});
  for (const args of [[], ['--unknown'], [root]]) {
    const invocation = spawnSync(process.execPath, [cli, ...args], {encoding: 'utf8'});
    assert.equal(invocation.status, 1);
    assert.equal(invocation.stdout, '');
    assert.match(invocation.stderr, /^Error:/);
  }
});


test('resolves parent components after symlinks using filesystem semantics', async t => {
  const {folder, root} = await config(t, {
    'config.kdl': 'include "link/../keys.kdl"\n',
    'target/deeper/placeholder': '',
    'target/keys.kdl': 'binds { Mod+T { spawn "correct"; }; }\n',
    'keys.kdl': 'binds { Mod+F { spawn "wrong"; }; }\n',
  });
  await symlink(path.join(folder, 'target/deeper'), path.join(folder, 'link'));
  const report = await inspectConfig(root);
  assert.equal(report.bindings[0].key, 'Mod+T');
});


test('CLI works through a symlink without running when imported from another entry point', async t => {
  const {folder, root} = await config(t, {
    'config.kdl': 'binds { Mod+T { spawn "foot"; }; }\n',
    'importer.mjs': `import ${JSON.stringify(cli)}; console.log("imported without running CLI");\n`,
  });
  const alias = path.join(folder, 'niri-keys');
  await symlink(cli, alias);
  const invocation = spawnSync(process.execPath, [alias, '--json', root], {encoding: 'utf8'});
  assert.equal(invocation.status, 0, invocation.stderr);
  assert.equal(JSON.parse(invocation.stdout).bindings[0].key, 'Mod+T');
  const imported = spawnSync(process.execPath, [path.join(folder, 'importer.mjs')], {encoding: 'utf8'});
  assert.equal(imported.status, 0, imported.stderr);
  assert.equal(imported.stdout, 'imported without running CLI\n');
  assert.equal(imported.stderr, '');
});
