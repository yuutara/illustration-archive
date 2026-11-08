/*
 * C 图库 · 浅色 / 深色与玻璃透明度：在 <head> 里同步执行，首屏就用对的配色，避免闪一下。
 * 主题固定为静展；选择只保存在本机浏览器，首次使用跟随系统设置。
 * 玻璃透明度（设置页“外观”）一个滑块管全部：所有玻璃的不透明度、看图器背后作品墙的模糊和纱的浓淡，
 * 以及信息栏、编辑面板、对话框的不透明度（按最坏的背景算出下限，保证字看得清）。
 */
(function () {
    'use strict';

    const KEY = 'ia-c-theme-mode';
    const GLASS_KEY = 'ia-c-glass';
    const GLASS_DEFAULT = 50;
    const root = document.documentElement;

    function read() {
        try {
            const saved = localStorage.getItem(KEY);
            if (saved === 'light' || saved === 'dark') return saved;
        } catch (e) { /* 隐私模式等情况下没有存储 */ }
        return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }

    function apply(mode) {
        root.dataset.look = 'quiet';
        root.dataset.mode = mode;
    }

    // 透明度 0–100 → 玻璃底色不透明度的倍数：50 是原本的样子，0 接近实底，100 约为原来的一半
    function glassScale(level) {
        const k = level <= 50 ? 1.4 - 0.8 * level / 100 : 1 - (level - 50) / 100;
        return Math.round(k * 1000) / 1000;
    }

    const glassLevel = (value) => {
        const n = Math.round(Number(value));
        return value !== null && value !== '' && Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : GLASS_DEFAULT;
    };

    function readGlass() {
        try { return glassLevel(localStorage.getItem(GLASS_KEY)); } catch (e) { return GLASS_DEFAULT; }
    }

    /*
     * 看图器空白处是一层纱：背后的作品墙先模糊，再盖一层与看图器同色的半透明底。
     * 滑块同时管纱的模糊、纱的浓淡和所有玻璃的不透明度：0 是实底，50（默认）重度模糊、纱较浓，100 轻度模糊、纱很淡。
     */
    function veilOf(level) {
        const blur = level <= 50 ? 48 : 48 - 40 * (level - 50) / 50;
        const alpha = level <= 50 ? 1 - 0.3 * level / 50 : 0.7 - 0.42 * (level - 50) / 50;
        return { blur: Math.round(blur * 10) / 10, alpha: Math.round(alpha * 1000) / 1000 };
    }

    /*
     * 文字面板的不透明度下限，按“模糊、盖纱之后最坏的背景”计算：作品墙模糊后最暗不过纯黑（浅色）、最亮不过纯白（深色），
     * 再依次盖上纱、作品主色光晕（只在对话框背后；信息栏和编辑面板背后没有光晕）和对话框遮罩。
     * 面板里的小字（--sheet-muted）和链接（--sheet-accent）都要不低于 4.5，留一点余量按 4.6 算。
     * 颜色与 theme-quiet.css 的同名变量一致，c-theme.test.js 核对两边没有走样。
     */
    const PANELS = {
        light: { veil: [239, 239, 237], panel: [255, 255, 255], scrim: [0, 0, 0, 0.3], amb: 0.24, wall: [0, 0, 0], texts: [[0x52, 0x52, 0x4e], [0x2b, 0x42, 0xbf]] },
        dark: { veil: [12, 12, 12], panel: [28, 28, 28], scrim: [0, 0, 0, 0.42], amb: 0.38, wall: [255, 255, 255], texts: [[0xa8, 0xa8, 0xa1], [0xa8, 0xb7, 0xff]] }
    };
    const MIN_CONTRAST = 4.6;
    const MARGIN = 0.04;
    const mix = (top, a, back) => top.map((c, i) => c * a + back[i] * (1 - a));
    function luminance(rgb) {
        const [r, g, b] = rgb.map((v) => {
            const c = v / 255;
            return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
    function contrast(a, b) {
        const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
        return (hi + 0.05) / (lo + 0.05);
    }
    function floorAlpha(c, back) {
        for (let i = 0; i <= 100; i++) {
            const bg = mix(c.panel, i / 100, back);
            if (c.texts.every((t) => contrast(t, bg) >= MIN_CONTRAST)) return i / 100;
        }
        return 1;
    }
    function glassLevels(level, mode) {
        const c = PANELS[mode];
        const v = veilOf(level);
        const k = glassScale(level);
        const veiled = (alpha) => mix(c.veil, alpha, c.wall);
        // 光晕最坏时取与纱相反的极端色：浅色是黑，深色是白
        const glowed = (back) => mix(c.wall, c.amb, back);
        const scrimmed = (back) => mix(c.scrim.slice(0, 3), c.scrim[3], back);
        const backs = {
            panel: (alpha) => veiled(alpha),
            sheetV: (alpha) => scrimmed(glowed(veiled(alpha))),
            sheet: () => scrimmed(c.wall)
        };
        const out = { blur: v.blur, veil: v.alpha };
        const dflt = veilOf(GLASS_DEFAULT).alpha;
        for (const [name, back] of Object.entries(backs)) {
            // 默认档：刚好看得清再留一点余量；其余档跟着滑块的倍数变，但不低于这一档自己的下限
            const floor = floorAlpha(c, back(v.alpha));
            const base = Math.min(1, floorAlpha(c, back(dflt)) + MARGIN);
            out[name] = Math.round(Math.min(1, Math.max(floor, base * k)) * 100) / 100;
        }
        return out;
    }

    function applyGlass(level) {
        root.style.setProperty('--glass-k', String(glassScale(level)));
        const g = glassLevels(level, current);
        root.style.setProperty('--veil-blur', g.blur + 'px');
        root.style.setProperty('--veil-a', String(g.veil));
        root.style.setProperty('--glass-panel-a', String(g.panel));
        root.style.setProperty('--glass-sheet-va', String(g.sheetV));
        root.style.setProperty('--glass-sheet-a', String(g.sheet));
    }

    // startViewTransition 的回调要到下一帧才执行，所以当前模式单独记着：切换后立刻读取也要拿到新值
    let current = read();
    apply(current);
    let glass = readGlass();
    applyGlass(glass);

    window.CTheme = {
        get: () => current,
        set(mode) {
            current = mode;
            const switchTo = () => { apply(mode); applyGlass(glass); };
            if (document.startViewTransition && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
                document.startViewTransition(switchTo);
            } else {
                switchTo();
            }
            try { localStorage.setItem(KEY, mode); } catch (e) { /* 同上 */ }
            if (window.iaDesktop) window.iaDesktop('theme', mode); // 应用窗口的标题栏跟着变
        },
        glass: () => glass,
        GLASS_DEFAULT,
        // 拖动时 save 为 false，只改画面；松手再存
        setGlass(level, save = true) {
            glass = glassLevel(level);
            applyGlass(glass);
            if (save) {
                try { localStorage.setItem(GLASS_KEY, String(glass)); } catch (e) { /* 同上 */ }
            }
        },
        _internal: { glassScale, glassLevel, veilOf, glassLevels, contrast, PANELS, MIN_CONTRAST }
    };
})();
