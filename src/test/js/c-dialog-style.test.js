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
const rgba = (text) => {
    const hex = text.match(/^#(..)(..)(..)$/);
    if (hex) return [...hex.slice(1).map((part) => parseInt(part, 16)), 1];
    return text.match(/[\d.]+/g).map(Number);
};
const over = ([r, g, b, a], back) => [r, g, b].map((channel, i) => channel * a + back[i] * (1 - a));
const luminance = (color) => {
    const [r, g, b] = color.map((channel) => {
        const c = channel / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
    const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
    return (high + 0.05) / (low + 0.05);
};

test("small text stays readable on the glass over the worst artwork behind it", () => {
    const worst = [["light", 'html[data-look="quiet"]', [0, 0, 0]], ["dark", 'html[data-look="quiet"][data-mode="dark"]', [255, 255, 255]]];
    for (const [name, selector, art] of worst) {
        const css = block(selector);
        const scrimmed = over(rgba(value(css, "--scrim")), art);
        const glass = over(rgba(value(css, "--sheet-bg")), scrimmed);
        const muted = rgba(value(css, "--sheet-muted"));
        assert.ok(contrast(muted.slice(0, 3), glass) >= 4.5, `${name}: ${contrast(muted.slice(0, 3), glass).toFixed(2)}`);
    }
    assert.match(theme, /:is\(\.sheet, \.sv-editor\) \{ --muted: var\(--sheet-muted\); --faint: var\(--sheet-muted\); \}/);
});

test("light and dark glass let the same amount of the page through", () => {
    const alpha = (selector) => rgba(value(block(selector), "--sheet-bg"))[3];
    const light = alpha('html[data-look="quiet"]');
    const dark = alpha('html[data-look="quiet"][data-mode="dark"]');
    assert.ok(Math.abs(light - dark) <= 0.05, `light ${light}, dark ${dark}`);
});

test("the glass slider reaches the floating glass in both themes but never a dialog, whose contrast is checked above", () => {
    for (const selector of ['html[data-look="quiet"]', 'html[data-look="quiet"][data-mode="dark"]']) {
        const css = block(selector);
        for (const token of ["--gl-bg", "--gl-bg-hover", "--gl-bg-panel", "--gl-bg-bar", "--masthead-glass"]) {
            assert.match(value(css, token), /var\(--glass-k, 1\)/, `${token} in ${selector}`);
        }
        assert.doesNotMatch(value(css, "--sheet-bg"), /glass-k/, `--sheet-bg in ${selector}`);
    }
    for (const selector of ['html[data-look="quiet"] :is(.viewer, .ctx-menu)', 'html[data-look="quiet"][data-mode="dark"] :is(.viewer, .ctx-menu)']) {
        const css = block(selector);
        for (const token of ["--gd-bg", "--gd-bg-panel"]) assert.match(value(css, token), /var\(--glass-k, 1\)/, `${token} in ${selector}`);
        assert.doesNotMatch(value(css, "--gd-bg-info"), /glass-k/, "the viewer's info text stays on steady glass");
    }
    assert.match(theme, /\.sv-info \{[^}]*background: var\(--gd-bg-info\)/);
});
