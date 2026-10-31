/*
 * 应用外壳：三个页面共用的顶部导航。
 * 桌面版的 <html> 带 is-desktop（<head> 里同步加上，导航不闪）：“图库 / X 收藏 / 设置”是唯一的导航，
 * 应用名已在窗口标题栏，不再重复；原 MySQL 运行方式只有这一页，保留应用名和“旧图库”。
 * 在应用窗口（WebView2）里，window.iaDesktop 是通往窗口的桥：这里把网页该有、桌面软件不该有的行为收掉。
 * 没有 WebView2 时应用跑在浏览器里，这些保持浏览器原样。
 */
(function () {
    'use strict';

    const desktop = !!(window.UiCapabilities && window.UiCapabilities.desktop);
    const LAUNCH_KEY = 'ia-launched';

    if (!desktop) return;

    async function refreshInbox() {
        const badge = document.getElementById('nav-inbox');
        if (!badge) return;
        try {
            const response = await fetch('/api/x-import/inbox?page=0&size=1', { headers: { Accept: 'application/json' } });
            if (!response.ok) return;
            const total = (await response.json()).totalItems;
            badge.textContent = total > 0 ? String(total) : '';
        } catch (e) { /* 数量只是提示，读不到就不显示 */ }
    }

    // 刷新、查找、打印、开发者工具这类浏览器快捷键。Ctrl+A / C / V / X / Z 留给页面和输入框
    function isBrowserShortcut(e) {
        if (e.key === 'F5' || e.key === 'F7' || e.key === 'F12') return true;
        if (!e.ctrlKey || e.altKey) return false;
        return (e.shiftKey ? 'cijnprt' : 'fghjlnoprstu').includes(e.key.toLowerCase());
    }

    function isExternal(link) {
        return link.origin !== location.origin && (link.protocol === 'https:' || link.protocol === 'http:');
    }

    // 外部链接交给默认浏览器：应用窗口是独立的配置，里面没有登录 X
    document.addEventListener('click', (e) => {
        const link = e.target instanceof Element ? e.target.closest('a[href]') : null;
        if (!link) return;
        if (window.iaDesktop) {
            if (!isExternal(link)) return;
            e.preventDefault();
            window.iaDesktop('open', link.href);
        } else if (link.hasAttribute('data-external')) {
            e.preventDefault();
            fetch('/api/desktop/open-external', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: link.href })
            }).catch(() => { /* 打不开时链接本身仍可复制 */ });
        }
    });

    if (window.iaDesktop) {
        document.addEventListener('keydown', (e) => {
            if (isBrowserShortcut(e)) e.preventDefault();
        }, true);
        // 输入框和选中的文字保留系统菜单，复制粘贴要用
        document.addEventListener('contextmenu', (e) => {
            const editing = e.target instanceof Element && e.target.closest('input, textarea');
            if (!editing && !String(window.getSelection())) e.preventDefault();
        });
        // 窗口没有标签页；看图器本身就能看原图
        const originalLink = document.getElementById('sv-original');
        if (originalLink) originalLink.style.display = 'none';
        if (window.CTheme) window.iaDesktop('theme', window.CTheme.get());
    }

    // sessionStorage 随窗口结束，所以这里判断的是“这次启动的第一屏”，之后切页不再重复
    function firstScreenOfLaunch() {
        try {
            if (sessionStorage.getItem(LAUNCH_KEY)) return false;
            sessionStorage.setItem(LAUNCH_KEY, '1');
            return true;
        } catch (e) { return false; /* 读不到存储就不做这个过渡 */ }
    }

    let announced = false;
    // 首屏作品已上墙：窗口这时才显现。顶栏不动，作品区淡入，接在启动图之后
    function ready() {
        if (announced || !window.iaDesktop) return;
        announced = true;
        window.iaDesktop('ready', '');
        const content = document.querySelector('.wall-wrap');
        if (!content || !firstScreenOfLaunch() || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        content.animate(
            [{ opacity: 0, translate: '0 8px' }, { opacity: 1, translate: '0 0' }],
            { duration: 280, delay: 60, easing: 'cubic-bezier(.2, 0, 0, 1)', fill: 'backwards' }
        );
    }

    window.AppShell = { refreshInbox, ready };
    refreshInbox();
})();
