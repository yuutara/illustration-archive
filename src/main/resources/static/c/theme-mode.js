/*
 * C 图库 · 浅色 / 深色与玻璃透明度：在 <head> 里同步执行，首屏就用对的配色，避免闪一下。
 * 主题固定为静展；选择只保存在本机浏览器，首次使用跟随系统设置。
 * 玻璃透明度（设置页“外观”）调整全部玻璃。顶栏、图上按钮、浮层、操作条和看图器工具两个方向都跟着变；
 * 对话框、编辑面板和看图器信息栏是成段的文字，只会变得更实，不会比默认更透（主题按最坏的背景保证可读）。
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

    function applyGlass(level) {
        root.style.setProperty('--glass-k', String(glassScale(level)));
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
            if (document.startViewTransition && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
                document.startViewTransition(() => apply(mode));
            } else {
                apply(mode);
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
        _internal: { glassScale, glassLevel }
    };
})();
