/*
 * C 图库 · 数据层：把现有 API 转成作品墙、看图器和牌堆共用的作品对象（window.Archive）。
 *
 *   GET /api/illustrations?page&size&q&authorId&tagId
 *                                      分页作品列表（按导入时间倒序），由服务端在全库上筛选：
 *                                      作者之间、标签之间为“或”，作者、标签、关键词三组之间为“且”
 *   GET /api/authors|tags?keyword&limit&offset
 *                                      作者 / 标签筛选候选，按名称排序、分页
 *   GET /api/illustrations/{id}        详情：标题、作者、标签、备注、来源、文件名与大小
 *   PATCH /api/illustrations/{id}      单件编辑：只发送改动过的字段，清空作者为 null、清空标签为 []；成功为 204，无响应体
 *   GET /api/assets/{id}/thumbnail     缩略图；GIF、视频没有缩略图，缩略图缺失时退回原图
 *   GET /api/assets/{id}/content       原图
 *
 * 列表接口不返回图片尺寸，所以每页作品在加入作品墙之前，先载入封面缩略图量出宽高比，并取一个主色；
 * 缩略图失败时退回原图，再失败就用占位图，作品墙不会因为单张图片坏掉而空出一块或错位。
 * 查询条件一变，结果、页码和总数全部重来：旧查询还在路上的列表请求被取消，迟到的响应、错误和图片测量一律丢弃，
 * 不会混进新结果。量过的封面按 asset id 记住，换条件后再遇到同一张图不必重量。
 * 唯一的写操作是单件编辑（PATCH）。保存后强制重读这件作品的详情；有搜索或筛选时，再按服务端结果重读已载入的各页，
 * 作品因修改不再符合条件就从结果中移出、总数随之变化，分页不重复。
 */
