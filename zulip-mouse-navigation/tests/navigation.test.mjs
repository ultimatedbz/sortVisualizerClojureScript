import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {stripTypeScriptTypes} from "node:module";
import {tmpdir} from "node:os";
import {dirname, join} from "node:path";
import test from "node:test";
import {fileURLToPath} from "node:url";
import {runInNewContext} from "node:vm";

const folder = dirname(dirname(fileURLToPath(import.meta.url)));
const fixture = readFileSync(join(folder, "tests/fixtures/desktop_integration.ts"), "utf8");
const patch = join(folder, "side-mouse-navigation.patch");
const apply = join(folder, "apply.py");
const target = "web/src/desktop_integration.ts";

function checkout(t) {
    const path = mkdtempSync(join(tmpdir(), "zulip-mouse-test-"));
    t.after(() => rmSync(path, {recursive: true, force: true}));
    mkdirSync(join(path, "web/src"), {recursive: true});
    writeFileSync(join(path, target), fixture);
    execFileSync("git", ["init", "--quiet", path]);
    execFileSync("git", ["-C", path, "add", target]);
    execFileSync("git", ["-C", path, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "--quiet", "--no-gpg-sign", "-m", "fixture"]);
    return path;
}

function runApply(path, ...args) {
    return spawnSync("python3", [apply, path, ...args], {encoding: "utf8"});
}

function loadPatched(t, desktop = true) {
    const path = checkout(t);
    execFileSync("git", ["-C", path, "apply", "--check", patch]);
    execFileSync("git", ["-C", path, "apply", patch]);
    const source = readFileSync(join(path, target), "utf8");
    // The complete real module executes; only imports are replaced with boundary
    // doubles. Type stripping is provided by Node, with no build dependencies.
    const javascript = stripTypeScriptTypes(source)
        .replace(/^import .*;\n/gm, "")
        .replace("export function initialize", "function initialize");
    const registrations = [];
    const moves = [];
    const document = {};
    const bridge = desktop ? {on_event() {}, set_send_notification_reply_message_supported() {}} : undefined;
    const context = {
        document,
        electron_bridge: bridge,
        window: {history: {back: () => moves.push(-1), forward: () => moves.push(1)}},
        $(element) {
            assert.equal(element, document);
            return {on: (...args) => registrations.push(args)};
        },
    };
    runInNewContext(`${javascript}\ninitialize();`, context);
    return {registrations, moves};
}

function mouseup(button, {prevented = false, cancelable = true, ...rest} = {}) {
    return {
        button,
        cancelable,
        defaultPrevented: prevented,
        isDefaultPrevented() { return this.defaultPrevented; },
        preventDefault() { if (this.cancelable) this.defaultPrevented = true; },
        ...rest,
    };
}

test("patch applies to the exact upstream source fixture", (t) => {
    // git hash-object pins the complete upstream content, not merely patch context.
    const hash = createHash("sha1").update(`blob ${Buffer.byteLength(fixture)}\0`).update(fixture).digest("hex");
    assert.equal(hash, "b2c5a85fd444b7a069f9d66c1eac100dc519dc3a");
    const {registrations} = loadPatched(t);
    assert.deepEqual(registrations.map(([event]) => event), ["mouseup", "click"]);
});

test("ordinary browsers keep their native mouse navigation", (t) => {
    const {registrations, moves} = loadPatched(t, false);
    assert.deepEqual(registrations, []);
    assert.deepEqual(moves, []);
});

test("side-button releases each navigate exactly one history entry", (t) => {
    const {registrations, moves} = loadPatched(t);
    const handler = registrations.find(([name]) => name === "mouseup")[1];
    for (const [button, expected] of [[3, -1], [4, 1]]) {
        const event = mouseup(button);
        handler(event);
        // Model Chromium's default action: failure to cancel would double-navigate.
        if (!event.defaultPrevented) moves.push(expected);
        assert.equal(event.defaultPrevented, true);
    }
    assert.deepEqual(moves, [-1, 1]);
});

test("left, middle, right, and unrelated buttons retain their defaults", (t) => {
    const {registrations, moves} = loadPatched(t);
    const handler = registrations.find(([name]) => name === "mouseup")[1];
    for (const button of [0, 1, 2, 5]) {
        const event = mouseup(button);
        handler(event);
        assert.equal(event.defaultPrevented, false);
    }
    assert.deepEqual(moves, []);
});

test("an event consumed by an inner control does not navigate again", (t) => {
    const {registrations, moves} = loadPatched(t);
    const handler = registrations.find(([name]) => name === "mouseup")[1];
    handler(mouseup(3, {prevented: true}));
    handler(mouseup(4, {prevented: true}));
    assert.deepEqual(moves, []);
});

test("side buttons work over the compose field and with modifier keys", (t) => {
    const {registrations, moves} = loadPatched(t);
    const handler = registrations.find(([name]) => name === "mouseup")[1];
    handler(mouseup(3, {target: {tagName: "TEXTAREA"}, altKey: true}));
    handler(mouseup(4, {target: {tagName: "INPUT"}, shiftKey: true}));
    assert.deepEqual(moves, [-1, 1]);
});

test("apply helper checks, applies once, and reverses without changing HEAD", (t) => {
    const path = checkout(t);
    const head = execFileSync("git", ["-C", path, "rev-parse", "HEAD"], {encoding: "utf8"});
    assert.equal(runApply(path, "--check").status, 0);
    assert.equal(readFileSync(join(path, target), "utf8"), fixture);
    assert.equal(runApply(path).status, 0);
    const patched = readFileSync(join(path, target), "utf8");
    assert.notEqual(patched, fixture);
    assert.match(runApply(path).stdout, /already applied/);
    assert.equal(readFileSync(join(path, target), "utf8"), patched);
    assert.equal(runApply(path, "--reverse", "--check").status, 0);
    assert.equal(readFileSync(join(path, target), "utf8"), patched);
    assert.equal(runApply(path, "--reverse").status, 0);
    assert.equal(readFileSync(join(path, target), "utf8"), fixture);
    assert.equal(execFileSync("git", ["-C", path, "rev-parse", "HEAD"], {encoding: "utf8"}), head);
});

test("apply helper refuses local changes and the wrong checkout path", (t) => {
    const path = checkout(t);
    const edited = `${fixture}\n// local edit\n`;
    writeFileSync(join(path, target), edited);
    assert.match(runApply(path).stderr, /has local changes/);
    assert.equal(readFileSync(join(path, target), "utf8"), edited);
    assert.match(runApply(join(path, "web")).stderr, /root of the Zulip checkout/);
});
