/* 手机端 X 收藏整理：复用待整理/最近跳过/撤销/归档并跳过其余逻辑，全部图片走本机同源代取。 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const M = window.XInboxModel;
    const PAGE_SIZE = 24;
    const SAVE_HINT_KEY = 'ia.mobile.x.saveHintDismissed';

    const state = {
        view: 'pending',
        page: 0,
        totalPages: 0,
        totalItems: 0,
        items: [],
        selected: new Set(),
        problems: new Map(),
        busy: false,
        disabled: false,
        preview: null, // { item, media, anchorIndex }
        undo: null,
        undoTimer: 0,
        requestId: 0
    };

    const isSkippedView = () => state.view === 'skipped';
    const sorted = (item) => [...(item.media || [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
    const postUrl = (item) => `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`;

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    function mediaProxyUrl(itemId, mediaKey, size, retry) {
        let url = `/api/x-import/inbox/${encodeURIComponent(itemId)}/media/${encodeURIComponent(mediaKey)}/content`;
        const params = [];
        if (size) params.push('size=' + encodeURIComponent(size));
        if (retry) params.push('retry=' + encodeURIComponent(retry));
        return params.length ? url + '?' + params.join('&') : url;
    }

    function releaseVideos(root) {
        if (!root || typeof root.querySelectorAll !== 'function') return;
        root.querySelectorAll('video').forEach((video) => {
            video.pause();
            video.removeAttribute('src');
            if (typeof video.load === 'function') video.load();
        });
    }

    function toPairing() {
        location.replace('/m/pair.html?e=3');
    }

    async function readForbidden(response) {
        const body = await response.json().catch(() => ({}));
        if (body && body.code === 'MOBILE_X_DISABLED') {
            showDisabledState();
            return 'MOBILE_X_DISABLED';
        }
        toPairing();
        return 'MOBILE_FORBIDDEN';
    }

    function showDisabledState() {
        state.disabled = true;
        state.busy = false;
        closePreview();
        clearStage();
        releaseVideos($('mx-grid'));
        $('mx-grid').replaceChildren();
        state.items = [];
        state.selected.clear();
        $('mx-loading').hidden = true;
        $('mx-empty').hidden = true;
        $('mx-status').hidden = true;
        $('mx-pager').hidden = true;
        $('mx-toolbar').hidden = true;
        $('mx-bar').hidden = true;
        $('mx-count').textContent = '';
        $('mx-disabled').hidden = false;
    }

    function clearDisabledState() {
        state.disabled = false;
        $('mx-disabled').hidden = true;
        $('mx-toolbar').hidden = false;
    }

    function toast(text, undo) {
        clearTimeout(state.undoTimer);
        state.undo = undo || null;
        const bar = $('mx-toast');
        bar.replaceChildren();
        if (!text) {
            bar.hidden = true;
            return;
        }
        bar.appendChild(el('span', null, text));
        if (undo) {
            const btn = el('button', null, undo.label || '撤销');
            btn.type = 'button';
            btn.addEventListener('click', () => undoSkip());
            bar.appendChild(btn);
        }
        bar.hidden = false;
        state.undoTimer = setTimeout(() => {
            state.undo = null;
            bar.hidden = true;
        }, undo ? 10000 : 3400);
    }

    let confirmResolver = null;
    function confirmSheet(title, text, okLabel) {
        if (confirmResolver) confirmResolver(false);
        $('mx-confirm-title').textContent = title;
        $('mx-confirm-text').textContent = text;
        $('mx-confirm-ok').textContent = okLabel || '确认';
        $('mx-confirm').hidden = false;
        return new Promise((resolve) => { confirmResolver = resolve; });
    }

    function closeConfirm(accepted) {
        $('mx-confirm').hidden = true;
        const resolve = confirmResolver;
        confirmResolver = null;
        if (resolve) resolve(accepted);
    }

    function renderMediaError(container, item, media, size, controls) {
        const box = el('div', controls ? 'mx-img-fail mx-img-fail-box' : 'mx-img-fail');
        box.appendChild(el('span', null, '图片暂时拉不到'));
        const retry = el('button', 'm-btn mx-retry-mini', '重试');
        retry.type = 'button';
        retry.addEventListener('click', (event) => {
            event.stopPropagation();
            const nextRetry = String(Date.now());
            container.replaceChildren(buildMediaNode(item, media, { size, controls, retry: nextRetry, host: container }));
        });
        box.appendChild(retry);
        container.replaceChildren(box);
    }

    function buildMediaNode(item, media, { size, controls, retry = '' } = {}) {
        if (media.mediaType === 'animated_gif') {
            const video = document.createElement('video');
            video.src = mediaProxyUrl(item.id, media.mediaKey, '', retry);
            video.autoplay = true;
            video.loop = true;
            video.muted = true;
            video.playsInline = true;
            video.preload = controls ? 'auto' : 'metadata';
            if (controls) video.controls = true;
            video.addEventListener('error', () => {
                const parent = video.parentElement;
                if (parent) renderMediaError(parent, item, media, '', controls);
            });
            return video;
        }
        const img = document.createElement('img');
        img.alt = '';
        if (!controls) {
            img.loading = 'lazy';
            img.decoding = 'async';
        }
        img.src = mediaProxyUrl(item.id, media.mediaKey, size, retry);
        img.addEventListener('load', () => img.classList.add('is-loaded'));
        if (img.complete) img.classList.add('is-loaded');
        img.addEventListener('error', () => {
            const parent = img.parentElement;
            if (parent) renderMediaError(parent, item, media, size, controls);
        });
        return img;
    }

    function renderGrid() {
        const grid = $('mx-grid');
        releaseVideos(grid);
        grid.replaceChildren();
        const fragment = document.createDocumentFragment();
        state.items.forEach((item) => {
            const card = el('article', 'mx-card' + (state.selected.has(item.id) ? ' is-selected' : ''));
            card.dataset.id = String(item.id);

            const check = el('button', 'mx-check');
            check.type = 'button';
            check.setAttribute('aria-pressed', String(state.selected.has(item.id)));
            check.setAttribute('aria-label', '选择 @' + item.authorUsername);
            check.appendChild(el('span', null, state.selected.has(item.id) ? '✓' : ''));
            check.addEventListener('click', (event) => {
                event.stopPropagation();
                toggleSelect(item.id);
            });
            card.appendChild(check);

            const openBtn = el('button', 'mx-card-media-btn');
            openBtn.type = 'button';
            openBtn.setAttribute('aria-label', '预览 @' + item.authorUsername + ' 的收藏');
            const mediaBox = el('span', 'm-card-media');
            const list = sorted(item);
            if (list[0]) {
                mediaBox.appendChild(buildMediaNode(item, list[0], { size: 'small', controls: false }));
            } else {
                mediaBox.appendChild(el('span', 'm-video-placeholder', '没有图片'));
            }
            if (list.length > 1) {
                mediaBox.appendChild(el('span', 'm-badge mx-card-badge-left', list.length + ' 张'));
            } else if (list[0] && list[0].mediaType === 'animated_gif') {
                mediaBox.appendChild(el('span', 'm-badge mx-card-badge-left', 'GIF'));
            }
            openBtn.appendChild(mediaBox);

            const meta = el('span', 'm-card-meta');
            meta.appendChild(el('span', 'm-card-title', item.authorDisplayName || item.authorUsername));
            meta.appendChild(el('span', 'm-card-author', '@' + item.authorUsername));
            const problem = state.problems.get(item.id);
            if (problem) meta.appendChild(el('span', 'mx-problem', problem));
            openBtn.appendChild(meta);
            openBtn.addEventListener('click', () => openPreview(item));
            card.appendChild(openBtn);
            fragment.appendChild(card);
        });
        grid.appendChild(fragment);
    }

    function toggleSelect(id) {
        if (state.busy) return;
        if (state.selected.has(id)) state.selected.delete(id);
        else state.selected.add(id);
        syncSelection();
    }

    function syncSelection() {
        const grid = $('mx-grid');
        [...grid.children].forEach((card) => {
            const id = Number(card.dataset.id);
            const selected = state.selected.has(id);
            card.classList.toggle('is-selected', selected);
            const btn = card.querySelector('.mx-check');
            if (btn) {
                btn.setAttribute('aria-pressed', String(selected));
                if (btn.firstChild) btn.firstChild.textContent = selected ? '✓' : '';
            }
        });

        const count = state.selected.size;
        $('mx-bar').hidden = state.disabled || (count === 0 && !state.busy);
        if (!state.busy) $('mx-selected').textContent = '已选 ' + count + ' 件';

        const skipped = isSkippedView();
        $('mx-skip').hidden = skipped;
        $('mx-archive').hidden = skipped;
        $('mx-restore').hidden = !skipped;

        const onPageIds = state.items.map((item) => item.id);
        const onPageSelected = onPageIds.filter((id) => state.selected.has(id)).length;
        const unselectedRest = state.items.length - onPageSelected;
        const restBtn = $('mx-archive-rest');
        restBtn.hidden = skipped || count === 0 || onPageSelected === 0 || unselectedRest <= 0;
        if (!restBtn.hidden) {
            restBtn.textContent = `归档并跳过其余 ${unselectedRest}`;
        }

        const selectAll = $('mx-select-page');
        selectAll.hidden = state.disabled || state.items.length === 0;
        const allPicked = state.items.length > 0 && state.items.every((item) => state.selected.has(item.id));
        selectAll.textContent = allPicked ? '取消全选' : '全选本页';

        [$('mx-clear'), $('mx-skip'), $('mx-archive'), $('mx-archive-rest'), $('mx-restore'),
            $('mx-side-archive'), $('mx-side-skip'), $('mx-side-restore'),
            $('mx-view-pending'), $('mx-view-skipped'), $('mx-select-page'), $('mx-refresh')]
            .forEach((btn) => { if (btn) btn.disabled = state.busy; });
    }

    function renderViewChrome() {
        const skipped = isSkippedView();
        $('mx-view-pending').setAttribute('aria-pressed', String(!skipped));
        $('mx-view-skipped').setAttribute('aria-pressed', String(skipped));
        $('mx-count').textContent = state.totalItems > 0
            ? (skipped ? `最近跳过 ${state.totalItems} 件` : `待整理 ${state.totalItems} 件`)
            : '';
        $('mx-pager').hidden = state.disabled || state.totalPages <= 1;
        $('mx-page').textContent = `${state.page + 1} / ${Math.max(1, state.totalPages)}`;
        $('mx-prev').disabled = state.busy || state.page <= 0;
        $('mx-next').disabled = state.busy || state.page + 1 >= state.totalPages;
    }

    async function load() {
        const id = ++state.requestId;
        $('mx-loading').hidden = state.items.length > 0;
        $('mx-empty').hidden = true;
        $('mx-status').hidden = true;
        const endpoint = isSkippedView() ? '/api/x-import/inbox/skipped' : '/api/x-import/inbox';
        try {
            const response = await fetch(`${endpoint}?page=${state.page}&size=${PAGE_SIZE}`, {
                headers: { Accept: 'application/json' },
                cache: 'no-store'
            });
            if (id !== state.requestId) return false;
            if (response.status === 403) {
                await readForbidden(response);
                return false;
            }
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const data = await response.json();
            clearDisabledState();
            state.items = Array.isArray(data.items) ? data.items : [];
            state.totalItems = data.totalItems || 0;
            state.totalPages = data.totalPages || 0;
            if (state.page > 0 && state.items.length === 0 && state.totalPages > 0) {
                state.page = Math.max(0, state.totalPages - 1);
                return load();
            }
            const visibleIds = new Set(state.items.map((item) => item.id));
            [...state.selected].forEach((sid) => { if (!visibleIds.has(sid)) state.selected.delete(sid); });
            $('mx-loading').hidden = true;
            renderGrid();
            if (state.items.length === 0) {
                $('mx-empty').hidden = false;
                $('mx-empty-title').textContent = isSkippedView() ? '最近没有跳过的收藏' : '待整理是空的';
                $('mx-empty-text').textContent = isSkippedView()
                    ? '整理时跳过的收藏会留在这里，可以随时放回待整理。'
                    : '电脑上同步的 X 收藏会出现在这里。';
            }
            renderViewChrome();
            syncSelection();
            renderPreviewMetaOnly();
            return true;
        } catch (error) {
            if (id !== state.requestId) return false;
            $('mx-loading').hidden = true;
            $('mx-grid').replaceChildren();
            $('mx-status').hidden = false;
            $('mx-status-text').textContent = '读取失败：' + error.message + '。请确认电脑端开启了服务和手机访问。';
            renderViewChrome();
            syncSelection();
            return false;
        }
    }

    async function postArchive(id) {
        const response = await fetch('/api/x-import/inbox/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ itemIds: [id] })
        });
        if (response.status === 403) {
            const code = await readForbidden(response);
            const err = new Error(code);
            err.forbidden = code;
            throw err;
        }
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
    }

    async function archiveEach(ids) {
        const outcome = { archived: [], problems: new Map(), unconfirmed: null, notSent: [], disabled: false };
        for (let i = 0; i < ids.length; i++) {
            const id = ids[i];
            $('mx-selected').textContent = `正在归档 ${i + 1} / ${ids.length}`;
            let summary;
            try {
                summary = await postArchive(id);
            } catch (error) {
                if (error && error.forbidden === 'MOBILE_X_DISABLED') {
                    outcome.disabled = true;
                    break;
                }
                outcome.unconfirmed = id;
                outcome.notSent = ids.slice(i + 1);
                break;
            }
            const result = summary.items && summary.items[0];
            if (result && result.status === 'SUCCESS') outcome.archived.push(id);
            else outcome.problems.set(id, M.archiveProblem(result || {}));
        }
        outcome.problems.forEach((text, id) => state.problems.set(id, text));
        outcome.archived.forEach((id) => { state.problems.delete(id); state.selected.delete(id); });
        return outcome;
    }

    async function patchItems(url, ids, extra = {}) {
        const response = await fetch(url, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ itemIds: ids, ...extra })
        });
        if (response.status === 403) {
            const code = await readForbidden(response);
            const err = new Error(code);
            err.forbidden = code;
            throw err;
        }
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
    }

    const postSkip = (ids) => patchItems('/api/x-import/inbox/skip', ids);
    const forget = (ids) => ids.forEach((id) => { state.selected.delete(id); state.problems.delete(id); });
    const undoFor = (result, label) => (result.skippedIds && result.skippedIds.length && result.skipToken
        ? { ids: result.skippedIds, skipToken: result.skipToken, label } : null);

    function settle() {
        state.busy = false;
        syncSelection();
        renderViewChrome();
    }

    async function archive(ids) {
        if (state.busy || state.disabled || !ids.length || isSkippedView()) return null;
        state.busy = true;
        syncSelection();
        const outcome = await archiveEach(ids);
        if (outcome.disabled || state.disabled) return outcome;
        await load();
        settle();
        toast(M.archiveSummary(outcome));
        return outcome;
    }

    async function archiveRest() {
        if (state.busy || state.disabled || isSkippedView()) return;
        const keep = state.items.filter((item) => state.selected.has(item.id)).map((item) => item.id);
        const rest = state.items.filter((item) => !state.selected.has(item.id)).map((item) => item.id);
        if (!keep.length || !rest.length) return;
        const ask = M.archiveRestConfirm(keep.length, rest.length);
        if (!(await confirmSheet(ask.title, ask.text, ask.ok)) || state.busy || state.disabled) return;
        state.busy = true;
        syncSelection();
        let skipped;
        try {
            skipped = await postSkip(rest);
            forget(rest);
        } catch (error) {
            if (error && error.forbidden === 'MOBILE_X_DISABLED') return;
            await load();
            settle();
            toast('没有确认跳过结果，这次没有归档。请看刷新后的列表');
            return;
        }
        await load();
        const outcome = await archiveEach(keep);
        if (outcome.disabled || state.disabled) return;
        await load();
        settle();
        toast(`${M.archiveSummary(outcome)}，跳过 ${skipped.skippedCount} 件`, undoFor(skipped, '撤销跳过'));
    }

    async function skip(ids) {
        if (state.busy || state.disabled || !ids.length || isSkippedView()) return false;
        state.busy = true;
        syncSelection();
        let done = false;
        try {
            const result = await postSkip(ids);
            forget(ids);
            toast(`已跳过 ${result.skippedCount} 件`, undoFor(result, '撤销'));
            done = true;
        } catch (error) {
            if (error && error.forbidden === 'MOBILE_X_DISABLED') return false;
            toast('没有确认跳过结果，请看刷新后的列表');
        }
        await load();
        settle();
        return done;
    }

    async function undoSkip() {
        const undo = state.undo;
        if (!undo) return;
        state.undo = null;
        $('mx-toast').hidden = true;
        try {
            const result = await patchItems('/api/x-import/inbox/unskip', undo.ids, { skipToken: undo.skipToken });
            toast(`已撤销，${result.restoredCount} 件放回待整理`);
        } catch (error) {
            if (error && error.forbidden === 'MOBILE_X_DISABLED') return;
            toast('没有确认撤销结果，请看刷新后的列表');
        }
        await load();
    }

    async function restore(ids) {
        if (state.busy || state.disabled || !ids.length || !isSkippedView()) return false;
        state.busy = true;
        syncSelection();
        let done = false;
        try {
            const result = await patchItems('/api/x-import/inbox/unskip', ids);
            forget(ids);
            toast(`已放回 ${result.restoredCount} 件，排在待整理原来的位置`);
            done = true;
        } catch (error) {
            if (error && error.forbidden === 'MOBILE_X_DISABLED') return false;
            toast('没有确认放回结果，请看刷新后的列表');
        }
        await load();
        settle();
        return done;
    }

    /* ---------------- 大图看图器（长按保存 / 跳过 / 归档） ---------------- */

    function clearStage() {
        const stage = $('mx-stage');
        releaseVideos(stage);
        stage.replaceChildren();
    }

    function maybeShowSaveHint(media) {
        const hint = $('mx-save-hint');
        if (!media || media.mediaType !== 'photo') {
            hint.hidden = true;
            return;
        }
        let dismissed = false;
        try {
            dismissed = !!(window.localStorage && window.localStorage.getItem(SAVE_HINT_KEY));
        } catch (ignored) {
            dismissed = false;
        }
        hint.hidden = dismissed;
    }

    function renderPreviewMetaOnly() {
        if (!state.preview) return;
        const preview = state.preview;
        const idx = state.items.findIndex((candidate) => candidate.id === preview.item.id);
        if (idx >= 0) preview.anchorIndex = idx;
        const media = sorted(preview.item);
        const pos = idx >= 0 ? `${idx + 1} / ${state.items.length}` : '已不在本页';
        const multi = media.length > 1 ? ` · ${preview.media + 1}/${media.length} 张` : '';
        $('mx-side-meta').textContent = pos + multi;
        const problem = state.problems.get(preview.item.id);
        $('mx-side-problem').hidden = !problem;
        $('mx-side-problem').textContent = problem || '';
        $('mx-side-archive').textContent = problem ? '重试归档' : '归档';
    }

    function renderPreview() {
        const preview = state.preview;
        if (!preview) return;
        const item = preview.item;
        const media = sorted(item);
        preview.media = Math.min(preview.media, Math.max(0, media.length - 1));
        clearStage();
        const current = media[preview.media];
        if (current) {
            const figure = el('figure', 'mx-figure');
            figure.appendChild(buildMediaNode(item, current, { size: '4096x4096', controls: true }));
            $('mx-stage').appendChild(figure);
        }
        maybeShowSaveHint(current);
        $('mx-media-switcher').hidden = media.length <= 1;
        $('mx-media-count').textContent = `${preview.media + 1} / ${Math.max(1, media.length)}`;
        $('mx-media-prev').disabled = preview.media <= 0;
        $('mx-media-next').disabled = preview.media + 1 >= media.length;

        $('mx-side-author').textContent = item.authorDisplayName || item.authorUsername;
        $('mx-side-handle').textContent = '@' + item.authorUsername;
        $('mx-side-text').textContent = item.postText || '';
        $('mx-side-open').href = postUrl(item);

        const skipped = isSkippedView();
        $('mx-side-archive').hidden = skipped;
        $('mx-side-skip').hidden = skipped;
        $('mx-side-restore').hidden = !skipped;
        renderPreviewMetaOnly();
    }

    function openPreview(item) {
        const idx = state.items.findIndex((candidate) => candidate.id === item.id);
        state.preview = { item, media: 0, anchorIndex: Math.max(0, idx) };
        renderPreview();
        $('mx-preview').hidden = false;
        if (typeof $('mx-preview').focus === 'function') $('mx-preview').focus();
    }

    function closePreview() {
        if (!state.preview) return;
        state.preview = null;
        clearStage();
        $('mx-preview').hidden = true;
    }

    function movePreviewItem(delta) {
        if (!state.preview) return;
        if (!state.items.length) {
            closePreview();
            return;
        }
        const curIdx = state.items.findIndex((candidate) => candidate.id === state.preview.item.id);
        let nextIdx;
        if (curIdx >= 0) {
            nextIdx = Math.min(Math.max(0, curIdx + delta), state.items.length - 1);
        } else {
            const anchor = state.preview.anchorIndex;
            nextIdx = delta >= 0
                ? Math.min(anchor, state.items.length - 1)
                : Math.max(0, anchor - 1);
        }
        state.preview = { item: state.items[nextIdx], media: 0, anchorIndex: nextIdx };
        renderPreview();
    }

    function advanceAfterRemoval(removedId, previousAnchor) {
        if (!state.preview || state.preview.item.id !== removedId) return;
        if (!state.items.length) {
            closePreview();
            return;
        }
        const stillHere = state.items.find((candidate) => candidate.id === removedId);
        if (stillHere) {
            state.preview.item = stillHere;
            renderPreview();
            return;
        }
        const nextIdx = Math.min(previousAnchor, state.items.length - 1);
        state.preview = { item: state.items[nextIdx], media: 0, anchorIndex: nextIdx };
        renderPreview();
    }

    async function previewArchive() {
        if (!state.preview || state.busy) return;
        const target = state.preview.item;
        const anchor = state.preview.anchorIndex;
        const outcome = await archive([target.id]);
        if (!outcome || state.disabled) return;
        if (outcome.problems.has(target.id) || outcome.unconfirmed === target.id) {
            renderPreview();
            return;
        }
        advanceAfterRemoval(target.id, anchor);
    }

    async function previewSkip() {
        if (!state.preview || state.busy) return;
        const target = state.preview.item;
        const anchor = state.preview.anchorIndex;
        const done = await skip([target.id]);
        if (done) advanceAfterRemoval(target.id, anchor);
    }

    async function previewRestore() {
        if (!state.preview || state.busy) return;
        const target = state.preview.item;
        const anchor = state.preview.anchorIndex;
        const done = await restore([target.id]);
        if (done) advanceAfterRemoval(target.id, anchor);
    }

    function switchView(nextView) {
        if (state.busy || state.view === nextView) return;
        state.view = nextView;
        state.page = 0;
        state.selected.clear();
        closePreview();
        load();
    }

    $('mx-view-pending').addEventListener('click', () => switchView('pending'));
    $('mx-view-skipped').addEventListener('click', () => switchView('skipped'));
    $('mx-refresh').addEventListener('click', () => { if (!state.busy) load(); });
    $('mx-recheck').addEventListener('click', () => load());
    $('mx-retry').addEventListener('click', () => load());
    $('mx-select-page').addEventListener('click', () => {
        if (state.busy || !state.items.length) return;
        const allPicked = state.items.every((item) => state.selected.has(item.id));
        if (allPicked) state.items.forEach((item) => state.selected.delete(item.id));
        else state.items.forEach((item) => state.selected.add(item.id));
        syncSelection();
    });
    $('mx-prev').addEventListener('click', () => {
        if (state.busy || state.page <= 0) return;
        state.page -= 1;
        state.selected.clear();
        load();
    });
    $('mx-next').addEventListener('click', () => {
        if (state.busy || state.page + 1 >= state.totalPages) return;
        state.page += 1;
        state.selected.clear();
        load();
    });

    $('mx-clear').addEventListener('click', () => { state.selected.clear(); syncSelection(); });
    $('mx-skip').addEventListener('click', () => skip([...state.selected]));
    $('mx-archive').addEventListener('click', () => archive([...state.selected]));
    $('mx-archive-rest').addEventListener('click', () => archiveRest());
    $('mx-restore').addEventListener('click', () => restore([...state.selected]));

    $('mx-side-close').addEventListener('click', () => closePreview());
    $('mx-side-prev').addEventListener('click', () => movePreviewItem(-1));
    $('mx-side-next').addEventListener('click', () => movePreviewItem(1));
    $('mx-media-prev').addEventListener('click', () => {
        if (!state.preview || state.preview.media <= 0) return;
        state.preview.media -= 1;
        renderPreview();
    });
    $('mx-media-next').addEventListener('click', () => {
        if (!state.preview) return;
        const media = sorted(state.preview.item);
        if (state.preview.media + 1 >= media.length) return;
        state.preview.media += 1;
        renderPreview();
    });
    $('mx-side-archive').addEventListener('click', () => previewArchive());
    $('mx-side-skip').addEventListener('click', () => previewSkip());
    $('mx-side-restore').addEventListener('click', () => previewRestore());

    $('mx-save-hint-close').addEventListener('click', () => {
        $('mx-save-hint').hidden = true;
        try {
            if (window.localStorage) window.localStorage.setItem(SAVE_HINT_KEY, '1');
        } catch (ignored) {
            // Ignore restricted storage mode.
        }
    });

    $('mx-confirm-cancel').addEventListener('click', () => closeConfirm(false));
    $('mx-confirm-ok').addEventListener('click', () => closeConfirm(true));

    if (typeof window !== 'undefined' && window.addEventListener) {
        window.addEventListener('pagehide', () => {
            clearStage();
            releaseVideos($('mx-grid'));
        });
    }

    load();
})();
