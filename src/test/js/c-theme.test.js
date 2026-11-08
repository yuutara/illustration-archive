const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/theme-mode.js"), "utf8");

function theme({ transitions = false, reduced = false, saved = null, glass = null } = {}) {
    const style = {};
    const root = { dataset: {}, style: { setProperty: (name, value) => { style[name] = value; } } };
    const pending = [];
    const calls = [];
    const stored = {};
    const document = { documentElement: root };
    if (transitions) document.startViewTransition = (change) => { pending.push(change); };
    const window = { iaDesktop: (action, value) => calls.push([action, value]) };
    vm.runInNewContext(source, {
        window, document,
        matchMedia: (query) => ({ matches: query.includes("reduced-motion") ? reduced : false }),
        localStorage: { getItem: (key) => (key === "ia-c-glass" ? glass : saved), setItem: (key, value) => { stored[key] = value; } }
    });
    return { root, style, pending, calls, stored, CTheme: window.CTheme };
}

test("the saved mode applies right away, before the first paint", () => {
    const { root, CTheme } = theme({ saved: "dark" });
    assert.equal(root.dataset.mode, "dark");
    assert.equal(CTheme.get(), "dark");
});

test("without view transitions the page changes at once", () => {
    const { root, CTheme } = theme();
    CTheme.set("dark");
    assert.equal(root.dataset.mode, "dark");
});

test("during a view transition the new mode can already be read and the page changes in the callback", () => {
    const { root, pending, calls, stored, CTheme } = theme({ transitions: true });
    CTheme.set("dark");
    assert.equal(CTheme.get(), "dark", "the theme button reads this straight after setting it");
    assert.equal(root.dataset.mode, "light", "the old look must be captured before the page changes");
    assert.deepEqual(calls, [["theme", "dark"]]);
    assert.equal(stored["ia-c-theme-mode"], "dark");
    pending[0]();
    assert.equal(root.dataset.mode, "dark");
});

test("reduced motion changes the page without a transition", () => {
    const { root, pending, CTheme } = theme({ transitions: true, reduced: true });
    CTheme.set("dark");
    assert.equal(pending.length, 0);
    assert.equal(root.dataset.mode, "dark");
});

test("glass transparency: the middle is the original look, the ends stay readable, and it applies before the first paint", () => {
    const { style, CTheme } = theme({ glass: "75" });
    const { glassScale, glassLevel } = CTheme._internal;
    assert.equal(glassScale(50), 1);
    assert.equal(glassScale(0), 1.4);
    assert.equal(glassScale(100), 0.5);
    assert.ok(glassScale(20) > glassScale(30) && glassScale(70) > glassScale(80), "more transparent all the way");
    assert.equal(style["--glass-k"], "0.75");
    assert.equal(CTheme.glass(), 75);
    for (const [given, level] of [[null, 50], ["", 50], ["abc", 50], ["-5", 0], ["130", 100], ["33.6", 34]]) {
        assert.equal(glassLevel(given), level, String(given));
    }
});

test("dragging the glass slider only repaints; letting go saves", () => {
    const { style, stored, CTheme } = theme();
    assert.equal(style["--glass-k"], "1", "nothing saved means the original look");
    CTheme.setGlass("90", false);
    assert.equal(style["--glass-k"], "0.6");
    assert.equal(stored["ia-c-glass"], undefined);
    CTheme.setGlass("90");
    assert.equal(stored["ia-c-glass"], "90");
});

/* ---------- 看图器的纱与文字面板（R4.2） ---------- */

test("one slider drives the viewer's blur and veil: solid at 0, heavy by default, light at the transparent end", () => {
    const { CTheme } = theme();
    const { veilOf } = CTheme._internal;
    assert.deepEqual({ ...veilOf(0) }, { blur: 48, alpha: 1 });
    assert.deepEqual({ ...veilOf(50) }, { blur: 48, alpha: 0.7 });
    assert.deepEqual({ ...veilOf(100) }, { blur: 8, alpha: 0.28 });
    for (let level = 1; level <= 100; level++) {
        assert.ok(veilOf(level).alpha < veilOf(level - 1).alpha, `veil thins at ${level}`);
        assert.ok(veilOf(level).blur <= veilOf(level - 1).blur, `blur never grows at ${level}`);
    }
});

