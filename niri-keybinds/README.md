# niri key binding inventory

List every key binding declared in a niri config and the files it includes. The script expands includes in source order and shows the key combination, its description, complete action arguments, binding options, and source file/line/column. It reads config files only: it does not execute `spawn` commands, change your configuration, or require a running niri session.

## Run

Install Node.js 22 or newer, then run from this repository:

```sh
cd niri-keybinds
npm ci --ignore-scripts
node list-keybinds.mjs ~/.config/niri/config.kdl
```

Pass the actual config path if you start niri with `--config` or `NIRI_CONFIG`. A path is required; the script does not discover or read your personal configuration automatically.

```sh
# Machine-readable output, with warnings also sent to stderr.
node list-keybinds.mjs --json /path/to/config.kdl > keybinds.json

# Try the included synthetic example.
node list-keybinds.mjs test/fixtures/config.kdl

# Run the offline test suite.
npm test
```

The table has `KEY`, `SCOPE`, `DESCRIPTION`, `ACTION`, `OPTIONS`, and `SOURCE` columns. Descriptions use `hotkey-overlay-title` when supplied, then a short explanation for common actions. For other actions, the exact configured action name and arguments are displayed. All actions are always present in the separate action column, including when an overlay title is set. A `null` overlay title does not hide the binding from this inventory.

Global `binds` and `recent-windows { binds { ... } }` both appear, with distinct scope labels. A `recent-windows` section marked `off` still contributes its *declarations*, because this report is an inventory of what the files contain.

## Parsing and include rules

The pinned `@bgotink/kdl` parser parses KDL v1, the dialect used by niri's knuffel configuration parser. This handles comments, nested blocks, escaped and raw strings, continuations, semicolon-separated nodes, quoted names, and `/-` disabled nodes/properties/children. Disabled declarations are omitted, even if their text resembles an include or a binding.

Includes are processed at their position in the file, recursively:

- Relative paths are relative to the file containing the include.
- Absolute paths and `~/` home paths work. Environment variables, globs, and `~otheruser` are not expanded.
- A missing `include optional=true "file.kdl"` emits a warning and continues. Missing required files, unreadable files, and malformed KDL fail with exit status 1. Optional does not suppress errors in an existing file or its descendants.
- Only top-level includes are valid. A nested include is an error.
- A file included multiple times is listed multiple times, preserving its position each time. Cycles, including cycles through symlinks, are errors. A separate 64-file stack depth limit bounds unusually deep graphs.

Home paths and optional includes follow niri 26.04 semantics; includes themselves were introduced in 25.11. No network requests are made when running the script after dependency installation.

## Declared bindings versus effective bindings

The report intentionally includes **all declarations**, including earlier bindings overridden by a later included file. Its JSON `scope` is `"declared"`; it does not claim that every row will trigger at runtime. This also makes accidental overrides visible instead of silently dropping them.

Niri merges conflicting keys across includes, rejects duplicate keys within a single `binds` block, resolves modifier aliases/XKB key names, and gives global binds precedence over recent-windows binds. This inventory does not reimplement that runtime resolution, inject default/hardcoded shortcuts, or validate every action and option. Use `niri validate --config /path/to/config.kdl` for full configuration validation on your installed niri version. It warns when a binding declares zero or multiple actions, preserving all action nodes for inspection; niri permits exactly one action per binding.

## JSON fields

The report contains `root`, `scope`, `files` (file visits in traversal order), `bindings`, and `warnings`. Each binding has:

- `key`, `scope`, `description`, and `source: {file, line, column}` (one-based locations).
- `properties` and `arguments`, preserving their names, decoded values, KDL type tags, exact entry `raw` text, and exact value `literal` text.
- `actions`, each with its name, type tag, arguments, properties, child action nodes, and exact `raw` KDL source.
- `raw`, the complete original binding node text.

Decoded numeric `value` fields use JavaScript numbers. Use `literal`/`raw` for exact numeric fidelity beyond JavaScript's safe integer range; the human table uses original literals too. Output preserves unknown action names and options instead of guessing their meaning. Terminal control characters are escaped in the table and error messages; JSON uses standard JSON escaping.

## Verification and sources

The offline tests cover recursive relative/absolute/home includes, repeated includes, optional errors, symlink cycles, depth limits, malformed KDL, disabled syntax, string/comment edge cases, overlay titles, source locations, recent-windows scope, multiple actions, large numeric literals, and CLI behavior. The parser was also checked against niri's upstream `resources/default-config.kdl`: 126 declared bindings, no warnings (2026-10-17).

Implementation references:

- [niri include rules](https://niri-wm.github.io/niri/Configuration:-Include.html)
- [niri key bindings](https://niri-wm.github.io/niri/Configuration:-Key-Bindings.html)
- [niri recent windows bindings](https://niri-wm.github.io/niri/Configuration:-Recent-Windows.html)
- [niri configuration parser](https://github.com/niri-wm/niri/blob/main/niri-config/src/lib.rs) and [binding decoder](https://github.com/niri-wm/niri/blob/main/niri-config/src/binds.rs)
- [KDL parser and v1 compatibility API](https://github.com/bgotink/kdl)

The single direct dependency is pinned in `package.json` and integrity-locked in `package-lock.json`; no third-party code is vendored.
