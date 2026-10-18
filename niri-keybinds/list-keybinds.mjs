#!/usr/bin/env node
import {readFile, realpath} from 'node:fs/promises';
import {homedir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import {getLocation} from '@bgotink/kdl';
import {parse} from '@bgotink/kdl/v1-compat';

const HELP = `Usage: node list-keybinds.mjs [--json] CONFIG.kdl

List every declared niri key binding, expanding includes in source order.
Includes global binds and recent-windows binds, even declarations later overridden.
Does not execute actions, modify files, or contact a running niri instance.

  --json    Emit structured JSON, including exact source and action arguments
  --help    Show this help
`;

const DESCRIPTIONS = {
  'close-window': 'Close the focused window',
  'fullscreen-window': 'Toggle fullscreen for the focused window',
  'toggle-windowed-fullscreen': 'Toggle windowed fullscreen',
  'toggle-window-floating': 'Toggle the focused window between floating and tiled',
  'switch-focus-between-floating-and-tiling': 'Switch focus between floating and tiled windows',
  'focus-column-left': 'Focus the column to the left',
  'focus-column-right': 'Focus the column to the right',
  'focus-column-first': 'Focus the first column',
  'focus-column-last': 'Focus the last column',
  'focus-window-up': 'Focus the window above',
  'focus-window-down': 'Focus the window below',
  'focus-window-previous': 'Focus the previously focused window',
  'focus-workspace': 'Focus the specified workspace',
  'focus-workspace-up': 'Focus the workspace above',
  'focus-workspace-down': 'Focus the workspace below',
  'focus-workspace-previous': 'Focus the previously focused workspace',
  'move-column-left': 'Move the focused column left',
  'move-column-right': 'Move the focused column right',
  'move-window-up': 'Move the focused window up',
  'move-window-down': 'Move the focused window down',
  'move-window-to-workspace': 'Move the focused window to the specified workspace',
  'move-column-to-workspace': 'Move the focused column to the specified workspace',
  'set-column-width': 'Set or adjust the focused column width',
  'set-window-width': 'Set or adjust the focused window width',
  'set-window-height': 'Set or adjust the focused window height',
  'maximize-column': 'Toggle the focused column between maximized and normal width',
  'center-column': 'Center the focused column in the view',
  'switch-preset-column-width': 'Cycle through preset column widths',
  'switch-preset-window-height': 'Cycle through preset window heights',
  'toggle-column-tabbed-display': 'Toggle the focused column between tabbed and normal display',
  'show-hotkey-overlay': 'Show the keyboard shortcuts overlay',
  'toggle-overview': 'Toggle the workspace overview',
  'open-overview': 'Open the workspace overview',
  'close-overview': 'Close the workspace overview',
  'screenshot': 'Open the interactive screenshot UI',
  'screenshot-screen': 'Take a screenshot of the focused screen',
  'screenshot-window': 'Take a screenshot of the focused window',
  'suspend': 'Suspend the computer',
  'power-off-monitors': 'Power off monitors',
  'power-on-monitors': 'Power on monitors',
  'toggle-keyboard-shortcuts-inhibit': 'Toggle keyboard shortcut inhibition',
  'next-window': 'Advance the recent windows switcher',
  'previous-window': 'Move backwards in the recent windows switcher',
};

function raw(element, source) {
  const location = getLocation(element);
  return source.slice(location.start.offset, location.end.offset);
}

function at(node, file) {
  const {line, column} = getLocation(node).start;
  return {file, line, column};
}

function locationText(location) {
  return `${location.file}:${location.line}:${location.column}`;
}

function entryData(entry, source) {
  const value = entry.getValue();
  // Keep the exact literal as well as a convenient decoded value. In particular,
  // consumers must use raw for numbers beyond JavaScript's safe integer range.
  return {
    name: entry.getName(),
    value,
    type: entry.value.getTag(),
    raw: raw(entry, source),
    literal: raw(entry.value, source),
  };
}

function actionData(node, source) {
  return {
    name: node.getName(),
    type: node.getTag(),
    arguments: node.getArgumentEntries().map(entry => entryData(entry, source)),
    properties: node.getPropertyEntries().map(entry => entryData(entry, source)),
    children: (node.children?.nodes ?? []).map(child => actionData(child, source)),
    raw: raw(node, source),
  };
}

function valueText(entry) {
  return entry.literal;
}

function actionText(action) {
  const entries = [
    ...action.arguments.map(valueText),
    ...action.properties.map(entry => `${entry.name}=${valueText(entry)}`),
  ];
  const children = action.children.length
    ? ` { ${action.children.map(actionText).join('; ')}; }` : '';
  return [action.name, ...entries].join(' ') + children;
}

function describe(action) {
  if (action.name === 'spawn') {
    return `Run program: ${action.arguments.map(valueText).join(' ')}`;
  }
  if (action.name === 'spawn-sh') {
    return `Run through sh: ${action.arguments.map(valueText).join(' ')}`;
  }
  if (action.name === 'quit') {
    return action.properties.some(entry => entry.name === 'skip-confirmation' && entry.value === true)
      ? 'Exit niri without confirmation' : 'Exit niri after confirmation';
  }
  return DESCRIPTIONS[action.name] ?? `Configured action: ${actionText(action)}`;
}

function bindingData(node, scope, file, source) {
  const properties = node.getPropertyEntries().map(entry => entryData(entry, source));
  const actions = (node.children?.nodes ?? []).map(child => actionData(child, source));
  const title = properties.findLast(entry => entry.name === 'hotkey-overlay-title');
  return {
    key: node.getName(),
    scope,
    source: at(node, file),
    properties,
    arguments: node.getArgumentEntries().map(entry => entryData(entry, source)),
    actions,
    description: typeof title?.value === 'string'
      ? title.value : actions.map(describe).join('; ') || '(no action)',
    raw: raw(node, source),
  };
}

function expandHome(filename, homeDirectory) {
  if (filename === '~') return homeDirectory;
  if (filename.startsWith('~/')) return `${homeDirectory}/${filename.slice(2)}`;
  // niri does not expand environment variables or ~otheruser.
  return filename;
}

function absoluteFrom(base, filename) {
  // Preserve .. until the OS resolves it: link/../x can differ from x when
  // link is a symlink. niri also joins paths without lexical normalization.
  return path.isAbsolute(filename) ? filename : `${base}${path.sep}${filename}`;
}

function validateNestedIncludes(nodes, file) {
  for (const node of nodes) {
    if (node.getName() === 'include') {
      throw new Error(`${locationText(at(node, file))}: include is only valid at the top level`);
    }
    validateNestedIncludes(node.children?.nodes ?? [], file);
  }
}

function sectionBindings(node, scope, file, source) {
  return (node.children?.nodes ?? []).map(bind => bindingData(bind, scope, file, source));
}

/** Read only the requested config and its include graph; never execute actions. */
export async function inspectConfig(filename, {homeDirectory = homedir(), maxDepth = 64} = {}) {
  const root = absoluteFrom(process.cwd(), expandHome(filename, homeDirectory));
  const result = await visit(root, [], []);
  return {scope: 'declared', root, ...result};

  async function visit(file, stack, includeChain) {
    if (stack.length >= maxDepth) {
      throw new Error(`Include depth limit (${maxDepth}) reached: ${[...includeChain, file].join(' -> ')}`);
    }
    const canonical = await realpath(file);
    if (stack.includes(canonical)) {
      throw new Error(`Include cycle: ${[...includeChain, file].join(' -> ')}`);
    }
    const source = new TextDecoder('utf-8', {fatal: true}).decode(await readFile(file));
    let document;
    try {
      document = parse(source, {storeLocations: true});
    } catch (error) {
      throw new Error(`${file}: invalid KDL v1: ${error.message}`, {cause: error});
    }
    let result = {files: [file], bindings: [], warnings: []};
    for (const node of document.nodes) {
      validateNestedIncludes(node.children?.nodes ?? [], file);
      if (node.getName() === 'include') {
        const args = node.getArguments();
        const props = node.getPropertyEntries();
        if (args.length !== 1 || typeof args[0] !== 'string' || args[0].length === 0
          || props.some(entry => entry.getName() !== 'optional' || typeof entry.getValue() !== 'boolean')
          || (node.children?.nodes.length ?? 0) > 0) {
          throw new Error(`${locationText(at(node, file))}: expected include "path" [optional=true|false]`);
        }
        const target = absoluteFrom(path.dirname(file), expandHome(args[0], homeDirectory));
        let included;
        try {
          included = await visit(target, [...stack, canonical], [...includeChain, file]);
        } catch (error) {
          // Only a missing immediate include is optional. Errors further down its
          // graph, syntax errors and permissions errors must still fail.
          if (node.getProperty('optional') === true && error.code === 'ENOENT' && error.path === target) {
            result = {...result, warnings: [...result.warnings,
              `${locationText(at(node, file))}: optional include not found: ${target}`]};
            continue;
          }
          throw new Error(`${locationText(at(node, file))}: could not include ${target}: ${error.message}`, {cause: error});
        }
        result = {
          files: [...result.files, ...included.files],
          bindings: [...result.bindings, ...included.bindings],
          warnings: [...result.warnings, ...included.warnings],
        };
      } else {
        const bindings = node.getName() === 'binds'
          ? sectionBindings(node, 'global', file, source)
          : node.getName() === 'recent-windows'
            ? (node.children?.nodes ?? []).filter(child => child.getName() === 'binds')
              .flatMap(child => sectionBindings(child, 'recent-windows', file, source))
            : [];
        result = {...result, bindings: [...result.bindings, ...bindings],
          warnings: [...result.warnings, ...bindings.filter(bind => bind.actions.length !== 1).map(bind =>
            `${locationText(bind.source)}: ${bind.key} declares ${bind.actions.length} actions; niri requires exactly one`)]};
      }
    }
    return result;
  }
}

// Escape terminal controls and embedded newlines without changing the JSON data.
function cell(value) {
  return String(value).replace(/[\u0000-\u001f\u007f-\u009f]/g, character =>
    character === '\n' ? '\\n' : character === '\t' ? '\\t'
      : `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`);
}

export function formatTable(report) {
  const rows = [
    ['KEY', 'SCOPE', 'DESCRIPTION', 'ACTION', 'OPTIONS', 'SOURCE'],
    ...report.bindings.map(bind => [bind.key, bind.scope, bind.description,
      bind.actions.map(actionText).join('; '),
      bind.properties.map(entry => `${entry.name}=${valueText(entry)}`).join(' '),
      locationText(bind.source)]),
  ].map(row => row.map(cell));
  const widths = rows[0].map((_, index) => Math.max(...rows.map(row => row[index].length)));
  return [
    'Declared bindings in include order (later declarations may override earlier ones):',
    ...rows.map(row => row.map((value, index) => value.padEnd(widths[index])).join('  ').trimEnd()),
    `${report.bindings.length} binding(s); ${report.files.length} file visit(s).`,
  ].join('\n');
}

export async function main(args) {
  try {
    const {values, positionals} = parseArgs({args, allowPositionals: true, options: {
      json: {type: 'boolean'}, help: {type: 'boolean'},
    }});
    if (values.help) {
      process.stdout.write(HELP);
      return 0;
    }
    if (positionals.length !== 1) throw new Error('Provide exactly one config file. Use --help for usage.');
    const report = await inspectConfig(positionals[0]);
    process.stdout.write(values.json ? `${JSON.stringify(report, null, 2)}\n` : `${formatTable(report)}\n`);
    for (const warning of report.warnings) process.stderr.write(`Warning: ${cell(warning)}\n`);
    return 0;
  } catch (error) {
    process.stderr.write(`Error: ${cell(error.message)}\n`);
    return 1;
  }
}

async function isMainModule() {
  if (!process.argv[1]) return false;
  try {
    const [entry, module] = await Promise.all([
      realpath(process.argv[1]),
      realpath(fileURLToPath(import.meta.url)),
    ]);
    return entry === module;
  } catch {
    // An importing host can set argv[1] to something other than a real file.
    return false;
  }
}

if (await isMainModule()) {
  process.exitCode = await main(process.argv.slice(2));
}
