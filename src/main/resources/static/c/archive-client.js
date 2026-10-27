/*
 * C 图库 · 数据读取层：把现有只读 API 转成作品墙、看图器和牌堆共用的作品对象（window.Archive）。
 *
 *   GET /api/illustrations?page&size   分页作品列表（按导入时间倒序）
 *   GET /api/illustrations/{id}        详情：标签、备注、来源、文件名与大小
 *   GET /api/assets/{id}/thumbnail     缩略图；GIF、视频没有缩略图，缩略图缺失时退回原图
 *   GET /api/assets/{id}/content       原图
 *
 * 列表接口不返回图片尺寸，所以每页作品在加入作品墙之前，先载入封面缩略图量出宽高比，并取一个主色；
 * 缩略图失败时退回原图，再失败就用占位图，作品墙不会因为单张图片坏掉而空出一块或错位。
 * 本层只读：没有任何写接口。
 */
(function () {
    'use strict';

    const PAGE_SIZE = 30;
    const MEASURE_CONCURRENCY = 6;
    const FALLBACK_TONE = '#d8d3c8';

    const works = [];
    const byId = new Map();
    const listeners = new Set();
    let nextPage = 0;
    let total = null;
    let loading = null;
    let lastError = null;

    const thumbnailUrl = (assetId) => `/api/assets/${assetId}/thumbnail`;
    const contentUrl = (assetId) => `/api/assets/${assetId}/content`;

    function emit(type, detail = {}) {
        listeners.forEach((fn) => fn({ type, ...detail }));
    }

    // 占位图：一张纸色的 SVG，写明原因（视频暂不支持 / 图片无法载入），尺寸给出 4:5 的版面
    function placeholder(text) {
        const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="1000" viewBox="0 0 800 1000">`
            + `<rect width="800" height="1000" fill="#e9e6dd"/>`
            + `<text x="400" y="510" font-family="sans-serif" font-size="40" fill="#8a8880" text-anchor="middle">${text}</text></svg>`;
        return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    }

    function formatOf(mimeType) {
        const sub = String(mimeType || '').split('/')[1] || '';
        return { jpeg: 'jpg', 'svg+xml': 'svg' }[sub] || sub;
    }

    function pageFor(asset) {
        const mime = asset.mimeType || '';
        const video = mime.startsWith('video/');
        const gif = mime === 'image/gif';
        return {
            assetId: asset.id,
            mimeType: mime,
            format: formatOf(mime),
            video,
            // 作品墙和牌堆用缩略图；GIF 没有缩略图，直接用原图（仍是动图）
            src: video ? placeholder('视频暂不支持预览') : gif ? contentUrl(asset.id) : thumbnailUrl(asset.id),
            full: video ? null : contentUrl(asset.id),
            width: video ? 800 : null,
            height: video ? 1000 : null,
            fullWidth: null,
            fullHeight: null,
            tone: video ? FALLBACK_TONE : null,
            broken: false,
            originalFilename: null,
            size: null
        };
    }

    function toWork(item) {
        const assets = (item.assets && item.assets.length)
            ? [...item.assets].sort((a, b) => a.sortOrder - b.sortOrder)
            : [{ id: item.coverAssetId, mimeType: item.coverMimeType, sortOrder: 0 }];
        return {
            id: item.id,
            title: item.title || '',
            author: item.author || null,
            createdAt: item.createdAt ? Date.parse(item.createdAt) : null,
            pages: assets.map(pageFor),
            detail: null,
            detailError: null
        };
    }

    function loadImage(url) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.decoding = 'async';
            img.onload = () => resolve(img);
            img.onerror = () => reject(new Error('image failed: ' + url));
            img.src = url;
        });
    }

    // 主色：缩到 8×8 取平均；同源图片可以读像素
    function toneOf(img) {
        try {
            const c = document.createElement('canvas');
            c.width = 8;
            c.height = 8;
            const g = c.getContext('2d', { willReadFrequently: true });
            g.drawImage(img, 0, 0, 8, 8);
            const d = g.getImageData(0, 0, 8, 8).data;
            let r = 0, gg = 0, b = 0, n = 0;
            for (let i = 0; i < d.length; i += 4) {
                if (d[i + 3] < 16) continue;
                r += d[i]; gg += d[i + 1]; b += d[i + 2]; n++;
            }
            if (!n) return FALLBACK_TONE;
            return '#' + [r, gg, b].map((v) => Math.round(v / n).toString(16).padStart(2, '0')).join('');
        } catch (e) {
            return FALLBACK_TONE;
        }
    }

    // 量出页面的宽高比与主色：缩略图 → 原图 → 占位图
    async function measure(page) {
        if (page.width) return page;
        const tries = page.src === page.full ? [page.full] : [page.src, page.full];
        for (const url of tries) {
            try {
                const img = await loadImage(url);
                page.src = url;
                page.width = img.naturalWidth || 800;
                page.height = img.naturalHeight || 1000;
                page.tone = toneOf(img);
                return page;
            } catch (e) { /* 试下一个来源 */ }
        }
        page.broken = true;
        page.src = placeholder('图片无法载入');
        page.width = 800;
        page.height = 1000;
        page.tone = FALLBACK_TONE;
        return page;
    }

    async function measureAll(pages) {
        const queue = [...pages];
        const workers = Array.from({ length: Math.min(MEASURE_CONCURRENCY, queue.length) }, async () => {
            while (queue.length) await measure(queue.shift());
        });
        await Promise.all(workers);
    }

    class RequestError extends Error {
        constructor(status, message) {
            super(message);
            this.status = status;
        }
    }

    async function getJson(url) {
        let response;
        try {
            response = await fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
        } catch (e) {
            throw new RequestError(0, '无法连接到本地图库服务');
        }
        if (response.status === 403) throw new RequestError(403, '本机访问会话已失效，请从控制窗口重新打开图库');
        if (!response.ok) throw new RequestError(response.status, `服务返回 ${response.status}`);
        return response.json();
    }

    function hasMore() {
        return total == null || works.length < total;
    }

    // 载入下一页：同一时间只有一个请求；失败后保留已载入的作品，下一次调用从同一页重试
    function loadMore() {
        if (loading) return loading;
        if (!hasMore()) return Promise.resolve(false);
        lastError = null;
        emit('loading');
        loading = (async () => {
            let event;
            try {
                const page = await getJson(`/api/illustrations?page=${nextPage}&size=${PAGE_SIZE}`);
                const fresh = page.items.filter((item) => !byId.has(item.id)).map(toWork);
                // 每件作品的封面先量好再上墙；其余页面在牌堆或看图器用到时再量
                await measureAll(fresh.map((w) => w.pages[0]));
                fresh.forEach((w) => { works.push(w); byId.set(w.id, w); });
                total = page.totalElements;
                nextPage = page.page + 1;
                if (page.items.length === 0) total = works.length;
                event = ['append', { count: fresh.length }];
            } catch (error) {
                lastError = error;
                event = ['error', { error }];
            }
            // 先结束“载入中”再通知，界面据此显示的是载入完成后的状态
            loading = null;
            emit(...event);
            return event[0] === 'append';
        })();
        return loading;
    }

    async function loadDetail(id) {
        const work = byId.get(id);
        if (!work) return null;
        if (work.detail) return work.detail;
        work.detailError = null;
        try {
            const detail = await getJson(`/api/illustrations/${id}`);
            work.detail = {
                sourceUrl: detail.sourceUrl || '',
                note: detail.note || '',
                tags: detail.tags || [],
                updatedAt: detail.updatedAt ? Date.parse(detail.updatedAt) : null
            };
            (detail.assets || []).forEach((asset) => {
                const page = work.pages.find((p) => p.assetId === asset.id);
                if (page) {
                    page.originalFilename = asset.originalFilename;
                    page.size = asset.fileSize;
                }
            });
            emit('detail', { id });
            return work.detail;
        } catch (error) {
            work.detailError = error;
            emit('detail', { id });
            return null;
        }
    }

    // 看图器用：载入原图并记下真实尺寸；失败时返回 null，界面保留缩略图并说明
    async function loadFull(page) {
        if (!page.full) return null;
        if (page.fullWidth) return page;
        try {
            const img = await loadImage(page.full);
            page.fullWidth = img.naturalWidth;
            page.fullHeight = img.naturalHeight;
            if (!page.width) {
                page.width = img.naturalWidth;
                page.height = img.naturalHeight;
                page.tone = page.tone || toneOf(img);
            }
            return page;
        } catch (e) {
            return null;
        }
    }

    function displayTitle(work) {
        if (work.title) return work.title;
        const name = work.pages[0].originalFilename;
        return name ? name.replace(/\.[^.]+$/, '') : '未命名作品';
    }

    function authorName(work) {
        const a = work.author;
        return a ? (a.displayName || (a.xUsername ? '@' + a.xUsername : '')) : '';
    }

    function formatSize(bytes) {
        if (bytes == null) return '—';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    }

    function formatDate(ms) {
        if (ms == null || Number.isNaN(ms)) return '—';
        const d = new Date(ms);
        const pad = (n) => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    function relativeDay(ms) {
        if (ms == null || Number.isNaN(ms)) return '';
        const start = (t) => { const d = new Date(t); d.setHours(0, 0, 0, 0); return d.getTime(); };
        const days = Math.round((start(Date.now()) - start(ms)) / 86400000);
        if (days <= 0) return '今天';
        if (days === 1) return '昨天';
        if (days < 30) return `${days} 天前`;
        return formatDate(ms).slice(0, 10) + ' ';
    }

    window.Archive = {
        PAGE_SIZE,
        works,
        get: (id) => byId.get(id) || null,
        total: () => total,
        hasMore,
        isLoading: () => loading != null,
        lastError: () => lastError,
        loadMore,
        loadDetail,
        loadFull,
        measure,
        displayTitle,
        authorName,
        formatSize,
        formatDate,
        relativeDay,
        subscribe: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
        // 供测试使用的纯函数
        _internal: { toWork, pageFor, formatOf, placeholder }
    };
})();