test("text panels keep small text and links at 4.5 or more over the worst blurred, veiled background, at every slider position", () => {
    const { CTheme } = theme();
    const { glassLevels, veilOf, contrast, PANELS } = CTheme._internal;
    const mix = (top, a, back) => top.map((c, i) => c * a + back[i] * (1 - a));
    for (const mode of ["light", "dark"]) {
        const c = PANELS[mode];
        for (let level = 0; level <= 100; level++) {
            const g = glassLevels(level, mode);
            const veiled = mix(c.veil, veilOf(level).alpha, c.wall);
            const backs = {
                panel: veiled,
                sheetV: mix(c.scrim.slice(0, 3), c.scrim[3], mix(c.wall, c.amb, veiled)),
                sheet: mix(c.scrim.slice(0, 3), c.scrim[3], c.wall)
            };
            for (const [name, back] of Object.entries(backs)) {
                const bg = mix(c.panel, g[name], back);
                for (const text of c.texts) {
                    const ratio = contrast(text, bg);
                    assert.ok(ratio >= 4.5, `${mode} ${name} at ${level}: ${ratio.toFixed(2)}`);
                }
            }
        }
    }
    // 默认档的信息栏、编辑面板比 R4.2 之前（.84 / .72、.78 / .82）明显更透
    assert.ok(glassLevels(50, "light").panel < 0.78 && glassLevels(50, "dark").panel < 0.72);
});

test("the colours behind the contrast math are the ones the stylesheets paint", () => {
    const { PANELS } = theme().CTheme._internal;
    const dir = path.resolve(__dirname, "../../main/resources/static/c");
    const quiet = readFileSync(path.join(dir, "theme-quiet.css"), "utf8");
    const hex = (rgb) => "#" + rgb.map((v) => v.toString(16).padStart(2, "0")).join("");
    const blockOf = (css, selector) => { const i = css.indexOf(selector + " {"); return css.slice(i, css.indexOf("}", i)); };
    const light = blockOf(quiet, 'html[data-look="quiet"]'), dark = blockOf(quiet, 'html[data-look="quiet"][data-mode="dark"]');
    assert.match(light, new RegExp(`--sheet-muted: ${hex(PANELS.light.texts[0])};`));
    assert.match(dark, new RegExp(`--sheet-muted: ${hex(PANELS.dark.texts[0])};`));
    assert.match(light, /--scrim: rgba\(0, 0, 0, \.3\);/);
    assert.match(dark, /--scrim: rgba\(0, 0, 0, \.42\);/);
    for (const [css, mode] of [[light, "light"], [dark, "dark"]]) {
        const c = PANELS[mode];
        assert.ok(css.includes(`--panel-rgb: ${c.panel.join(" ")};`), `panel colour in ${mode}`);
        assert.ok(css.includes(`--veil-rgb: ${c.veil.join(" ")};`), `veil colour in ${mode}`);
        assert.ok(css.includes(`--sheet-accent: ${hex(c.texts[1])};`), `link colour in ${mode}`);
        assert.ok(css.includes("--panel-bg: rgb(var(--panel-rgb) / var(--glass-panel-a"), `info and editor follow the slider in ${mode}`);
        assert.ok(css.includes("--sheet-bg: rgb(var(--panel-rgb) / var(--glass-sheet-a"), `dialogs follow the slider in ${mode}`);
    }
    assert.match(quiet, /:is\(\.viewer, \.ctx-menu\) \{\n    --v-bg: #efefed;\n    --amb: 24%;/);
    assert.match(quiet, /\[data-mode="dark"\] :is\(\.viewer, \.ctx-menu\) \{\n    --v-bg: #0c0c0c;\n    --amb: 38%;/);
    assert.ok(quiet.includes(":has(#viewer:not([hidden])) { --sheet-bg: rgb(var(--panel-rgb) / var(--glass-sheet-va"), "dialogs over the viewer use their own floor");
});

test("the glass levels follow the slider and the light / dark switch", () => {
    const { style, CTheme } = theme();
    assert.equal(style["--veil-blur"], "48px");
    assert.equal(style["--veil-a"], "0.7");
    const lightPanel = style["--glass-panel-a"];
    CTheme.set("dark");
    assert.notEqual(style["--glass-panel-a"], lightPanel, "dark glass has its own floor");
    CTheme.setGlass("100", false);
    assert.equal(style["--veil-blur"], "8px");
    assert.equal(style["--veil-a"], "0.28");
});
