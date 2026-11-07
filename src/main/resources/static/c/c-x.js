/*
 * X 收藏：把同步来的点赞过一遍。选中多件批量处理，或打开预览逐件按 A 归档、S 跳过。
 * 跳过后 10 秒内可以撤销（Ctrl+Z），之后在“最近跳过”里放回。
 * 同步是付费的，只在用户点按钮时发生；按钮旁一直写着最多读多少条、约多少钱，第一次还会先说明费用。
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
    const PREVIEW_CLOSE_MILLIS = 160;
    const CONNECTION_POLL_MILLIS = 1500;
    const TOAST_MILLIS = 3200;
    const UNDO_MILLIS = 10000;

    let connectionTimer = 0;

    const state = {
        page: 0, totalPages: 0, total: 0, items: [],
        selected: new Set(), problems: new Map(),
        connected: false, scanBudget: 50, busy: false, recovering: false, progress: null,
        view: 'pending', // pending：待整理；skipped：最近跳过
        connectCard: null, emptyText: '同步后，待整理的收藏会出现在这里。',
        scanBudgets: [],
        undo: null, // { ids, skipToken, label }：提示条上还能撤销的那次跳过；提示条消失或被下一条换掉，就不能再撤销
        preview: null // { item, index, media }：item 是正在展示的那件，index 只是它在列表里的位置，刷新后要重新对上
    };
    let loadSeq = 0;

    const isSkippedView = () => state.view === 'skipped';

    // 提示条一次只说一件事。带撤销的那条停 10 秒，被下一条换掉就不能再撤销，免得撤到看不见的那一次
    function toast(text, undo = null) {
        const t = $('toast');
        t.textContent = text;
        state.undo = undo;
        if (undo) {
            const button = el('button', 'toast-action', undo.label);
            button.type = 'button';
            button.addEventListener('click', undoSkip);
            t.appendChild(button);
        }
        t.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { t.hidden = true; state.undo = null; }, undo ? UNDO_MILLIS : TOAST_MILLIS);
    }

    const postUrl = (item) => `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`;
    // 列表卡片只有 200–330 px 宽，用 680 的图就够清楚；预览要看细节，用 2048
    const LIST_SIZE = 'small';
    const PREVIEW_SIZE = 'large';
    const mediaUrl = (item, media, size) => media.mediaType === 'animated_gif'
        ? `/api/x-import/inbox/${encodeURIComponent(item.id)}/media/${encodeURIComponent(media.mediaKey)}/content`
        : M.photoVariant(media.sourceUrl, size);
    const sorted = (item) => [...(item.media || [])].sort((a, b) => a.sortOrder - b.sortOrder);

    // 从页面上拿掉的 <video> 仍占着解码器和连接，要清掉地址才会释放
    function releaseVideos(root) {
        root.querySelectorAll('video').forEach((video) => {
            video.pause();
            video.removeAttribute('src');
            video.load();
        });
    }

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
        image.addEventListener('load', () => image.classList.add('is-loaded'));
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
        const skipped = isSkippedView();
        const rest = state.items.length - count;
        $('x-bar').hidden = count === 0;
        $('x-selected').textContent = `已选 ${count} 件`;
        $('x-skip').hidden = skipped;
        $('x-archive').hidden = skipped;
        // 用户习惯先全选本页、再去掉不要的：这时一步做完；全选了就只是归档
        $('x-archive-rest').hidden = skipped || rest === 0;
        $('x-archive-rest').textContent = `归档，跳过其余 ${rest} 件`;
        $('x-archive').classList.toggle('bb-primary', !skipped && rest === 0);
        $('x-restore').hidden = !skipped;
        ['x-archive', 'x-archive-rest', 'x-restore', 'x-skip', 'x-clear', 'x-sync', 'x-view-pending', 'x-view-skipped']
            .forEach((id) => { $(id).disabled = state.busy; });
        $('x-recovery-actions').querySelectorAll('button').forEach((button) => { button.disabled = state.busy; });
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
            if (state.items.some((item) => item.id === id)) { kept.set(id, card); return; }
            releaseVideos(card);
            card.remove();
        });
        state.items.forEach((item, index) => {
            const card = kept.get(item.id) || renderCard(item);
            syncProblem(card, item.id);
            if (grid.children[index] !== card) grid.insertBefore(card, grid.children[index] || null);
        });
        $('x-loading').hidden = true;
        const skipped = isSkippedView();
        $('x-count').textContent = !state.total ? '' : skipped ? `${state.total} 件已跳过，最近跳过的在前` : `${state.total} 件待整理`;
        $('x-empty-title').textContent = skipped ? '没有跳过的收藏' : '收件箱是空的';
        $('x-empty-text').textContent = skipped ? '在待整理里跳过的收藏会出现在这里，可以放回。' : state.emptyText;
        $('x-empty').hidden = state.total > 0;
        $('x-select-page').hidden = state.items.length === 0;
        $('x-pager').hidden = state.totalPages <= 1;
        $('x-page').textContent = `${state.page + 1} / ${state.totalPages}`;
        $('x-prev').disabled = state.page === 0;
        $('x-next').disabled = state.page + 1 >= state.totalPages;
        syncSelection();
    }

    // 切换列表时前一次载入可能还没回来，只有最后发出的那次能画到页面上
    async function load(page = state.page) {
        const seq = ++loadSeq;
        const skipped = isSkippedView();
        const list = skipped ? '/api/x-import/inbox/skipped' : '/api/x-import/inbox';
        try {
            let target = page;
            let data;
            for (;;) {
                const response = await fetch(`${list}?page=${target}&size=${PAGE_SIZE}`, { headers: { Accept: 'application/json' } });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                data = await response.json();
                if (target === 0 || target < data.totalPages) break;
                target = Math.max(0, data.totalPages - 1); // 这一页被处理空了，退回最后一页
            }
            if (seq !== loadSeq) return true;
            state.page = target;
            state.items = data.items;
            state.total = data.totalItems;
            state.totalPages = data.totalPages;
            if (!skipped) state.progress = data.syncProgress; // 最近跳过与同步进度无关，保留待整理读到的
            const present = new Set(data.items.map((item) => item.id));
            state.selected.forEach((id) => { if (!present.has(id)) state.selected.delete(id); });
            renderList();
            renderRecovery();
            syncPreview();
            if (window.AppShell) window.AppShell.refreshInbox();
            return true;
        } catch (e) {
            if (seq !== loadSeq) return true;
            $('x-loading').hidden = false;
            $('x-loading').textContent = `${skipped ? '最近跳过' : '收件箱'}载入失败：${e.message}`;
            return false;
        }
    }

    /* ---------------- 待整理 / 最近跳过 ---------------- */

    // 同步、费用和进度提示都只属于待整理
    function renderView() {
        const skipped = isSkippedView();
        $('x-view-pending').setAttribute('aria-pressed', String(!skipped));
        $('x-view-skipped').setAttribute('aria-pressed', String(skipped));
        $('x-sync').hidden = skipped || !state.connected;
        $('x-sync-cost').hidden = skipped || !state.connected;
        $('x-sync-cost-text').textContent = M.syncEstimate(state.scanBudget);
        $('x-connect').hidden = skipped || !state.connectCard;
        renderRecovery();
    }

    /*
     * 切换时旧列表立刻清空：新列表到之前、或者载入失败时，页面上不能留着另一个列表的作品，
     * 否则选中它们再点按钮，操作的会是标签没说的那一边。
     */
    function setView(view) {
        if (state.busy || state.view === view) return;
        state.view = view;
        state.selected.clear();
        state.items = [];
        state.total = 0;
        state.totalPages = 0;
        renderView();
        renderList();
        $('x-empty').hidden = true;
        $('x-loading').hidden = false;
        $('x-loading').textContent = '正在载入…';
        load(0).then(() => window.scrollTo(0, 0));
    }

    /* ---------------- 连接状态与同步 ---------------- */

    function showResult(head, detail, { problem = false, settings = false, settingsHash = 'x' } = {}) {
        $('x-result').hidden = false;
        $('x-result').classList.toggle('is-problem', problem);
        $('x-result-head').textContent = head;
        $('x-result-detail').textContent = detail;
        const actions = $('x-result-actions');
        actions.replaceChildren();
        if (settings) {
            const link = el('a', 'link', '打开设置');
            link.href = `/c/settings.html#${settingsHash}`;
            actions.appendChild(link);
        }
    }

    // 授权在浏览器里完成，这一页不会收到通知，等待期间要自己再看一眼
    async function loadConnection() {
        let connection = null;
        try {
            const response = await fetch('/api/x-auth/status', { headers: { Accept: 'application/json' } });
            if (!response.ok) throw new Error();
            connection = await response.json();
            state.connected = connection.status.savedLogin === 'USABLE';
            state.scanBudget = connection.scanBudget;
            state.scanBudgets = connection.scanBudgets || [];
        } catch (e) {
            state.connected = false;
        }
        const card = M.connectionCard(connection);
        state.connectCard = card;
        if (card) {
            $('x-connect-title').textContent = card.title;
            $('x-connect-text').textContent = card.text;
            $('x-connect-link').textContent = card.action;
        }
        state.emptyText = state.connected ? '点“同步”取回你在 X 上新点赞的作品。' : '连接 X 账号后，待整理的收藏会出现在这里。';
        if (!isSkippedView()) $('x-empty-text').textContent = state.emptyText;
        renderView();
        clearTimeout(connectionTimer);
        if (connection && connection.status.state === 'WAITING') connectionTimer = setTimeout(loadConnection, CONNECTION_POLL_MILLIS);
    }

    function costNoticeSeen() {
        try { return localStorage.getItem(COST_NOTICE_KEY) === '1'; } catch (e) { return false; }
    }

    async function confirmCost() {
        if (costNoticeSeen()) return true;
        const ok = await confirmSheet('同步会产生 X API 费用',
            `${M.syncCost(state.scanBudget)}费用从你的 X 开发者额度中扣除。只有点“同步”时才会读取，不会在后台自动进行。`, '继续同步');
        if (ok) { try { localStorage.setItem(COST_NOTICE_KEY, '1'); } catch (e) { /* 下次再提示一次 */ } }
        return ok;
    }

    function confirmSheet(title, text, okLabel) {
        $('x-confirm-title').textContent = title;
        $('x-confirm-text').textContent = text;
        $('x-confirm-ok').textContent = okLabel;
        const sheet = $('x-confirm');
        const returnFocus = document.activeElement;
        sheet.hidden = false;
        $('x-confirm-cancel').focus();
        return new Promise((resolve) => {
            const done = (ok) => {
                sheet.hidden = true;
                $('x-confirm-ok').onclick = null;
                $('x-confirm-cancel').onclick = null;
                if (returnFocus && returnFocus.isConnected) returnFocus.focus();
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
            // 带上页面写着的上限：别处改过设置时，服务端在读 X 之前拒绝，按新额度重新决定
            const response = await fetch(`/api/x-import/sync?readLimit=${encodeURIComponent(state.scanBudget)}`,
                { method: 'POST', headers: { Accept: 'application/json' } });
            const body = await response.json().catch(() => ({}));
            if (response.status === 409 && body.code === 'READ_LIMIT_CHANGED') {
                state.scanBudget = body.readLimit;
                renderView();
                showResult('读取上限已经改了，这次没有同步',
                    `现在${M.syncEstimate(body.readLimit)}。没有读取 X，也没有产生费用；确认后再点“同步”。`,
                    { problem: true, settings: true, settingsHash: 'x-read-limit' });
                return;
            }
            if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
            const summary = M.syncSummary(body, state.scanBudgets);
            showResult(summary.problem || summary.headline, summary.problem ? summary.headline + '。' + summary.detail : summary.detail, {
                problem: !!summary.problem,
                settings: summary.readLimit || (!!summary.problem && /设置/.test(summary.problem)),
                settingsHash: summary.readLimit ? 'x-read-limit' : 'x'
            });
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

    /* ---------------- 同步进度需要处理时 ---------------- */

    function renderRecovery() {
        const found = window.UiCapabilities?.xImport === false || isSkippedView() ? null : M.recovery(state.progress, state.scanBudget);
        $('x-recovery').hidden = !found;
        if (!found) return;
        $('x-recovery-title').textContent = found.title;
        $('x-recovery-detail').textContent = found.detail;
        $('x-recovery-actions').replaceChildren(...found.actions.map((action, index) => {
            const button = el('button', index === 0 ? 'btn btn-ink' : 'btn', state.recovering ? '正在处理…' : action.label);
            button.type = 'button';
            button.disabled = state.busy;
            button.addEventListener('click', () => recover(action));
            return button;
        }));
    }

    async function recover(action) {
        if (state.busy || !(await confirmSheet(action.confirmTitle, action.confirmText, action.confirmLabel))) return;
        state.busy = true;
        state.recovering = true;
        renderRecovery();
        syncSelection();
        const result = await M.runRecovery(action, async () => {
            const response = await fetch(action.url, action.body ? {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify(action.body)
            } : { method: 'POST', headers: { Accept: 'application/json' } });
            if (response.ok) return null;
            if (response.status === 409) return { expired: true };
            const body = await response.json().catch(() => ({}));
            return body.message || `HTTP ${response.status}`;
        }, async () => (await load() ? state.progress : null));
        state.busy = false;
        state.recovering = false;
        renderRecovery();
        syncSelection();
        showResult(result.head, result.detail, { problem: result.problem });
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

    async function archiveEach(ids) {
        const outcome = await M.archiveAll(ids, postArchive, (done, total) => {
            $('x-selected').textContent = `正在归档 ${done + 1} / ${total}`;
        });
        outcome.problems.forEach((text, id) => state.problems.set(id, text));
        outcome.archived.forEach((id) => { state.problems.delete(id); state.selected.delete(id); });
        return outcome;
    }

    async function archive(ids) {
        if (state.busy || !ids.length || isSkippedView()) return null;
        state.busy = true;
        syncSelection();
        const outcome = await archiveEach(ids);
        await load();
        settle();
        toast(M.archiveSummary(outcome));
        return outcome;
    }

    /*
     * 归档选中的，这一页没选的跳过。先跳过：一次请求就能确认，没选的马上离开页面；跳过没有确认时什么都不归档。
     * 再逐件归档，某件失败只留下它自己和原因。最后提示条只撤销跳过，已经归档的不受影响。
     */
    async function archiveRest() {
        if (state.busy || isSkippedView()) return;
        const keep = state.items.filter((item) => state.selected.has(item.id)).map((item) => item.id);
        const rest = state.items.filter((item) => !state.selected.has(item.id)).map((item) => item.id);
        if (!keep.length || !rest.length) return;
        const ask = M.archiveRestConfirm(keep.length, rest.length);
        if (!(await confirmSheet(ask.title, ask.text, ask.ok)) || state.busy) return;
        state.busy = true;
        syncSelection();
        let skipped;
        try {
            skipped = await postSkip(rest);
            forget(rest);
        } catch (e) {
            await load();
            settle();
            toast('没有确认跳过结果，这次没有归档。请看刷新后的列表');
            return;
        }
        await load();
        const outcome = await archiveEach(keep);
        await load();
        settle();
        toast(`${M.archiveSummary(outcome)}，跳过 ${skipped.skippedCount} 件`, undoFor(skipped, '撤销跳过'));
    }

    // 列表刷新完才算结束：刷新途中再按一次，处理的还是刚才那一件
    function settle() {
        state.busy = false;
        syncSelection();
    }

    async function patchItems(url, ids, extra = {}) {
        const response = await fetch(url, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ itemIds: ids, ...extra })
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
    }

    const postSkip = (ids) => patchItems('/api/x-import/inbox/skip', ids);
    const forget = (ids) => ids.forEach((id) => { state.selected.delete(id); state.problems.delete(id); });

    /*
     * 撤销只认服务端确认这一次跳过的作品和这次跳过的凭据：请求里别处已经跳过的不算，
     * 放回后又被重新跳过的也不算，那是另一次跳过、另一个凭据。一件都没跳过就没有可撤销的。
     */
    const undoFor = (result, label) => (result.skippedIds && result.skippedIds.length && result.skipToken
        ? { ids: result.skippedIds, skipToken: result.skipToken, label } : null);

    async function skip(ids) {
        if (state.busy || !ids.length || isSkippedView()) return false;
        state.busy = true;
        syncSelection();
        let done = false;
        try {
            const result = await postSkip(ids);
            forget(ids);
            toast(`已跳过 ${result.skippedCount} 件`, undoFor(result, '撤销'));
            done = true;
        } catch (e) {
            toast('没有确认跳过结果，请看刷新后的列表');
        }
        await load();
        settle();
        return done;
    }

    /*
     * 撤销只针对提示条上那一次。放回只改仍是“已跳过”的，所以在别处已经放回或处理过的不会被动到。
     * 不等其他操作结束：它碰的是另一批作品，列表随后按服务端刷新。
     */
    async function undoSkip() {
        const undo = state.undo;
        if (!undo) return;
        state.undo = null;
        $('toast').hidden = true;
        try {
            const result = await patchItems('/api/x-import/inbox/unskip', undo.ids, { skipToken: undo.skipToken });
            toast(`已撤销，${result.restoredCount} 件放回待整理`);
        } catch (e) {
            toast('没有确认撤销结果，请看刷新后的列表');
        }
        await load();
    }

    // 最近跳过里挑出来的，按点赞顺序排回待整理原来的位置
    async function restore(ids) {
        if (state.busy || !ids.length || !isSkippedView()) return false;
        state.busy = true;
        syncSelection();
        let done = false;
        try {
            const result = await patchItems('/api/x-import/inbox/unskip', ids);
            forget(ids);
            toast(`已放回 ${result.restoredCount} 件，排在待整理原来的位置`);
            done = true;
        } catch (e) {
            toast('没有确认放回结果，请看刷新后的列表');
        }
        await load();
        settle();
        return done;
    }

    /* ---------------- 预览 ---------------- */

    function clearStage() {
        const stage = $('x-stage');
        releaseVideos(stage);
        stage.replaceChildren();
    }

    function renderPreview() {
        const preview = state.preview;
        const item = preview.item;
        const media = sorted(item);
        preview.media = Math.min(preview.media, Math.max(0, media.length - 1));
        clearStage();
        const stage = $('x-stage');
        if (media[preview.media]) stage.appendChild(mediaElement(item, media[preview.media], { controls: true }));
        if (media.length > 1) stage.appendChild(el('span', 'x-stage-count', `${preview.media + 1} / ${media.length}`));
        $('x-side-author').textContent = item.authorDisplayName || item.authorUsername;
        $('x-side-handle').textContent = '@' + item.authorUsername;
        $('x-side-text').textContent = item.postText || '';
        $('x-side-open').href = postUrl(item);
        const skipped = isSkippedView();
        $('x-side-archive').hidden = skipped;
        $('x-side-skip').hidden = skipped;
        $('x-side-restore').hidden = !skipped;
        renderPreviewStatus();
        preloadNext(preview.index);
    }

    function renderPreviewStatus() {
        const { item, index } = state.preview;
        const created = item.postCreatedAt ? new Date(item.postCreatedAt).toLocaleDateString() : '';
        const position = indexOfItem(item.id) < 0 ? '' : `第 ${state.page * PAGE_SIZE + index + 1} / ${state.total} 件`;
        $('x-side-meta').textContent = [created, position].filter(Boolean).join(' · ');
        const problem = state.problems.get(item.id);
        $('x-side-problem').hidden = !problem;
        $('x-side-problem').textContent = problem || '';
    }

    /*
     * 列表刷新（同步结束、别处处理过）时序号可能已经换了人，这里只按 ID 把位置对上，画面不动。
     * 这件不在新列表里时继续展示：它可能只是被新收藏挤到后面的页，手下的作品不能悄悄换掉，
     * index 留着它原来的位置，后面的作品已经补到这个序号上。
     */
    function syncPreview() {
        const preview = state.preview;
        if (!preview) return;
        const index = indexOfItem(preview.item.id);
        if (index >= 0) {
            preview.index = index;
            preview.item = state.items[index];
        }
        renderPreviewStatus();
    }

    // 逐件整理时下一张往往马上要看，提前取好就不用等
    function preloadNext(index) {
        const next = state.items[index + 1];
        const media = next && sorted(next)[0];
        if (media && media.mediaType === 'photo') new Image().src = mediaUrl(next, media, PREVIEW_SIZE);
    }

    function openPreview(index) {
        if (index < 0) return;
        state.preview = { item: state.items[index], index, media: 0 };
        $('x-preview').hidden = false;
        document.body.classList.add('is-locked');
        $('x-preview').focus({ preventScroll: true });
        renderPreview();
    }

    function closePreview() {
        state.preview = null;
        $('x-stage').querySelectorAll('video').forEach((video) => video.pause());
        $('x-preview').hidden = true;
        document.body.classList.remove('is-locked');
        // 退场结束后再清空，淡出时看到的还是这件作品
        setTimeout(() => { if (!state.preview) clearStage(); }, PREVIEW_CLOSE_MILLIS);
    }

    function showInPreview(preview, index) {
        preview.item = state.items[index];
        preview.index = index;
        preview.media = 0;
        renderPreview();
    }

    // 处理的永远是展示着的这一件。处理完它不在列表里了，它原来的序号上就是下一件；
    // 还在或有问题记录，说明没处理成功，留下来显示原因；处理途中已经翻到别的作品时，停在那一件上
    async function decide(action) {
        const preview = state.preview;
        if (!preview || state.busy) return;
        const { id } = preview.item;
        await action([id]);
        if (state.preview !== preview || preview.item.id !== id) return;
        if (indexOfItem(id) >= 0 || state.problems.has(id)) {
            preview.media = 0;
            renderPreview();
        } else if (state.items.length) showInPreview(preview, Math.min(preview.index, state.items.length - 1));
        else closePreview();
    }

    function movePreview(step) {
        const preview = state.preview;
        // 这一件已不在列表里时，它的序号上已经是下一件，往后翻不用再加一
        const next = indexOfItem(preview.item.id) < 0 && step > 0 ? preview.index : preview.index + step;
        if (next < 0 || next >= state.items.length) return;
        showInPreview(preview, next);
    }

    function moveMedia(step) {
        const preview = state.preview;
        const count = sorted(preview.item).length;
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
        if ((e.ctrlKey || e.metaKey) && !e.shiftKey && e.key.toLowerCase() === 'z' && state.undo
            && !(e.target instanceof Element && e.target.closest('input, textarea'))) {
            e.preventDefault();
            undoSkip();
            return;
        }
        if (state.preview) {
            if (e.key === 'Escape') closePreview();
            else if (e.key === 'ArrowRight') moveMedia(1);
            else if (e.key === 'ArrowLeft') moveMedia(-1);
            else if (e.key === 'ArrowDown') movePreview(1);
            else if (e.key === 'ArrowUp') movePreview(-1);
            else if (e.key.toLowerCase() === 'a' && !isSkippedView()) decide(archive);
            else if (e.key.toLowerCase() === 's' && !isSkippedView()) decide(skip);
            else return;
            e.preventDefault();
            return;
        }
        if (e.target instanceof Element && e.target.closest('input, textarea')) return;
        if (isSkippedView()) {
            if (e.key === 'Escape' && state.selected.size) { state.selected.clear(); syncSelection(); }
            return;
        }
        if (state.selected.size && e.key.toLowerCase() === 'a' && !e.ctrlKey) archive([...state.selected]);
        else if (state.selected.size && e.key.toLowerCase() === 's' && !e.ctrlKey) skip([...state.selected]);
        else if (e.key === 'Escape' && state.selected.size) { state.selected.clear(); syncSelection(); }
    });

    // 费用确认和图库的确认框一样：点背景算取消，Tab 只在两个按钮之间切换
    $('x-confirm').addEventListener('mousedown', (e) => { if (e.target === $('x-confirm')) $('x-confirm-cancel').click(); });
    $('x-confirm').addEventListener('keydown', (e) => {
        if (e.key !== 'Tab') return;
        e.preventDefault();
        (document.activeElement === $('x-confirm-ok') ? $('x-confirm-cancel') : $('x-confirm-ok')).focus();
    });

    $('x-sync').addEventListener('click', sync);
    $('x-archive').addEventListener('click', () => archive([...state.selected]));
    $('x-skip').addEventListener('click', () => skip([...state.selected]));
    $('x-archive-rest').addEventListener('click', archiveRest);
    $('x-restore').addEventListener('click', () => restore([...state.selected]));
    $('x-view-pending').addEventListener('click', () => setView('pending'));
    $('x-view-skipped').addEventListener('click', () => setView('skipped'));
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
    $('x-side-restore').addEventListener('click', () => decide(restore));
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
