/*
 * X 收藏：把同步来的点赞过一遍。选中多件批量处理，或打开预览逐件按 A 归档、S 跳过。
 * 同步是付费的，只在用户点按钮时发生；第一次会先说明费用。
 */
(function () {
    'use strict';

    const M = window.XInboxModel;
    const $ = (id) => document.getElementById(id);
    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };

    const PAGE_SIZE = 40;
    const COST_NOTICE_KEY = 'ia-x-sync-cost-seen';

    const state = {
        page: 0, totalPages: 0, total: 0, items: [],
        selected: new Set(), problems: new Map(),
        connected: false, scanBudget: 100, busy: false,
        preview: null // { index, media }
    };

    function toast(text) {
        const t = $('toast');
        t.textContent = text;
        t.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { t.hidden = true; }, 3200);
    }

    const postUrl = (item) => `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`;
    // 列表卡片只有 200–330 px 宽，用 680 的图就够清楚；预览要看细节，用 2048
    const LIST_SIZE = 'small';
    const PREVIEW_SIZE = 'large';
    const mediaUrl = (item, media, size) => media.mediaType === 'animated_gif'
        ? `/api/x-import/inbox/${encodeURIComponent(item.id)}/media/${encodeURIComponent(media.mediaKey)}/content`
        : M.photoVariant(media.sourceUrl, size);
    const sorted = (item) => [...(item.media || [])].sort((a, b) => a.sortOrder - b.sortOrder);

    // <img> 拿不到状态码，这里不判断原因，也不说图片没了：可能只是这次没取到
    function showUnavailable(item, image) {
        const notice = el('div', 'x-unavailable');
        notice.appendChild(el('span', '', '暂时读不到这张图'));
        const retry = el('button', 'x-retry', '重试');
        retry.type = 'button';
        retry.addEventListener('click', (e) => {
            e.stopPropagation();
            notice.remove();
            const src = image.src;
            image.removeAttribute('src');
            image.src = src;
        });
        notice.appendChild(retry);
        const post = el('a', 'link', '打开原帖');
        post.href = postUrl(item);
        post.target = '_blank';
        post.rel = 'noreferrer';
        post.dataset.external = '';
        notice.appendChild(post);
        image.after(notice);
    }

    function mediaElement(item, media, { controls = false } = {}) {
        if (media.mediaType === 'animated_gif') {
            const video = document.createElement('video');
            video.src = mediaUrl(item, media);
            video.muted = true;
            video.loop = true;
            video.playsInline = true;
            video.preload = 'metadata';
            if (controls) { video.controls = true; video.autoplay = true; }
            return video;
        }
        const image = document.createElement('img');
        image.alt = '';
        image.decoding = 'async';
        image.loading = controls ? 'eager' : 'lazy';
        image.draggable = false;
        image.addEventListener('error', () => showUnavailable(item, image));
        image.src = mediaUrl(item, media, controls ? PREVIEW_SIZE : LIST_SIZE);
        return image;
    }

    /* ---------------- 列表 ---------------- */

    const indexOfItem = (id) => state.items.findIndex((item) => item.id === id);

    function syncProblem(card, id) {
        const text = state.problems.get(id);
        let line = card.querySelector('.x-problem');
        if (!text) { if (line) line.remove(); return; }
        if (!line) line = card.appendChild(el('p', 'x-problem'));
        line.textContent = text;
    }

    function renderCard(item) {
        const card = el('article', 'x-card');
        card.dataset.id = item.id;
        card.tabIndex = 0;
        card.setAttribute('role', 'checkbox');
        const media = sorted(item);
        const frame = el('div', 'x-frame');
        if (media[0]) {
            if (media[0].width && media[0].height) frame.style.aspectRatio = `${media[0].width} / ${media[0].height}`;
            frame.appendChild(mediaElement(item, media[0]));
        }
        const marks = el('div', 'piece-marks');
        if (media.length > 1) marks.appendChild(el('span', 'mark', `${media.length} 张`));
        if (media.some((m) => m.mediaType === 'animated_gif')) marks.appendChild(el('span', 'mark', '动图'));
        frame.appendChild(marks);
        frame.appendChild(el('span', 'x-check'));
        const open = el('button', 'x-open', '预览');
        open.type = 'button';
        open.addEventListener('click', (e) => { e.stopPropagation(); openPreview(indexOfItem(item.id)); });
        frame.appendChild(open);
        card.appendChild(frame);
        const meta = el('div', 'x-meta');
        meta.appendChild(el('p', 'x-author', item.authorDisplayName || item.authorUsername));
        if (item.postText) meta.appendChild(el('p', 'x-text', item.postText));
        card.appendChild(meta);
        card.addEventListener('click', (e) => { if (!e.target.closest('a')) toggle(item.id); });
        card.addEventListener('dblclick', () => openPreview(indexOfItem(item.id)));
        card.addEventListener('keydown', (e) => {
            if (e.target !== card) return;
            if (e.key === ' ') { e.preventDefault(); toggle(item.id); }
            if (e.key === 'Enter') { e.preventDefault(); openPreview(indexOfItem(item.id)); }
        });
        return card;
    }

    function syncSelection() {
        document.querySelectorAll('.x-card').forEach((card) => {
            const on = state.selected.has(Number(card.dataset.id));
            card.classList.toggle('is-selected', on);
            card.setAttribute('aria-checked', String(on));
        });
        const count = state.selected.size;
        $('x-bar').hidden = count === 0;
        $('x-selected').textContent = `已选 ${count} 件`;
        ['x-archive', 'x-skip', 'x-clear'].forEach((id) => { $(id).disabled = state.busy; });
        const all = state.items.length > 0 && state.items.every((item) => state.selected.has(item.id));
        $('x-select-page').textContent = all ? '取消全选' : '全选本页';
    }

    function toggle(id) {
        if (state.busy) return;
        if (state.selected.has(id)) state.selected.delete(id);
        else state.selected.add(id);
        syncSelection();
    }

    // 留下还在列表里的卡片：逐件整理时只少一张，其余的图片不必重新加载和解码
    function renderList() {
        const grid = $('x-grid');
        const kept = new Map();
        [...grid.children].forEach((card) => {
            const id = Number(card.dataset.id);
            if (state.items.some((item) => item.id === id)) kept.set(id, card);
            else card.remove();
        });
        state.items.forEach((item, index) => {
            const card = kept.get(item.id) || renderCard(item);
            syncProblem(card, item.id);
            if (grid.children[index] !== card) grid.insertBefore(card, grid.children[index] || null);
        });
        $('x-loading').hidden = true;
        $('x-count').textContent = state.total ? `${state.total} 件待整理` : '';
        $('x-empty').hidden = state.total > 0;
        $('x-select-page').hidden = state.items.length === 0;
        $('x-pager').hidden = state.totalPages <= 1;
        $('x-page').textContent = `${state.page + 1} / ${state.totalPages}`;
        $('x-prev').disabled = state.page === 0;
        $('x-next').disabled = state.page + 1 >= state.totalPages;
        syncSelection();
    }

    async function load(page = state.page) {
        try {
            let target = page;
            let data;
            for (;;) {
                const response = await fetch(`/api/x-import/inbox?page=${target}&size=${PAGE_SIZE}`, { headers: { Accept: 'application/json' } });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                data = await response.json();
                if (target === 0 || target < data.totalPages) break;
                target = Math.max(0, data.totalPages - 1); // 这一页被处理空了，退回最后一页
            }
            state.page = target;
            state.items = data.items;
            state.total = data.totalItems;
            state.totalPages = data.totalPages;
            const present = new Set(data.items.map((item) => item.id));
            state.selected.forEach((id) => { if (!present.has(id)) state.selected.delete(id); });
            renderList();
            if (window.AppShell) window.AppShell.refreshInbox();
            return true;
        } catch (e) {
            $('x-loading').hidden = false;
            $('x-loading').textContent = `收件箱载入失败：${e.message}`;
            return false;
        }
    }

    /* ---------------- 连接状态与同步 ---------------- */

    function showResult(head, detail, { problem = false, settings = false } = {}) {
        $('x-result').hidden = false;
        $('x-result').classList.toggle('is-problem', problem);
        $('x-result-head').textContent = head;
        $('x-result-detail').textContent = detail;
        const actions = $('x-result-actions');
        actions.replaceChildren();
        if (settings) {
            const link = el('a', 'link', '打开设置');
            link.href = '/c/settings.html#x';
            actions.appendChild(link);
        }
    }

    async function loadConnection() {
        try {
            const response = await fetch('/api/x-auth/status', { headers: { Accept: 'application/json' } });
            if (!response.ok) throw new Error();
            const connection = await response.json();
            state.connected = connection.status.state === 'CONNECTED';
            state.scanBudget = connection.scanBudget;
        } catch (e) {
            state.connected = false;
        }
        $('x-connect').hidden = state.connected;
        $('x-sync').hidden = !state.connected;
        $('x-empty-text').textContent = state.connected ? '点“同步”取回你在 X 上新点赞的作品。' : '连接 X 账号后，待整理的收藏会出现在这里。';
    }

    function costNoticeSeen() {
        try { return localStorage.getItem(COST_NOTICE_KEY) === '1'; } catch (e) { return false; }
    }

    function confirmCost() {
        if (costNoticeSeen()) return Promise.resolve(true);
        $('x-confirm-text').textContent = `每次同步最多读取 ${state.scanBudget} 条点赞，按 X 官方价目约 ${M.costEstimate(state.scanBudget)}，从你的 X 开发者额度中扣除。`
            + '只有点“同步”时才会读取，不会在后台自动进行。实际费用以 X 开发者控制台为准。';
        const sheet = $('x-confirm');
        sheet.hidden = false;
        $('x-confirm-cancel').focus();
        return new Promise((resolve) => {
            const done = (ok) => {
                sheet.hidden = true;
                $('x-confirm-ok').onclick = null;
                $('x-confirm-cancel').onclick = null;
                if (ok) { try { localStorage.setItem(COST_NOTICE_KEY, '1'); } catch (e) { /* 下次再提示一次 */ } }
                resolve(ok);
            };
            $('x-confirm-ok').onclick = () => done(true);
            $('x-confirm-cancel').onclick = () => done(false);
        });
    }

    async function sync() {
        if (state.busy || !(await confirmCost())) return;
        state.busy = true;
        const button = $('x-sync');
        button.disabled = true;
        button.textContent = '正在同步…';
        syncSelection();
        try {
            const response = await fetch('/api/x-import/sync', { method: 'POST', headers: { Accept: 'application/json' } });
            const body = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
            const summary = M.syncSummary(body);
            showResult(summary.problem || summary.headline, summary.problem ? summary.headline + '。' + summary.detail : summary.detail,
                { problem: !!summary.problem, settings: !!summary.problem && /设置/.test(summary.problem) });
            await load(0);
        } catch (e) {
            showResult('同步没有完成', `${e.message}。已保存的进度不受影响。`, { problem: true });
        } finally {
            state.busy = false;
            button.disabled = false;
            button.textContent = '同步';
            syncSelection();
        }
    }

    /* ---------------- 归档与跳过 ---------------- */

    async function postArchive(id) {
        const response = await fetch('/api/x-import/inbox/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ itemIds: [id] })
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
    }

    async function archive(ids) {
        if (state.busy || !ids.length) return null;
        state.busy = true;
        syncSelection();
        const outcome = await M.archiveAll(ids, postArchive, (done, total) => {
            $('x-selected').textContent = `正在归档 ${done + 1} / ${total}`;
        });
        outcome.problems.forEach((text, id) => state.problems.set(id, text));
        outcome.archived.forEach((id) => { state.problems.delete(id); state.selected.delete(id); });
        state.busy = false;
        await load();
        toast(M.archiveSummary(outcome));
        return outcome;
    }

    async function skip(ids) {
        if (state.busy || !ids.length) return false;
        state.busy = true;
        syncSelection();
        let done = false;
        try {
            const response = await fetch('/api/x-import/inbox/skip', {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({ itemIds: ids })
            });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const result = await response.json();
            ids.forEach((id) => { state.selected.delete(id); state.problems.delete(id); });
            toast(`已跳过 ${result.skippedCount} 件`);
            done = true;
        } catch (e) {
            toast('没有确认跳过结果，请看刷新后的列表');
        }
        state.busy = false;
        await load();
        return done;
    }

    /* ---------------- 预览 ---------------- */

    function renderPreview() {
        const preview = state.preview;
        const item = state.items[preview.index];
        if (!item) { closePreview(); return; }
        const media = sorted(item);
        preview.media = Math.min(preview.media, Math.max(0, media.length - 1));
        const stage = $('x-stage');
        stage.replaceChildren();
        if (media[preview.media]) stage.appendChild(mediaElement(item, media[preview.media], { controls: true }));
        if (media.length > 1) stage.appendChild(el('span', 'x-stage-count', `${preview.media + 1} / ${media.length}`));
        $('x-side-author').textContent = item.authorDisplayName || item.authorUsername;
        $('x-side-handle').textContent = '@' + item.authorUsername;
        $('x-side-text').textContent = item.postText || '';
        const created = item.postCreatedAt ? new Date(item.postCreatedAt).toLocaleDateString() : '';
        $('x-side-meta').textContent = `${created}${created ? ' · ' : ''}第 ${state.page * PAGE_SIZE + preview.index + 1} / ${state.total} 件`;
        const problem = state.problems.get(item.id);
        $('x-side-problem').hidden = !problem;
        $('x-side-problem').textContent = problem || '';
        $('x-side-open').href = postUrl(item);
        preloadNext(preview.index);
    }

    // 逐件整理时下一张往往马上要看，提前取好就不用等
    function preloadNext(index) {
        const next = state.items[index + 1];
        const media = next && sorted(next)[0];
        if (media && media.mediaType === 'photo') new Image().src = mediaUrl(next, media, PREVIEW_SIZE);
    }

    function openPreview(index) {
        if (index < 0) return;
        state.preview = { index, media: 0 };
        $('x-preview').hidden = false;
        document.body.classList.add('is-locked');
        $('x-preview').focus({ preventScroll: true });
        renderPreview();
    }

    function closePreview() {
        state.preview = null;
        $('x-stage').replaceChildren();
        $('x-preview').hidden = true;
        document.body.classList.remove('is-locked');
    }

    // 归档或跳过后列表已刷新，同一序号上就是下一件；这一件还在，说明没处理成功，留下来显示原因
    async function decide(action) {
        const preview = state.preview;
        if (!preview || state.busy) return;
        const item = state.items[preview.index];
        if (!item) return;
        await action([item.id]);
        if (!state.preview) return;
        if (!state.items.length) { closePreview(); return; }
        preview.index = Math.min(preview.index, state.items.length - 1);
        preview.media = 0;
        renderPreview();
    }

    function movePreview(step) {
        const preview = state.preview;
        const next = preview.index + step;
        if (next < 0 || next >= state.items.length) return;
        preview.index = next;
        preview.media = 0;
        renderPreview();
    }

    function moveMedia(step) {
        const preview = state.preview;
        const count = sorted(state.items[preview.index]).length;
        const next = preview.media + step;
        if (next < 0 || next >= count) { movePreview(step); return; }
        preview.media = next;
        renderPreview();
    }

    /* ---------------- 事件 ---------------- */

    document.addEventListener('keydown', (e) => {
        if (!$('x-confirm').hidden) {
            if (e.key === 'Escape') $('x-confirm-cancel').click();
            return;
        }
        if (state.preview) {
            if (e.key === 'Escape') closePreview();
            else if (e.key === 'ArrowRight') moveMedia(1);
            else if (e.key === 'ArrowLeft') moveMedia(-1);
            else if (e.key === 'ArrowDown') movePreview(1);
            else if (e.key === 'ArrowUp') movePreview(-1);
            else if (e.key.toLowerCase() === 'a') decide(archive);
            else if (e.key.toLowerCase() === 's') decide(skip);
            else return;
            e.preventDefault();
            return;
        }
        if (e.target instanceof Element && e.target.closest('input, textarea')) return;
        if (state.selected.size && e.key.toLowerCase() === 'a' && !e.ctrlKey) archive([...state.selected]);
        else if (state.selected.size && e.key.toLowerCase() === 's' && !e.ctrlKey) skip([...state.selected]);
        else if (e.key === 'Escape' && state.selected.size) { state.selected.clear(); syncSelection(); }
    });

    $('x-sync').addEventListener('click', sync);
    $('x-archive').addEventListener('click', () => archive([...state.selected]));
    $('x-skip').addEventListener('click', () => skip([...state.selected]));
    $('x-clear').addEventListener('click', () => { state.selected.clear(); syncSelection(); });
    $('x-select-page').addEventListener('click', () => {
        const all = state.items.every((item) => state.selected.has(item.id));
        state.items.forEach((item) => (all ? state.selected.delete(item.id) : state.selected.add(item.id)));
        syncSelection();
    });
    $('x-prev').addEventListener('click', () => load(state.page - 1).then(() => window.scrollTo(0, 0)));
    $('x-next').addEventListener('click', () => load(state.page + 1).then(() => window.scrollTo(0, 0)));
    $('x-side-archive').addEventListener('click', () => decide(archive));
    $('x-side-skip').addEventListener('click', () => decide(skip));
    $('x-side-close').addEventListener('click', closePreview);
    $('x-stage').addEventListener('click', (e) => { if (e.target === $('x-stage')) closePreview(); });

    function syncTheme() {
        const dark = window.CTheme.get() === 'dark';
        const button = $('theme-toggle');
        button.setAttribute('aria-pressed', String(dark));
        button.querySelector('use').setAttribute('href', dark ? '#i-sun' : '#i-moon');
    }
    $('theme-toggle').addEventListener('click', () => {
        window.CTheme.set(window.CTheme.get() === 'dark' ? 'light' : 'dark');
        syncTheme();
    });
    syncTheme();

    loadConnection();
    load(0);
})();
