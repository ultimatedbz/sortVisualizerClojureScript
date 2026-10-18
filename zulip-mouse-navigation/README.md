# Zulip side mouse navigation

A 15-line addition to Zulip's `web/src/desktop_integration.ts` maps the mouse's
Back button (`button === 3`) to `window.history.back()` and Forward button
(`button === 4`) to `window.history.forward()`.

This uses the same session history as Alt+Left and Alt+Right. Zulip's normal
browser client already gets these actions from the browser; the missing binding
is in its Electron desktop client. The handler is therefore inside Zulip's
existing `electron_bridge` guard. It does not invent a separate navigation stack
or dispatch synthetic keyboard events.

The handler runs on **mouseup**, cancels Chromium's default action before
navigating, and ignores events another control already consumed. Left, middle,
and right buttons keep their existing behavior. There is only one navigation
listener, so a down/up/auxclick sequence cannot trigger multiple movements.

## Apply

Requires Python 3 and Git. Pass the root of your **Zulip server** checkout, not
this repository or the separate `zulip-desktop` repository:

```sh
python3 zulip-mouse-navigation/apply.py /path/to/zulip --check
python3 zulip-mouse-navigation/apply.py /path/to/zulip
git -C /path/to/zulip diff -- web/src/desktop_integration.ts
```

The helper refuses to overwrite local changes to the target file, checks the
patch before applying it, and recognizes an already-applied patch. It changes
no commits, branches, remotes, or installed applications. Rebuild/deploy the
Zulip frontend through your normal development or server deployment process,
then reload the organization in Zulip Desktop. Merely applying this patch to a
local checkout does not change a hosted organization you do not deploy.

To remove it:

```sh
python3 zulip-mouse-navigation/apply.py /path/to/zulip --reverse --check
python3 zulip-mouse-navigation/apply.py /path/to/zulip --reverse
```

The patch is pinned and checked against upstream commit
[`e45560dfcfc40c786c689b454ef6f4e7dc0f3eaf`](https://github.com/zulip/zulip/commit/e45560dfcfc40c786c689b454ef6f4e7dc0f3eaf).
For a different revision, the helper reports the difference and still requires
`git apply --check` to pass. It does not force hunks or overwrite conflicting code.

## Tests

From this repository's root, with Node.js 22.13 or newer, Python 3, and Git:

```sh
node --test zulip-mouse-navigation/tests/*.test.mjs
```

The tests work offline without npm dependencies. They verify the upstream
fixture's Git blob hash, apply the real patch, strip TypeScript using Node's
built-in API, and run the complete module with browser/Electron boundary doubles.
They cover both directions, suppression of duplicate native navigation,
unaffected browser mode and ordinary buttons, already-consumed events, compose
fields, modifier keys, and the apply/check/reapply/reverse workflow. The single
upstream source fixture and its license are included for reproducibility.

These tests passed during implementation. Zulip's full test/lint tools require a
provisioned Zulip development environment; that environment was not available here.
Actual physical side-button delivery by a particular OS/driver is not simulated
by these unit tests. After deploying, verify the hardware in Zulip Desktop:

1. Visit a channel, a topic, and a direct message.
2. Press Back once: it should return to the topic, matching Alt+Left.
3. Press Forward once: it should return to the direct message, matching Alt+Right.
4. Repeat while the compose box is focused and while viewing settings.
5. Check that ordinary clicks, middle-click links, and right-click menus still work.

The binding applies to the organization's web content. A server-side patch cannot
receive clicks on the desktop app's native title bar or organization sidebar, or
buttons intercepted/remapped by an OS or mouse driver before they reach the page.

## Source references

- [Zulip desktop integration at the pinned revision](https://github.com/zulip/zulip/blob/e45560dfcfc40c786c689b454ef6f4e7dc0f3eaf/web/src/desktop_integration.ts)
- [Zulip's documented viewing-history shortcuts](https://github.com/zulip/zulip/blob/e45560dfcfc40c786c689b454ef6f4e7dc0f3eaf/starlight_help/src/content/docs/keyboard-shortcuts.mdx)
- [Desktop's Alt+Left/Right accelerators](https://github.com/zulip/zulip-desktop/blob/1cc112def5cd4b0b3ffe15c0857258c527b4421a/app/main/menu.ts)
  and [session-history implementation](https://github.com/zulip/zulip-desktop/blob/1cc112def5cd4b0b3ffe15c0857258c527b4421a/app/renderer/js/components/webview.ts)
- [Upstream report of missing desktop side-button navigation](https://github.com/zulip/zulip-desktop/issues/1270)
- [Chromium's `MouseButtonsNavigate` / `MouseButtonsDontNavigate` tests](https://chromium.googlesource.com/chromium/src/+/e4cdf6e6774c8b79cd8701676ac584a0d78acb84/content/browser/web_contents/web_contents_impl_browsertest.cc)
  document that its history action occurs on mouseup and can be canceled there.

See [NOTICE](NOTICE) and [UPSTREAM-LICENSE](UPSTREAM-LICENSE) for the upstream
fixture and patch excerpt's Apache 2.0 attribution.
