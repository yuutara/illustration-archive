/*
 * C 图库 · 图片右键菜单（只在应用窗口里）：“复制图片”“另存为…”。
 * 应用窗口收掉了 WebView2 自带的右键菜单（shell.js），这里给作品图片补回最常用的两项。作品墙上的是缩略图，
 * 菜单一律取原图：作品墙按牌堆当前页，看图器按当前显示的这张。浏览器里用浏览器自己的菜单，这里不介入。
 *
 * 复制：剪贴板只收 PNG，其他格式先在页面里转一次（GIF 只取第一帧）；视频不能复制，只能另存。
 * 另存为：用系统的保存对话框，文件名取导入时的原文件名，内容是原图本身，不重新编码。
 */
(function () {
    'use strict';

    const ASSET = /\/api\/assets\/(\d+)\/(?:thumbnail|content)$/;
    const EXTENSIONS = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/webp': 'webp', 'video/mp4': 'mp4' };

    /** 图片地址 → 作品文件的 id；不是作品文件（占位图、图标、X 上的图）时为 null */
    function assetIdFrom(src, base) {
        if (!src) return null;
        try {
            const url = new URL(src, base);
            if (url.origin !== new URL(base).origin) return null;
            const match = ASSET.exec(url.pathname);
            return match ? Number(match[1]) : null;
        } catch (e) { return null; }
    }

    /** 保存时的文件名：原文件名；还不知道时用 id 和格式 */
    function fileNameFor(assetId, originalFilename, mimeType) {
        const name = String(originalFilename || '').trim();
        if (name) return name;
        return `插画-${assetId}.${EXTENSIONS[mimeType] || 'bin'}`;
    }

    /** 右键点到的是哪张图：作品墙上取这件作品当前显示的那页，其余取点到的图片或视频 */
    function mediaAt(target) {
        if (!(target instanceof Element)) return null;
        const piece = target.closest('.piece');
        if (piece) return piece.querySelector('.piece-video') || piece.querySelector('.piece-open img');
        return target.closest('img, video');
    }

    window.CImageMenu = { _internal: { assetIdFrom, fileNameFor, mediaAt } };

    if (!window.iaDesktop) return;

    const A = window.Archive;
    const api = () => window.GalleryApi;
    const toast = (text) => { if (api()) api().toast(text); };
    const contentUrl = (assetId) => `/api/assets/${assetId}/content`;

    function pageOf(assetId) {
        for (const work of A.works) {
            const page = work.pages.find((p) => p.assetId === assetId);
            if (page) return { work, page };
        }
        return null;
    }

    async function fetchOriginal(assetId) {
        const response = await fetch(contentUrl(assetId));
        if (!response.ok) throw new Error(response.status === 404 ? '原图已不在图库里' : `读取原图失败（${response.status}）`);
        return response.blob();
    }

    async function asPng(blob) {
        if (blob.type === 'image/png') return blob;
        const bitmap = await createImageBitmap(blob);
        const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
        canvas.getContext('2d').drawImage(bitmap, 0, 0);
        bitmap.close();
        return canvas.convertToBlob({ type: 'image/png' });
    }

    // 写剪贴板要在点击的这一刻发起；图片随后再到，所以交给剪贴板的是一个 Promise
    async function copyImage(assetId) {
        try {
            await navigator.clipboard.write([new ClipboardItem({ 'image/png': fetchOriginal(assetId).then(asPng) })]);
            toast('已复制图片');
        } catch (e) {
            toast(`没能复制图片：${e.message || e}`);
        }
    }

    async function saveImage(assetId) {
        const found = pageOf(assetId);
        if (found && !found.page.originalFilename) await A.loadDetail(found.work.id);
        const name = fileNameFor(assetId, found && found.page.originalFilename, found && found.page.mimeType);
        if (typeof window.showSaveFilePicker !== 'function') {
            // 没有保存对话框时按下载处理，存到系统的“下载”文件夹
            const link = document.createElement('a');
            link.href = contentUrl(assetId);
            link.download = name;
            link.click();
            return;
        }
        let handle;
        try {
            handle = await window.showSaveFilePicker({ suggestedName: name });
        } catch (e) {
            if (e.name !== 'AbortError') toast(`没能打开保存对话框：${e.message || e}`);
            return;
        }
        try {
            const blob = await fetchOriginal(assetId);
            const writable = await handle.createWritable();
            await writable.write(blob);
            await writable.close();
            toast(`已保存 ${handle.name}`);
        } catch (e) {
            toast(`没能保存：${e.message || e}`);
        }
    }

    const menu = document.createElement('div');
    menu.className = 'sv-menu ctx-menu';
    menu.setAttribute('role', 'menu');
    menu.hidden = true;
    menu.innerHTML = '<button type="button" role="menuitem" data-act="copy">复制图片</button>'
        + '<button type="button" role="menuitem" data-act="save">另存为…</button>';
    document.body.appendChild(menu);
    let current = null;

    function close() {
        if (menu.hidden) return;
        menu.hidden = true;
        current = null;
    }

    function open(assetId, video, x, y) {
        current = assetId;
        menu.querySelector('[data-act="copy"]').disabled = video;
        menu.querySelector('[data-act="copy"]').title = video ? '视频不能复制，可以另存' : '';
        menu.hidden = false;
        // 贴着指针展开，靠近窗口边缘时向内翻
        const { width, height } = menu.getBoundingClientRect();
        menu.style.left = Math.max(4, Math.min(x, innerWidth - width - 4)) + 'px';
        menu.style.top = Math.max(4, Math.min(y, innerHeight - height - 4)) + 'px';
        menu.querySelector('button:not(:disabled)').focus({ preventScroll: true });
    }

    document.addEventListener('contextmenu', (e) => {
        const media = mediaAt(e.target);
        const assetId = media && assetIdFrom(media.currentSrc || media.getAttribute('src'), location.href);
        if (!assetId) { close(); return; }
        e.preventDefault();
        open(assetId, media.tagName === 'VIDEO', e.clientX, e.clientY);
    });
    menu.addEventListener('click', (e) => {
        const item = e.target.closest('button[data-act]');
        if (!item || item.disabled || current == null) return;
        const assetId = current;
        close();
        if (item.dataset.act === 'copy') copyImage(assetId);
        else saveImage(assetId);
    });
    document.addEventListener('mousedown', (e) => { if (!menu.contains(e.target)) close(); }, true);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !menu.hidden) { e.stopPropagation(); close(); } }, true);
    addEventListener('blur', close);
    addEventListener('resize', close);
    document.addEventListener('scroll', close, true);
})();
