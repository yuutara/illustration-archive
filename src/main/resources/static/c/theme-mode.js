/*
 * C 图库 · 浅色 / 深色：在 <head> 里同步执行，首屏就用对的配色，避免闪一下。
 * 方案固定为 C 暖纸；选择只保存在本机浏览器，首次使用跟随系统设置。
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
        root.dataset.look = 'paper';
        root.dataset.mode = mode;
    }

    apply(read());

    window.CTheme = {
        get: () => root.dataset.mode,
        set(mode) {
            apply(mode);
            try { localStorage.setItem(KEY, mode); } catch (e) { /* 同上 */ }
        }
    };
})();
