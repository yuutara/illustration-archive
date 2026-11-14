/*
 * C 回收站（仅桌面端）：
 * 展示移入回收站的作品、删除时间与预计自动清理时间（30 天），
 * 支持单件/批量恢复，以及单件/批量/全部清空永久删除（均有独立危险确认）。
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };

    const PAGE_SIZE = 24;
    const TOAST_MILLIS = 3200;

    const state = {
        page: 0,
        totalPages: 0,
        totalItems: 0,
        retentionDays: 30,
        items: [],
        selected: new Set(),
        busy: false,
        confirmMode: null, // { type: 'selected', ids: [...] } | { type: 'all' }
        requestSeq: 0
    };

    function toast(text) {
        const node = $('toast');
        if (!node) return;
        node.textContent = text;
        node.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { node.hidden = true; }, TOAST_MILLIS);
    }

    function formatDateTime(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return '—';
        return d.toLocaleString(undefined, {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit'
        });
    }

    function formatPurgeSummary(iso, nowMs = Date.now()) {
        if (!iso) return '—';
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return '—';
        const diffMs = d.getTime() - nowMs;
        const dateText = d.toLocaleDateString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit' });
        if (diffMs <= 0) return `${dateText}（即将清理）`;
        const days = Math.ceil(diffMs / 86400000);
        return `${dateText}（约 ${days} 天后）`;
    }

    function releaseVideos(root) {
        if (!root) return;
        root.querySelectorAll('video').forEach((video) => {
            video.pause();
            video.removeAttribute('src');
            video.load();
        });
    }

    function mediaNode(item) {
        if (item.coverAssetId == null) {
            return el('span', 'trash-media-empty', '无可用封面');
        }
        if (item.coverMimeType === 'video/mp4') {
            const video = document.createElement('video');
            video.src = `/api/trash/assets/${encodeURIComponent(item.coverAssetId)}/content`;
            video.muted = true;
            video.loop = true;
            video.autoplay = true;
            video.playsInline = true;
            video.preload = 'metadata';
            return video;
        }
        const img = document.createElement('img');
        img.src = `/api/trash/assets/${encodeURIComponent(item.coverAssetId)}/thumbnail`;
        img.alt = item.title || '回收站作品封面';
        img.loading = 'lazy';
        img.decoding = 'async';
        return img;
    }

    function syncSelection() {
        const grid = $('trash-grid');
        if (grid) {
            [...grid.children].forEach((card) => {
                const id = Number(card.dataset.id);
                const selected = state.selected.has(id);
                card.classList.toggle('is-selected', selected);
                const check = card.querySelector('.trash-check');
                if (check) {
                    check.setAttribute('aria-pressed', String(selected));
                    check.textContent = selected ? '✓' : '';
                }
            });
        }
        const selectedCount = state.selected.size;
        $('trash-bar').hidden = selectedCount === 0;
        $('trash-selected').textContent = `已选 ${selectedCount} 件`;

        const selectPage = $('trash-select-page');
        const emptyBtn = $('trash-empty-btn');
        selectPage.hidden = state.items.length === 0;
        emptyBtn.hidden = state.totalItems === 0;
        const allOnPage = state.items.length > 0 && state.items.every((item) => state.selected.has(item.id));
        selectPage.textContent = allOnPage ? '取消全选' : '全选本页';

        ['trash-select-page', 'trash-empty-btn', 'trash-restore-btn', 'trash-purge-btn', 'trash-clear-btn']
            .forEach((id) => { if ($(id)) $(id).disabled = state.busy; });
        if (grid) {
            grid.querySelectorAll('button').forEach((btn) => { btn.disabled = state.busy; });
        }
        $('trash-pager').hidden = state.totalPages <= 1;
        $('trash-page').textContent = `${state.page + 1} / ${Math.max(1, state.totalPages)}`;
        $('trash-prev').disabled = state.busy || state.page <= 0;
        $('trash-next').disabled = state.busy || state.page + 1 >= state.totalPages;
    }

    function toggleSelect(id) {
        if (state.busy) return;
        if (state.selected.has(id)) state.selected.delete(id);
        else state.selected.add(id);
        syncSelection();
    }

    function renderGrid() {
        const grid = $('trash-grid');
        releaseVideos(grid);
        grid.replaceChildren();
        const frag = document.createDocumentFragment();
        state.items.forEach((item) => {
            const card = el('article', 'trash-card');
            card.dataset.id = String(item.id);

            const media = el('div', 'trash-media');
            const check = el('button', 'trash-check');
            check.type = 'button';
            check.setAttribute('aria-label', `选择作品 ${item.title || item.id}`);
            check.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleSelect(item.id);
            });
            media.appendChild(check);
            media.appendChild(mediaNode(item));
            if (item.assetCount > 1) {
                media.appendChild(el('span', 'trash-badge', `${item.assetCount} 张`));
            }
            media.addEventListener('click', () => toggleSelect(item.id));
            card.appendChild(media);

            const body = el('div', 'trash-body');
            body.appendChild(el('h2', 'trash-title', item.title || '无标题'));
            body.appendChild(el('p', 'trash-author', item.author && item.author.name ? item.author.name : '未填作者'));

            const times = el('div', 'trash-times');
            times.appendChild(el('span', '', `删除于 ${formatDateTime(item.trashedAt)}`));
            times.appendChild(el('span', '', `自动清理 ${formatPurgeSummary(item.purgeAt)}`));
            body.appendChild(times);

            const actions = el('div', 'trash-actions');
            const restoreBtn = el('button', 'btn', '恢复');
            restoreBtn.type = 'button';
            restoreBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                restoreIds([item.id]);
            });
            const purgeBtn = el('button', 'btn btn-danger', '永久删除');
            purgeBtn.type = 'button';
            purgeBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                openPurgeConfirm({ type: 'selected', ids: [item.id] });
            });
            actions.append(restoreBtn, purgeBtn);
            body.appendChild(actions);

            card.appendChild(body);
            frag.appendChild(card);
        });
        grid.appendChild(frag);
        syncSelection();
    }

    async function readJson(response) {
        return response.json().catch(() => ({}));
    }

    async function load() {
        if (!(window.UiCapabilities && window.UiCapabilities.desktop)) {
            $('trash-loading').hidden = true;
            $('trash-error').hidden = true;
            $('trash-note').hidden = true;
            $('trash-empty').hidden = false;
            $('trash-empty-title').textContent = '回收站仅在桌面端提供';
            $('trash-empty-text').textContent = '当前不是桌面应用模式，请返回图库。';
            $('trash-back-link').hidden = false;
            syncSelection();
            return false;
        }

        const seq = ++state.requestSeq;
        $('trash-loading').hidden = state.items.length > 0;
        $('trash-error').hidden = true;
        $('trash-empty').hidden = true;
        $('trash-back-link').hidden = true;

        try {
            const response = await fetch(`/api/trash?page=${state.page}&size=${PAGE_SIZE}`, {
                headers: { Accept: 'application/json' },
                cache: 'no-store'
            });
            if (seq !== state.requestSeq) return false;
            const data = await readJson(response);
            if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);

            state.items = Array.isArray(data.items) ? data.items : [];
            state.totalItems = data.totalItems || 0;
            state.totalPages = data.totalPages || 0;
            state.retentionDays = data.retentionDays || 30;
            $('trash-note').textContent = `移入回收站的作品会暂存在这里；${state.retentionDays} 天后自动永久清理，清理前可以随时恢复原样。`;
            $('trash-count').textContent = state.totalItems > 0 ? `${state.totalItems} 件` : '';

            if (state.page > 0 && state.items.length === 0 && state.totalPages > 0) {
                state.page = Math.max(0, state.totalPages - 1);
                return load();
            }

            const visibleIds = new Set(state.items.map((item) => item.id));
            [...state.selected].forEach((id) => { if (!visibleIds.has(id)) state.selected.delete(id); });

            $('trash-loading').hidden = true;
            renderGrid();
            if (state.items.length === 0) {
                $('trash-empty').hidden = false;
                $('trash-empty-title').textContent = '回收站是空的';
                $('trash-empty-text').textContent = '从图库或看图器移入回收站的作品会出现在这里。';
            }
            return true;
        } catch (error) {
            if (seq !== state.requestSeq) return false;
            $('trash-loading').hidden = true;
            releaseVideos($('trash-grid'));
            $('trash-grid').replaceChildren();
            $('trash-error').hidden = false;
            $('trash-error-text').textContent = `没能读取回收站：${error.message}`;
            syncSelection();
            return false;
        }
    }

    async function restoreIds(ids) {
        if (state.busy || !ids.length) return;
        state.busy = true;
        syncSelection();
        try {
            const response = await fetch('/api/trash/restore', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify({ ids })
            });
            const result = await readJson(response);
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            ids.forEach((id) => state.selected.delete(id));
            state.busy = false;
            await load();
            if (window.AppShell && typeof window.AppShell.refreshTrash === 'function') {
                window.AppShell.refreshTrash();
            }
            const restoredCount = result.restoredCount ?? ids.length;
            toast(`已恢复 ${restoredCount} 件作品，可在图库查看`);
        } catch (error) {
            state.busy = false;
            syncSelection();
            toast(`恢复失败：${error.message}`);
        }
    }

    function closePurgeConfirm() {
        if (state.busy) return;
        state.confirmMode = null;
        $('trash-confirm').hidden = true;
        $('trash-confirm-error').hidden = true;
        $('trash-confirm-error').textContent = '';
    }

    function openPurgeConfirm(mode) {
        if (state.busy) return;
        state.confirmMode = mode;
        $('trash-confirm-error').hidden = true;
        $('trash-confirm-error').textContent = '';
        if (mode.type === 'all') {
            $('trash-confirm-title').textContent = `清空回收站里的全部 ${state.totalItems} 件作品？`;
            $('trash-confirm-text').textContent = '清空会立即彻底删除回收站里的全部作品、数据库记录、原图文件和缩略图，无法再恢复。';
            $('trash-confirm-ok').textContent = '清空回收站';
        } else {
            const count = mode.ids.length;
            $('trash-confirm-title').textContent = `永久删除这 ${count} 件作品？`;
            $('trash-confirm-text').textContent = '永久删除后会立即移除数据库记录、原图文件和缩略图，无法再恢复。';
            $('trash-confirm-ok').textContent = '永久删除';
        }
        $('trash-confirm').hidden = false;
        $('trash-confirm-cancel').focus();
    }

    async function executePurgeConfirm() {
        const mode = state.confirmMode;
        if (!mode || state.busy) return;
        state.busy = true;
        syncSelection();
        $('trash-confirm-cancel').disabled = true;
        $('trash-confirm-ok').disabled = true;
        $('trash-confirm-error').hidden = true;

        try {
            const url = mode.type === 'all' ? '/api/trash/purge-all' : '/api/trash/purge';
            const init = {
                method: 'POST',
                headers: { Accept: 'application/json' }
            };
            if (mode.type !== 'all') {
                init.headers['Content-Type'] = 'application/json';
                init.body = JSON.stringify({ ids: mode.ids });
            }
            const response = await fetch(url, init);
            const result = await readJson(response);
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);

            if (mode.type === 'all') state.selected.clear();
            else mode.ids.forEach((id) => state.selected.delete(id));

            state.busy = false;
            $('trash-confirm-cancel').disabled = false;
            $('trash-confirm-ok').disabled = false;
            closePurgeConfirm();
            await load();
            if (window.AppShell && typeof window.AppShell.refreshTrash === 'function') {
                window.AppShell.refreshTrash();
            }
            const purgedCount = result.purgedCount ?? 0;
            const pending = result.pendingFileCleanups || 0;
            toast(pending > 0
                ? `已永久删除 ${purgedCount} 件作品（${pending} 个文件稍后自动完成清理）`
                : `已永久删除 ${purgedCount} 件作品`);
        } catch (error) {
            state.busy = false;
            $('trash-confirm-cancel').disabled = false;
            $('trash-confirm-ok').disabled = false;
            $('trash-confirm-error').hidden = false;
            $('trash-confirm-error').textContent = `清理没有完成：${error.message}`;
            syncSelection();
        }
    }

    function bindEvents() {
        $('trash-select-page').addEventListener('click', () => {
            if (state.busy || !state.items.length) return;
            const allOnPage = state.items.every((item) => state.selected.has(item.id));
            state.items.forEach((item) => {
                if (allOnPage) state.selected.delete(item.id);
                else state.selected.add(item.id);
            });
            syncSelection();
        });
        $('trash-clear-btn').addEventListener('click', () => {
            state.selected.clear();
            syncSelection();
        });
        $('trash-restore-btn').addEventListener('click', () => {
            restoreIds([...state.selected]);
        });
        $('trash-purge-btn').addEventListener('click', () => {
            if (!state.selected.size) return;
            openPurgeConfirm({ type: 'selected', ids: [...state.selected] });
        });
        $('trash-empty-btn').addEventListener('click', () => {
            if (!state.totalItems) return;
            openPurgeConfirm({ type: 'all' });
        });
        $('trash-retry').addEventListener('click', load);
        $('trash-prev').addEventListener('click', () => {
            if (state.page > 0 && !state.busy) {
                state.page -= 1;
                load();
            }
        });
        $('trash-next').addEventListener('click', () => {
            if (state.page + 1 < state.totalPages && !state.busy) {
                state.page += 1;
                load();
            }
        });
        $('trash-confirm-cancel').addEventListener('click', closePurgeConfirm);
        $('trash-confirm-ok').addEventListener('click', executePurgeConfirm);
        $('trash-confirm').addEventListener('mousedown', (e) => {
            if (e.target === $('trash-confirm')) closePurgeConfirm();
        });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && !$('trash-confirm').hidden) {
                e.preventDefault();
                closePurgeConfirm();
            }
        });
        window.addEventListener('pagehide', () => releaseVideos($('trash-grid')));
    }

    window.CTrash = {
        state,
        formatDateTime,
        formatPurgeSummary,
        load,
        restoreIds,
        openPurgeConfirm,
        executePurgeConfirm
    };

    bindEvents();
    load();
})();
