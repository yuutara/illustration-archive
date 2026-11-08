const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const read = (name) => readFileSync(path.join(dir, name), "utf8");

const theme = read("theme-quiet.css");
const block = (selector) => {
    const start = theme.indexOf(selector + " {");
    assert.notEqual(start, -1, selector);
    return theme.slice(start, theme.indexOf("\n}", start));
};
const declared = (css) => new Set([...css.matchAll(/^\s*(--[a-z-]+):/gm)].map((match) => match[1]));

test("every dialog token exists in both themes, so dark mode never falls back to nothing", () => {
    const light = declared(block('html[data-look="quiet"]'));
    const dark = declared(block('html[data-look="quiet"][data-mode="dark"]'));
    const tokens = ["--well", "--well-hover", "--well-edge", "--dash", "--scrim", "--sheet-bg", "--sheet-shadow", "--focus-ring", "--accent-wash", "--danger-wash"];
    for (const token of tokens) {
        assert.ok(light.has(token), `${token} in light`);
        assert.ok(dark.has(token), `${token} in dark`);
    }
});

test("the stylesheets use no dialog token that the theme does not define", () => {
    const known = declared(theme);
    for (const sheet of ["gallery.css", "c.css", "app.css", "theme-quiet.css"]) {
        const used = [...read(sheet).matchAll(/var\((--(?:well|dash|scrim|sheet|focus|accent-wash|danger-wash)[a-z-]*)/g)].map((match) => match[1]);
        for (const token of used) assert.ok(known.has(token), `${token} in ${sheet}`);
    }
});

test("the drop area offers a real button and keeps the ids the importer depends on", () => {
    const page = read("index.html");
    const zone = page.slice(page.indexOf('id="dropzone"'), page.indexOf('id="import-bar"'));
    assert.match(zone, /<button class="btn" id="import-pick" type="button">/);
    assert.doesNotMatch(zone, /class="link"/);
});

test("a dialog is glass, and nothing around it stops the glass from reaching the page", () => {
    const sheet = theme.match(/html\[data-look="quiet"\] \.sheet \{[^}]*\}/)[0];
    assert.match(sheet, /background: var\(--sheet-bg\)/);
    assert.match(sheet, /backdrop-filter: var\(--gl-blur-l\)/);
    const scrim = theme.match(/html\[data-look="quiet"\] \.sheet-backdrop \{[^}]*\}/)[0];
    assert.doesNotMatch(scrim, /backdrop-filter|filter:/, "a filter on the scrim would cut the sheet off from the page");
    assert.match(theme, /\.sheet-backdrop\[hidden\] \{ opacity: 1;/, "so would fading it with opacity");
    assert.match(theme, /prefers-reduced-transparency: reduce\) \{\n[^\n]*\.sheet[^\n]*background: var\(--card\)/);
});

const value = (css, token) => css.match(new RegExp(`^\\s*${token}: (.+);$`, "m"))[1];

// 文字面板的对比度按滑块每一档在 c-theme.test.js 里核对（最坏背景 = 模糊、盖纱之后的纯黑 / 纯白）；这里只核对样式确实用了那几个算好的值
test("text panels take the opacity the theme computes, and use the colours that contrast check was made with", () => {
    assert.match(theme, /:is\(\.sheet, \.sv-editor, \.sv-info\) \{ --muted: var\(--sheet-muted\); --faint: var\(--sheet-muted\); --accent-text: var\(--sheet-accent\); \}/);
    assert.match(theme, /\.sv-info \{[^}]*background: var\(--panel-bg\)/);
    assert.match(theme, /\.sv-editor \{[^}]*background: var\(--panel-bg\)/);
    for (const selector of ['html[data-look="quiet"]', 'html[data-look="quiet"][data-mode="dark"]']) {
        const css = block(selector);
        assert.match(value(css, "--panel-bg"), /var\(--glass-panel-a, [\d.]+\)/, `--panel-bg in ${selector}`);
        assert.match(value(css, "--sheet-bg"), /var\(--glass-sheet-a, [\d.]+\)/, `--sheet-bg in ${selector}`);
    }
});

test("the glass slider reaches all glass", () => {
    for (const selector of ['html[data-look="quiet"]', 'html[data-look="quiet"][data-mode="dark"]']) {
        const css = block(selector);
        for (const token of ["--gl-bg", "--gl-bg-hover", "--gl-bg-panel", "--gl-bg-bar", "--masthead-glass"]) {
            assert.match(value(css, token), /var\(--glass-k, 1\)/, `${token} in ${selector}`);
        }
    }
    for (const selector of ['html[data-look="quiet"] :is(.viewer, .ctx-menu)', 'html[data-look="quiet"][data-mode="dark"] :is(.viewer, .ctx-menu)']) {
        const css = block(selector);
        for (const token of ["--gd-bg", "--gd-bg-panel"]) assert.match(value(css, token), /var\(--glass-k, 1\)/, `${token} in ${selector}`);
    }
    // 看图器背后的纱：模糊和浓淡也由滑块给出；关掉透明效果时不画
    assert.match(theme, /background-color: rgb\(var\(--veil-rgb\) \/ calc\(var\(--veil-a, [\d.]+\) \* var\(--veil-o\)\)\)/);
    assert.match(theme, /backdrop-filter: blur\(calc\(var\(--veil-blur, \d+px\) \* var\(--veil-o\)\)\)/);
    assert.match(theme, /@media not \(prefers-reduced-transparency: reduce\) \{\n    html\[data-look="quiet"\] \.viewer \{ background-color: transparent;/);
});