(function () {
    'use strict';

    const PAGE_SIZE = 30;
    const FACET_PAGE_SIZE = 30;
    const MEASURE_CONCURRENCY = 6;
    const FALLBACK_TONE = '#d8d3c8';

    // works 始终是同一个数组（作品墙和动效层直接引用），换查询时清空重填
    const works = [];
    const byId = new Map();
    // 编辑后因不再符合当前条件而移出结果的作品：看图器还停在它上面时仍要能读到，换查询时清空
    const retained = new Map();
    const measured = new Map();
    const detailLoads = new Map();
    let detailSeq = 0;
    const listeners = new Set();
    let query = { q: '', authorIds: [], tagIds: [] };
    let generation = 0;
    let started = false;
    let controller = null;
    let nextPage = 0;
    let total = null;
    let filters = null;
    let loading = null;
    let lastError = null;

    const thumbnailUrl = (assetId) => `/api/assets/${assetId}/thumbnail`;
    const contentUrl = (assetId) => `/api/assets/${assetId}/content`;

    const idList = (list) => [...new Set((list || []).map(Number).filter((n) => Number.isInteger(n) && n > 0))]
        .sort((a, b) => a - b);

    // 条件规范化：关键词去首尾空白，id 去重排序；同样的条件得到同样的内容，便于判断“没变”
    function normalizeQuery(next = {}) {
        return { q: String(next.q || '').trim(), authorIds: idList(next.authorIds), tagIds: idList(next.tagIds) };
    }

    const sameQuery = (a, b) => a.q === b.q && a.authorIds.join() === b.authorIds.join() && a.tagIds.join() === b.tagIds.join();

    // 作者、标签各自用重复参数（authorId=1&authorId=2），与旧图库和后端契约一致；参数名也用于页面地址
    function queryParams(q) {
        const params = new URLSearchParams();
        if (q.q) params.append('q', q.q);
        q.authorIds.forEach((id) => params.append('authorId', String(id)));
        q.tagIds.forEach((id) => params.append('tagId', String(id)));
        return params;
    }

    function listUrl(q, page) {
        const extra = queryParams(q).toString();
        return `/api/illustrations?page=${page}&size=${PAGE_SIZE}` + (extra ? '&' + extra : '');
    }

    // 页面地址里的条件（/c/?q=…&authorId=…&tagId=…），刷新后保留
    function queryFromSearch(search) {
        const params = new URLSearchParams(search || '');
        return normalizeQuery({ q: params.get('q'), authorIds: params.getAll('authorId'), tagIds: params.getAll('tagId') });
    }

    function searchOf(q) {
        const text = queryParams(normalizeQuery(q)).toString();
        return text ? '?' + text : '';
    }

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

    // stillWanted() 变成 false（查询已经换了）后不再领新的图片去量
    async function measureAll(pages, stillWanted = () => true) {
        const queue = [...pages];
        const workers = Array.from({ length: Math.min(MEASURE_CONCURRENCY, queue.length) }, async () => {
            while (queue.length && stillWanted()) await measure(queue.shift());
        });
        await Promise.all(workers);
    }

    const MEASURED_FIELDS = ['src', 'width', 'height', 'tone', 'broken'];

    function reuseMeasured(page) {
        const known = measured.get(page.assetId);
        if (known && !page.width) MEASURED_FIELDS.forEach((k) => { page[k] = known[k]; });
    }

    function rememberMeasured(page) {
        if (page.width && !page.video) measured.set(page.assetId, Object.fromEntries(MEASURED_FIELDS.map((k) => [k, page[k]])));
    }

    class RequestError extends Error {
        constructor(status, message) {
            super(message);
            this.status = status;
        }
    }

    async function getJson(url, signal) {
        let response;
        try {
            response = await fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin', signal });
        } catch (e) {
            if (signal && signal.aborted) throw e;
            throw new RequestError(0, '无法连接到本地图库服务');
        }
        if (response.status === 403) throw new RequestError(403, '本机访问会话已失效，请从控制窗口重新打开图库');
        if (!response.ok) throw new RequestError(response.status, `服务返回 ${response.status}`);
        return response.json();
    }

    function hasMore() {
        return total == null || works.length < total;
    }

    // 载入下一页：同一时间只有一个请求；失败后保留已载入的作品，下一次调用从同一页重试。
    // 请求属于发出时的查询（generation）：查询换了以后，它的响应、测量结果和错误都不再生效。
    function loadMore() {
        if (loading) return loading;
        if (!hasMore()) return Promise.resolve(false);
        started = true;
        lastError = null;
        const gen = generation;
        const current = () => gen === generation;
        const signal = controller ? controller.signal : undefined;
        loading = (async () => {
            let event;
            try {
                const page = await getJson(listUrl(query, nextPage), signal);
                if (!current()) return false;
                const fresh = page.items.filter((item) => !byId.has(item.id)).map(toWork);
                // 每件作品的封面先量好再上墙；其余页面在牌堆或看图器用到时再量
                const covers = fresh.map((w) => w.pages[0]);
                covers.forEach(reuseMeasured);
                await measureAll(covers, current);
                if (!current()) return false;
                covers.forEach(rememberMeasured);
                fresh.forEach((w) => { works.push(w); byId.set(w.id, w); });
                total = page.totalElements;
                filters = page.filters || null;
                nextPage = page.page + 1;
                if (page.items.length === 0) total = works.length;
                event = ['append', { count: fresh.length }];
            } catch (error) {
                if (!current()) return false;
                lastError = error;
                event = ['error', { error }];
            }
            // 先结束“载入中”再通知，界面据此显示的是载入完成后的状态
            loading = null;
            emit(...event);
            return event[0] === 'append';
        })();
        // 请求已经登记为“载入中”之后再通知，订阅者此时读到的 isLoading() 为 true
        emit('loading');
        return loading;
    }

    // 换查询前的最后一道闸（由编辑器登记）：返回 false 时拒绝换查询，列表、页码、总数都不动，并发出 blocked
    let queryGuard = null;

    // 换查询：取消旧请求，清空结果、页码与总数，从第一页重新载入。条件没变且已经开始载入时什么都不做。
    function setQuery(next) {
        const q = normalizeQuery(next);
        if (started && sameQuery(q, query)) return loading || Promise.resolve(false);
        if (started && queryGuard && !queryGuard()) {
            emit('blocked');
            return Promise.resolve(false);
        }
        generation++;
        if (controller) controller.abort();
        controller = typeof AbortController === 'function' ? new AbortController() : null;
        query = q;
        works.length = 0;
        byId.clear();
        retained.clear();
        nextPage = 0;
        total = null;
        filters = null;
        loading = null;
        lastError = null;
        emit('reset');
        return loadMore();
    }

    /*
     * 编辑保存后按服务端结果重读已载入的各页（条件不变）：已在列表里的作品沿用原对象（封面尺寸、详情不丢），
     * 不再符合条件的移出结果、记入 retained；总数、筛选回带信息与页码一并更新，之后的“载入更多”接着往下翻，不会重复。
     * 失败时保持原列表不动并返回错误；查询在此期间换了，结果作废。
     */
    function refreshLoaded() {
        const pages = Math.max(1, nextPage);
        generation++;
        if (controller) controller.abort();
        controller = typeof AbortController === 'function' ? new AbortController() : null;
        const gen = generation;
        const current = () => gen === generation;
        const signal = controller ? controller.signal : undefined;
        lastError = null;
        const run = (async () => {
            try {
                const items = [];
                let last = null;
                for (let p = 0; p < pages; p++) {
                    last = await getJson(listUrl(query, p), signal);
                    if (!current()) return { stale: true };
                    items.push(...last.items);
                    if (last.items.length < PAGE_SIZE) break;
                }
                const seen = new Set();
                const next = [];
                items.forEach((item) => {
                    if (seen.has(item.id)) return;
                    seen.add(item.id);
                    const old = byId.get(item.id) || retained.get(item.id);
                    if (old) {
                        old.title = item.title || '';
                        old.author = item.author || null;
                        next.push(old);
                    } else next.push(toWork(item));
                });
                const covers = next.map((w) => w.pages[0]).filter((p) => !p.width);
                covers.forEach(reuseMeasured);
                await measureAll(covers, current);
                if (!current()) return { stale: true };
                covers.forEach(rememberMeasured);
                const removed = works.map((w, index) => ({ id: w.id, index })).filter((r) => !seen.has(r.id));
                removed.forEach((r) => retained.set(r.id, byId.get(r.id)));
                works.length = 0;
                byId.clear();
                next.forEach((w) => { works.push(w); byId.set(w.id, w); retained.delete(w.id); });
                total = last.totalElements;
                filters = last.filters || null;
                nextPage = last.page + 1;
                loading = null;
                emit('refresh', { removed });
                return { removed };
            } catch (error) {
                if (!current()) return { stale: true };
                loading = null;
                return { error };
            }
        })();
        loading = run.then((r) => !r.error && !r.stale);
        return run;
    }

    /*
     * 作者 / 标签候选：按关键词向服务端搜索、往下翻页，能找到前 20 条之外的项。
     * 关键词换了，旧关键词迟到的结果直接丢弃；失败后 more() 重试同一页。
     */
    function facetSearch(kind) {
        const base = kind === 'author' ? '/api/authors' : '/api/tags';
        const s = { keyword: '', items: [], done: false, loading: false, error: null };
        let seq = 0;
        let offset = 0;

        async function fetchPage(mySeq) {
            s.loading = true;
            s.error = null;
            const params = new URLSearchParams({ limit: String(FACET_PAGE_SIZE), offset: String(offset) });
            if (s.keyword) params.set('keyword', s.keyword);
            try {
                const rows = await getJson(`${base}?${params}`);
                if (mySeq !== seq) return false;
                const known = new Set(s.items.map((x) => x.id));
                rows.forEach((row) => { if (!known.has(row.id)) s.items.push(row); });
                offset += rows.length;
                s.done = rows.length < FACET_PAGE_SIZE;
                return true;
            } catch (error) {
                if (mySeq !== seq) return false;
                s.error = error;
                return false;
            } finally {
                if (mySeq === seq) s.loading = false;
            }
        }

        return {
            state: s,
            search(keyword) {
                seq++;
                s.keyword = String(keyword || '').trim();
                s.items = [];
                s.done = false;
                offset = 0;
                return fetchPage(seq);
            },
            more() {
                if (s.loading || s.done) return Promise.resolve(false);
                return fetchPage(seq);
            }
        };
    }

    const getWork = (id) => byId.get(id) || retained.get(id) || null;

    /*
     * 详情：默认用缓存；force 时一定重读（打开编辑器、保存之后）。同一件作品同时有多次读取时只采用最后发出的那次，
     * 先发后到的旧结果不会覆盖新结果。详情里的标题、作者也同步到作品对象上，作品墙与看图器据此显示。
     */
    function loadDetail(id, { force = false } = {}) {
        const work = getWork(id);
        if (!work) return Promise.resolve(null);
        if (work.detail && !force) return Promise.resolve(work.detail);
        const pending = detailLoads.get(id);
        if (pending && !force) return pending.promise;
        const seq = ++detailSeq;
        const latest = () => detailLoads.get(id)?.seq === seq;
        // 被后发的读取取代：交给最新的那次（还在路上就等它，已完成就用它写下的结果）
        const superseded = () => {
            const newer = detailLoads.get(id);
            return newer ? newer.promise : (getWork(id) || work).detail || null;
        };
        work.detailError = null;
        const promise = (async () => {
            try {
                const detail = await getJson(`/api/illustrations/${id}`);
                if (!latest()) return superseded();
                const target = getWork(id) || work;
                target.title = detail.title || '';
                target.author = detail.author || null;
                target.detail = {
                    title: detail.title || '',
                    author: detail.author || null,
                    sourceUrl: detail.sourceUrl || '',
                    note: detail.note || '',
                    tags: detail.tags || [],
                    updatedAt: detail.updatedAt ? Date.parse(detail.updatedAt) : null
                };
                target.detailError = null;
                (detail.assets || []).forEach((asset) => {
                    const page = target.pages.find((p) => p.assetId === asset.id);
                    if (page) {
                        page.originalFilename = asset.originalFilename;
                        page.size = asset.fileSize;
                    }
                });
                emit('detail', { id });
                return target.detail;
            } catch (error) {
                if (!latest()) return superseded();
                (getWork(id) || work).detailError = error;
                emit('detail', { id });
                return null;
            } finally {
                if (latest()) detailLoads.delete(id);
            }
        })();
        detailLoads.set(id, { seq, promise });
        return promise;
    }

    /* ---------------- 单件编辑 ---------------- */

    const EDIT_TEXT_FIELDS = ['title', 'sourceUrl', 'note'];

    // 详情 → 可编辑的原值（编辑器的草稿从它复制）
    function editableOf(detail) {
        return {
            title: detail.title || '',
            authorId: detail.author ? detail.author.id : null,
            tagIds: (detail.tags || []).map((t) => t.id),
            sourceUrl: detail.sourceUrl || '',
            note: detail.note || ''
        };
    }

    /*
     * PATCH 请求体：只放改动过的字段。文本去掉首尾空白，空白即 null（与旧图库一致）；作者清空为 null；
     * 标签按集合比较（顺序无关），清空为 []。没有改动时得到空对象。
     */
    function buildPatch(original, draft) {
        const text = (v) => String(v == null ? '' : v).trim() || null;
        const tagKey = (ids) => [...new Set(ids)].sort((a, b) => a - b).join();
        const patch = {};
        EDIT_TEXT_FIELDS.forEach((k) => { if (text(draft[k]) !== text(original[k])) patch[k] = text(draft[k]); });
        const authorId = draft.authorId == null ? null : draft.authorId;
        if (authorId !== (original.authorId == null ? null : original.authorId)) patch.authorId = authorId;
        if (tagKey(draft.tagIds) !== tagKey(original.tagIds)) patch.tagIds = [...new Set(draft.tagIds)];
        return patch;
    }

    async function saveFailure(response) {
        let message = '';
        try {
            const body = await response.json();
            if (body && typeof body.message === 'string') message = body.message;
        } catch (e) { /* 没有可读的错误说明 */ }
        if (response.status === 403) return '本机访问会话已失效，请从控制窗口重新打开图库';
        if (response.status === 404) return '这件作品已不存在，可能已在旧图库中删除';
        if (response.status === 400) return '内容不符合要求' + (message ? '：' + message : '');
        return `服务没有保存这次修改（${response.status}）。所选作者或标签可能已在别处被删除，请刷新后重试`;
    }

    /*
     * 保存：PATCH 成功（204，无响应体，不解析）后强制重读详情；有搜索或筛选时再按服务端结果刷新已载入的列表。
     * 返回 { listed, detailError, refreshError }：listed 为 false 表示这件作品已不符合当前条件、移出了结果。
     * PATCH 失败时抛出带说明的错误，本地数据不变。
     */
    async function saveWork(id, patch) {
        let response;
        try {
            response = await fetch(`/api/illustrations/${id}`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify(patch)
            });
        } catch (e) {
            throw new RequestError(0, '无法连接到本地图库服务，修改没有保存');
        }
        if (!response.ok) throw new RequestError(response.status, await saveFailure(response));
        const filtered = !!(query.q || query.authorIds.length || query.tagIds.length);
        const result = { listed: true, detailError: null, refreshError: null };
        if (!(await loadDetail(id, { force: true }))) result.detailError = getWork(id) ? getWork(id).detailError : null;
        if (filtered) {
            const refreshed = await refreshLoaded();
            if (refreshed.error) result.refreshError = refreshed.error;
        }
        result.listed = byId.has(id);
        emit('updated', { id, listed: result.listed });
        return result;
    }

    /*
     * 一次编辑的状态：loading（读详情）→ ready（可编辑）→ saving → saved；读详情失败为 error。
     * 只有 ready 时能改草稿和保存，保存中再次提交直接忽略；保存失败回到 ready，草稿原样保留并记下原因。
     * 详情读完之前没有草稿，避免把没载入的字段当成空值发出去。
     */
    function editSession(id) {
        const s = { id, phase: 'loading', original: null, draft: null, error: null };
        const listeners = new Set();
        let disposed = false;
        const changed = () => { if (!disposed) listeners.forEach((fn) => fn(s)); };
        const patch = () => (s.original ? buildPatch(s.original, s.draft) : {});
        return {
            state: s,
            onChange(fn) { listeners.add(fn); },
            patch,
            dirty: () => Object.keys(patch()).length > 0,
            async load() {
                s.phase = 'loading';
                s.error = null;
                changed();
                const detail = await loadDetail(id, { force: true });
                if (disposed) return false;
                if (!detail) {
                    const work = getWork(id);
                    s.phase = 'error';
                    s.error = (work && work.detailError && work.detailError.message) || '作品信息载入失败';
                    changed();
                    return false;
                }
                s.original = editableOf(detail);
                s.draft = { ...s.original, tagIds: [...s.original.tagIds] };
                s.phase = 'ready';
                changed();
                return true;
            },
            set(key, value) {
                if (s.phase !== 'ready') return;
                s.draft[key] = key === 'tagIds' ? [...value] : value;
                changed();
            },
            async save() {
                if (s.phase !== 'ready') return null;
                const body = patch();
                if (!Object.keys(body).length) return { unchanged: true };
                s.phase = 'saving';
                s.error = null;
                changed();
                try {
                    const result = await saveWork(id, body);
                    s.phase = 'saved';
                    changed();
                    return result;
                } catch (error) {
                    s.phase = 'ready';
                    s.error = error.message;
                    changed();
                    return null;
                }
            },
            dispose() { disposed = true; listeners.clear(); }
        };
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
        get: getWork,
        listed: (id) => byId.has(id),
        total: () => total,
        query: () => ({ q: query.q, authorIds: [...query.authorIds], tagIds: [...query.tagIds] }),
        isFiltered: () => !!(query.q || query.authorIds.length || query.tagIds.length),
        filters: () => filters,
        generation: () => generation,
        setQuery,
        setQueryGuard: (fn) => { queryGuard = fn; },
        queryFromSearch,
        searchOf,
        facetSearch,
        editSession,
        refreshLoaded,
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
        _internal: { toWork, pageFor, formatOf, placeholder, listUrl, normalizeQuery, buildPatch, editableOf, saveWork }
    };
})();
