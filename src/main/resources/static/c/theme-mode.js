/*
 * C 图库 · 浅色 / 深色：在 <head> 里同步执行，首屏就用对的配色，避免闪一下。
 * 主题固定为静展；选择只保存在本机浏览器，首次使用跟随系统设置。
 */
(function () {
    'use strict';

    const KEY = 'ia-c-theme-mode';
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

    // startViewTransition 的回调要到下一帧才执行，所以当前模式单独记着：切换后立刻读取也要拿到新值
    let current = read();
    apply(current);

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
        }
    };
})();
