/*
 * C 图库 · 本地导入
 *
 * “导入作品”、Ctrl + O、空图库里的按钮，或者把外部图片文件拖进页面，都打开导入面板。
 * - 文件交给 archive-client.js 的 importQueue：逐个提交，按服务端逐项结果显示“已导入 / 图库中已存在 / 失败 / 无法确认”。
 *   部分失败不影响其他文件；连接中断时如实显示“无法确认”，队列暂停，由用户决定继续或重试，不自动重传。
 * - 处理完一轮后按服务端结果重读已载入的列表：新作品高亮出现，可以直接“打开”去看图、编辑。
 *   有搜索或筛选时条件保留；新作品不符合条件就说明原因，并给出“清除条件查看”。
 * - 只接受从页面外拖进来的文件：卡片拖动、循环换牌、拖动浏览都不是原生拖放，也不带 Files；
 *   页面内发起的原生拖动另加标记排除。看图器、编辑确认框开着时不导入，但仍拦下浏览器“打开这个文件”的默认行为，
 *   页面不会被换掉、草稿不会丢。
 */
(function () {
    'use strict';

    const A = window.Archive;
    const $ = (id) => document.getElementById(id);
    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };
    const host = (name, ...args) => {
        const api = window.GalleryApi;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    const filters = (name, ...args) => {
        const api = window.CFilters;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    const editor = (name, ...args) => {
        const api = window.CEditor;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };

    const sheet = $('import-sheet');
    const input = $('import-input');
    const dropzone = $('dropzone');
    const list = $('import-list');
    const bar = $('import-bar');
    const notice = $('import-notice');
    const summary = $('import-summary');
    const retryButton = $('import-retry');
    const resumeButton = $('import-resume');
    const doneButton = $('import-done');
    const closeButton = $('import-close');

    const STATUS_TEXT = {
        queued: '等待', uploading: '上传中…', success: '已导入', duplicate: '图库中已存在', failed: '失败', unknown: '无法确认'
    };

    let queue = null;
    let wasRunning = false;
    let openedByDrag = false;
    let returnFocus = null;
    const rows = new Map();
    const previews = [];
    const refreshed = new Set();
    let lastNotice = null;

    /* ---------------- 打开与关闭 ---------------- */

    function isOpen() { return !sheet.hidden; }

    function open({ pick = false } = {}) {
        if (host('isViewerOpen')) return false;
        if (!isOpen()) {
            returnFocus = document.activeElement;
            sheet.hidden = false;
            host('setBackgroundInert', true);
            render();
        }
        if (pick) input.click();
        else $('import-pick').focus();
        return true;
    }

    // 正在导入时不关闭（导入不会因关面板而中断，但结果需要看到）；关闭后清空这一轮的列表
    function close() {
        if (queue && queue.isRunning()) {
            host('toast', '正在导入，完成后再关闭');
            return false;
        }
        sheet.hidden = true;
        host('setBackgroundInert', false);
        previews.splice(0).forEach((url) => URL.revokeObjectURL(url));
        rows.clear();
        list.innerHTML = '';
        queue = null;
        wasRunning = false;
        lastNotice = null;
        openedByDrag = false;
        render();
        if (returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') returnFocus.focus();
        returnFocus = null;
        return true;
    }

    function addFiles(entries) {
        if (!entries.length) return;
        open();
        if (!queue) queue = A.importQueue({ onChange: render });
        const added = queue.add(entries);
        added.forEach((item) => rows.set(item.key, rowFor(item)));
        render();
    }

    /* ---------------- 列表与汇总 ---------------- */

    function rowFor(item) {
        const li = el('li');
        let thumb;
        if (/^image\/(jpeg|png|gif)$/.test(item.file.type || '')) {
            thumb = el('img', 'thumb');
            thumb.alt = '';
            const url = URL.createObjectURL(item.file);
            previews.push(url);
            thumb.src = url;
        } else thumb = el('span', 'thumb');
        li.appendChild(thumb);
        const text = el('div');
        text.appendChild(el('span', 'name', item.name));
        text.appendChild(el('small'));
        li.appendChild(text);
        li.appendChild(el('span', 'st'));
        li.appendChild(el('span', 'imp-action'));
        list.appendChild(li);
        return li;
    }

    function renderRow(item) {
        const li = rows.get(item.key);
        if (!li) return;
        li.className = 'is-' + item.status;
        li.querySelector('small').textContent = item.message || A.formatSize(item.size);
        const st = li.querySelector('.st');
        st.className = 'st st-' + item.status;
        st.textContent = STATUS_TEXT[item.status];
        const action = li.querySelector('.imp-action');
        action.innerHTML = '';
        if ((item.status === 'success' || item.status === 'duplicate') && item.illustrationId != null && A.listed(item.illustrationId)) {
            const b = el('button', 'link', '打开');
            b.type = 'button';
            b.title = item.status === 'success' ? '在看图器中打开，可以继续编辑' : '打开图库中已有的这件作品';
            b.addEventListener('click', () => {
                if (close()) host('openViewer', item.illustrationId);
            });
            action.appendChild(b);
        }
    }

    function render() {
        const running = !!queue && queue.isRunning();
        if (queue) queue.items.forEach(renderRow);
        const s = queue ? queue.summary() : null;
        dropzone.classList.toggle('is-compact', !!s && s.total > 0);
        closeButton.disabled = running;
        bar.hidden = !s || s.total === 0;
        if (s) {
            const done = s.total - s.queued - s.uploading;
            bar.firstElementChild.style.width = (s.total ? (done / s.total) * 100 : 0) + '%';
            bar.classList.toggle('is-running', running);
        }

        if (!s || !s.total) summary.textContent = '';
        else if (running) summary.textContent = `正在导入 ${s.total - s.queued - s.uploading + 1} / ${s.total}`;
        else {
            const parts = [`已导入 ${s.success}`];
            if (s.duplicate) parts.push(`已存在 ${s.duplicate}`);
            if (s.failed) parts.push(`失败 ${s.failed}`);
            if (s.unknown) parts.push(`无法确认 ${s.unknown}`);
            if (s.queued) parts.push(`未开始 ${s.queued}`);
            const paused = queue.pausedReason();
            summary.textContent = (paused === 'network' ? '已暂停：连接中断　' : paused === 'session' ? '已暂停：会话失效　' : '') + parts.join(' · ');
        }

        const retryable = queue && !running ? queue.retryable() : 0;
        retryButton.hidden = !retryable;
        retryButton.textContent = `重试 ${retryable} 张`;
        retryButton.title = '只重新提交失败和无法确认的；已导入、已存在的不会再提交';
        resumeButton.hidden = !(queue && !running && queue.pausedReason() && s.queued);
        resumeButton.textContent = s ? `继续导入剩下的 ${s.queued} 张` : '继续导入';
        doneButton.hidden = !(s && s.total && !running);
        renderNotice();

        // 一轮处理刚结束：按服务端结果刷新列表
        if (wasRunning && !running) settle();
        wasRunning = running;
    }

    function renderNotice() {
        notice.innerHTML = '';
        const lines = [];
        const s = queue ? queue.summary() : null;
        if (s && s.unknown && !queue.isRunning()) {
            lines.push([`有 ${s.unknown} 张因连接中断，无法确认是否已经导入。可以刷新图库查看；也可以重试，已经导入的会显示为“图库中已存在”。`]);
        }
        if (lastNotice && lastNotice.hidden) {
            const clear = el('button', 'link', '清除条件查看');
            clear.type = 'button';
            clear.addEventListener('click', () => { if (close()) filters('clearAll'); });
            lines.push([`新导入的 ${lastNotice.hidden} 件不符合当前的搜索或筛选条件，所以没有出现在图库里。`, clear]);
        }
        if (lastNotice && lastNotice.refreshError) {
            lines.push([`图库列表没能刷新：${lastNotice.refreshError}。刷新页面即可看到新作品。`]);
        }
        notice.hidden = !lines.length;
        lines.forEach((parts) => {
            const p = el('p');
            parts.forEach((part, i) => { if (i) p.append('　'); p.append(part); });
            notice.appendChild(p);
        });
    }

    // 一轮结束：有新进图库的作品（含“无法确认”后重试才知道已导入的）就按服务端结果重读已载入的列表（保留当前条件），
    // 再对照哪些因条件不符没有出现
    async function settle() {
        const mine = queue;
        const fresh = mine.newIds().filter((id) => !refreshed.has(id));
        if (!fresh.length) return;
        fresh.forEach((id) => refreshed.add(id));
        host('markFresh', fresh);
        const result = await A.refreshLoaded();
        if (A.isFiltered()) {
            try { host('setLibraryTotal', await A.libraryCount()); } catch (e) { /* 只影响“全部”旁的数字 */ }
        }
        if (queue !== mine) return;
        const hidden = A.isFiltered() ? mine.newIds().filter((id) => !A.listed(id)).length : 0;
        lastNotice = { hidden, refreshError: result.error ? result.error.message : null };
        render();
    }

    /* ---------------- 入口 ---------------- */

    $('import-open').addEventListener('click', () => open());
    $('empty-import').addEventListener('click', () => open({ pick: true }));
    $('import-pick').addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
        addFiles([...input.files]);
        input.value = '';
    });
    closeButton.addEventListener('click', close);
    doneButton.addEventListener('click', close);
    retryButton.addEventListener('click', () => { if (queue) queue.retry(); });
    resumeButton.addEventListener('click', () => { if (queue) queue.resume(); });
    sheet.addEventListener('mousedown', (e) => { if (e.target === sheet) close(); });
    sheet.addEventListener('keydown', (e) => {
        // 面板里的按键不触发图库快捷键
        e.stopPropagation();
        if (e.key === 'Escape') {
            e.preventDefault();
            close();
        }
    });
    window.addEventListener('beforeunload', (e) => {
        if (queue && queue.isRunning()) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    /* ---------------- 拖入 ---------------- */

    const INTERNAL_DRAG = 'application/x-illustration-archive-internal';
    // 页面里发起的原生拖动（链接、文字、图片）打上标记，不当作外部文件
    document.addEventListener('dragstart', (e) => {
        try { e.dataTransfer.setData(INTERNAL_DRAG, '1'); } catch (err) { /* 拖动照常 */ }
    });
    const externalFiles = (e) => {
        const types = [...((e.dataTransfer && e.dataTransfer.types) || [])];
        return types.includes('Files') && !types.includes(INTERNAL_DRAG);
    };
    // 看图器、编辑确认、快捷键说明开着时不接受导入
    const canImportNow = () => !host('isViewerOpen') && !editor('isConfirming') && $('shortcuts').hidden;

    function entriesOf(dataTransfer) {
        const items = [...(dataTransfer.items || [])].filter((item) => item.kind === 'file');
        if (!items.length) return [...(dataTransfer.files || [])].map((file) => ({ file }));
        return items.map((item) => {
            const entry = typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null;
            return { file: item.getAsFile(), directory: !!(entry && entry.isDirectory) };
        }).filter((entry) => entry.file);
    }

    let dragDepth = 0;
    window.addEventListener('dragenter', (e) => {
        if (!externalFiles(e)) return;
        e.preventDefault();
        dragDepth++;
        if (!canImportNow()) return;
        if (!isOpen()) {
            openedByDrag = true;
            open();
        }
        dropzone.classList.add('is-over');
    });
    window.addEventListener('dragover', (e) => {
        if (!externalFiles(e)) return;
        // 无论能否导入都拦下默认行为，否则浏览器会直接打开这个文件、换掉当前页面
        e.preventDefault();
        e.dataTransfer.dropEffect = canImportNow() ? 'copy' : 'none';
    });
    window.addEventListener('dragleave', (e) => {
        if (!externalFiles(e)) return;
        dragDepth = Math.max(0, dragDepth - 1);
        if (dragDepth) return;
        dropzone.classList.remove('is-over');
        // 只是拖过、没有放下：拖动打开的空面板收回去
        if (openedByDrag && (!queue || !queue.items.length)) close();
    });
    window.addEventListener('drop', (e) => {
        if (!externalFiles(e)) return;
        e.preventDefault();
        dragDepth = 0;
        dropzone.classList.remove('is-over');
        if (!canImportNow()) {
            host('toast', '看图或编辑时不能导入，请先返回图库');
            return;
        }
        openedByDrag = false;
        addFiles(entriesOf(e.dataTransfer));
    });

    window.CImport = { open, close, isOpen, addFiles, isRunning: () => !!queue && queue.isRunning() };
})();
