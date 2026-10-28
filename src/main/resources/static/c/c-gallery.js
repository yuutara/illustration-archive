/*
 * C 图库 · 浏览
 * 作品墙、看图器、多图翻页与循环牌堆沿用原型 B 已验收的交互；数据来自 archive-client.js。
 * 搜索与作者 / 标签筛选的界面在 c-filters.js，单件编辑在 c-editor.js；这里负责按当前查询结果铺墙、显示状态，
 * 看图器只在当前结果里前后翻。离开当前作品（关闭、切换、点作者 / 标签）都先经过编辑器的“离开”确认。
 * 本地导入在 c-import.js。删除、批量整理尚未接入：入口保留但明确禁用，快捷键只提示“尚未接入”。
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
    const icon = (name) => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
    const NOT_YET = '删除与批量整理尚未接入新版图库，请在旧图库中操作';

    const state = {
        density: 'roomy', results: A.works, viewer: null,
        // 选择模式在本包中不可用；保留字段供 fx.js 读取
        selecting: false, selected: new Set(), focusId: null, anchorId: null
    };

    const fx = (name, ...args) => {
        const hooks = window.GalleryFx;
        return hooks && typeof hooks[name] === 'function' ? hooks[name](...args) : undefined;
    };
    // c-filters.js、c-editor.js 在本文件之后载入，用到时再取
    const filters = (name, ...args) => {
        const api = window.CFilters;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    const editor = (name, ...args) => {
        const api = window.CEditor;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    // 离开当前作品前问编辑器：没在编辑或没有改动时直接放行；有改动时确认；保存中不放行
    const leaveEditor = async () => (await editor('requestLeave')) !== false;
    const importer = (name, ...args) => {
        const api = window.CImport;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    // 刚导入的作品：下次上墙时高亮一下
    const freshIds = new Set();

    function toast(text) {
        const t = $('toast');
        t.textContent = text;
        t.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { t.hidden = true; }, 2400);
    }

    /* ---------------- 深浅色 ---------------- */

    function syncThemeButton() {
        const dark = document.documentElement.dataset.mode === 'dark';
        const b = $('theme-toggle');
        b.setAttribute('aria-pressed', String(dark));
        b.title = dark ? '切换到浅色' : '切换到深色';
        b.querySelector('use').setAttribute('href', dark ? '#i-sun' : '#i-moon');
    }
    $('theme-toggle').addEventListener('click', () => {
        window.CTheme.set(document.documentElement.dataset.mode === 'dark' ? 'light' : 'dark');
        syncThemeButton();
    });
    syncThemeButton();

    /* ---------------- 作品墙 ---------------- */

    const pieces = new Map();

    function pieceFor(work) {
        let fig = pieces.get(work.id);
        if (!fig) {
            fig = el('figure', 'piece');
            fig.dataset.id = work.id;
            fig.innerHTML = `<div class="piece-media">
                    <button class="piece-open" type="button"><img alt="" loading="lazy" decoding="async" draggable="false"></button>
                    <div class="piece-marks"></div>
                    <button class="piece-edit" type="button" title="编辑作品信息（E）">${icon('pen')}编辑</button>
                </div>
                <figcaption><span class="piece-title"></span><button class="piece-author" type="button"></button></figcaption>`;
            fig.querySelector('.piece-open').addEventListener('click', () => {
                state.focusId = work.id;
                openViewer(work.id);
            });
            // 卡片上的“编辑”：打开看图器并直接进入编辑，与看图器里的“编辑”、E 键同一流程
            fig.querySelector('.piece-edit').addEventListener('click', () => {
                state.focusId = work.id;
                openViewer(work.id, { edit: true });
            });
            // 点作者名：只看这位作者（关键词和标签条件保留）
            fig.querySelector('.piece-author').addEventListener('click', () => {
                const current = A.get(work.id);
                if (current && current.author) filters('only', 'author', current.author);
            });
            pieces.set(work.id, fig);
        }
        const img = fig.querySelector('img');
        const p = work.pages[0];
        if (img.getAttribute('src') !== p.src) img.src = p.src;
        img.width = p.width;
        img.height = p.height;
        fig.classList.toggle('is-broken', p.broken);
        fig.querySelector('.piece-open').setAttribute('aria-label', '查看 ' + A.displayTitle(work));
        fig.querySelector('.piece-title').textContent = A.displayTitle(work);
        const name = A.authorName(work);
        const ab = fig.querySelector('.piece-author');
        ab.textContent = name || '未设置作者';
        ab.classList.toggle('is-none', !name);
        ab.disabled = !work.author;
        ab.title = work.author ? `只看 ${name} 的作品` : '';
        if (freshIds.delete(work.id)) {
            fig.classList.add('is-fresh');
            fig.addEventListener('animationend', () => fig.classList.remove('is-fresh'), { once: true });
        }
        const marks = fig.querySelector('.piece-marks');
        marks.innerHTML = '';
        // 多页角标必须排在第一个：牌堆会把它改成“当前页 / 总页数”
        if (work.pages.length > 1) marks.appendChild(el('span', 'mark', `${work.pages.length} 页`));
        if (p.format === 'gif') marks.appendChild(el('span', 'mark', 'GIF'));
        if (p.video) marks.appendChild(el('span', 'mark', '视频'));
        return fig;
    }

    function renderWall() {
        const wall = $('wall');
        const roomy = state.density === 'roomy';
        const target = roomy ? 290 : 200;
        const gap = roomy ? 32 : 14;
        const width = wall.clientWidth;
        const cols = Math.max(1, Math.floor((width + gap) / (target + gap)));
        const colWidth = (width - gap * (cols - 1)) / cols;
        const before = fx('captureWall');
        wall.classList.remove('is-refreshing');
        wall.removeAttribute('aria-busy');
        wall.innerHTML = '';
        wall.style.gap = gap + 'px';
        wall.classList.toggle('compact', !roomy);
        const columns = [], heights = [];
        for (let i = 0; i < cols; i++) {
            const col = el('div', 'wall-col');
            col.style.gap = (roomy ? gap - 4 : gap) + 'px';
            wall.appendChild(col);
            columns.push(col);
            heights.push(0);
        }
        state.results.forEach((work) => {
            const p = work.pages[0];
            const i = heights.indexOf(Math.min(...heights));
            columns[i].appendChild(pieceFor(work));
            heights[i] += colWidth * (p.height / p.width) + (roomy ? 34 : 0) + gap;
        });
        renderStatus();
        fx('playWall', before);
    }

    // “全部”旁的数字只表示整个图库；筛选时的结果数显示在已选条件旁
    let libraryTotal = null;

    // 墙下的状态行：载入中 / 失败可重试 / 已载入 n / N / 全部载入；空图库、无匹配结果与首屏失败单独显示
    function renderStatus() {
        const total = A.total();
        const count = state.results.length;
        const error = A.lastError();
        const loading = A.isLoading();
        const filtered = A.isFiltered();
        if (!filtered && total != null) libraryTotal = total;
        $('count-all').textContent = libraryTotal == null ? '' : String(libraryTotal);
        const settledEmpty = total === 0 && !loading && !error;
        $('empty').hidden = !(settledEmpty && !filtered);
        $('no-match').hidden = !(settledEmpty && filtered);
        const status = $('wall-status');
        const text = $('wall-status-text');
        const retry = $('wall-retry');
        const more = $('wall-more');
        status.hidden = false;
        retry.hidden = !error;
        more.hidden = true;
        status.classList.toggle('is-error', !!error);
        status.classList.toggle('is-loading', loading);
        if (error) {
            const first = filtered ? '搜索失败：' : '图库载入失败：';
            text.textContent = (count ? `已载入 ${count} 件；继续载入失败：` : first) + error.message;
        } else if (loading) {
            text.textContent = count ? `正在载入更多… 已载入 ${count} / ${total}` : filtered ? '正在查找…' : '正在载入图库…';
        } else if (total === 0) {
            status.hidden = true;
        } else if (A.hasMore()) {
            text.textContent = `已载入 ${count} / ${total} 件`;
            more.hidden = false;
        } else {
            text.textContent = filtered ? `· 符合条件的 ${total} 件已全部载入 ·` : `· 共 ${total} 件，已全部载入 ·`;
        }
        filters('renderSummary');
    }

    // 接近底部时自动载入下一页；失败后不自动重试，等用户点“重试”
    const loadObserver = new IntersectionObserver((entries) => {
        if (entries.some((e) => e.isIntersecting) && !A.lastError() && A.hasMore()) A.loadMore();
    }, { rootMargin: '900px 0px' });
    loadObserver.observe($('wall-status'));
    $('wall-retry').addEventListener('click', () => A.loadMore());
    $('wall-more').addEventListener('click', () => A.loadMore());
    $('no-match-clear').addEventListener('click', () => filters('clearAll'));

    document.querySelectorAll('.density button').forEach((b) => b.addEventListener('click', () => {
        state.density = b.dataset.density;
        document.querySelectorAll('.density button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
        renderWall();
    }));
    window.addEventListener('scroll', () => $('masthead').classList.toggle('is-scrolled', window.scrollY > 4), { passive: true });
    let wallWidth = 0;
    new ResizeObserver(([entry]) => {
        if (Math.abs(entry.contentRect.width - wallWidth) < 1) return;
        wallWidth = entry.contentRect.width;
        renderWall();
    }).observe($('wall'));

    // 瀑布流按屏幕位置移动焦点：左右找相邻列中高度最接近的，上下在同一列内移动
    function moveFocus(key) {
        const list = state.results.map((w) => pieces.get(w.id)).filter((f) => f && f.isConnected);
        if (!list.length) return;
        const cur = pieces.get(state.focusId);
        let target = null;
        if (!cur || !cur.isConnected) target = list[0];
        else {
            const r = cur.querySelector('.piece-open').getBoundingClientRect();
            const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            let best = Infinity;
            list.forEach((fig) => {
                if (fig === cur) return;
                const b = fig.querySelector('.piece-open').getBoundingClientRect();
                const dx = b.left + b.width / 2 - cx, dy = b.top + b.height / 2 - cy;
                let primary, secondary;
                if (key === 'ArrowRight' && dx > r.width / 2) { primary = dx; secondary = Math.abs(dy); }
                else if (key === 'ArrowLeft' && -dx > r.width / 2) { primary = -dx; secondary = Math.abs(dy); }
                else if (key === 'ArrowDown' && dy > 1 && Math.abs(dx) < r.width / 2) { primary = dy; secondary = Math.abs(dx); }
                else if (key === 'ArrowUp' && dy < -1 && Math.abs(dx) < r.width / 2) { primary = -dy; secondary = Math.abs(dx); }
                else return;
                const score = primary + secondary * 2;
                if (score < best) { best = score; target = fig; }
            });
        }
        if (!target) return;
        state.focusId = Number(target.dataset.id);
        target.querySelector('.piece-open').focus();
    }

    $('wall').addEventListener('focusin', (e) => {
        const fig = e.target.closest('.piece');
        if (fig) state.focusId = Number(fig.dataset.id);
    });

    /* ---------------- 看图器 ---------------- */

    const viewer = $('viewer');
    const canvas = $('sv-canvas');
    const img = $('sv-img');
    let chromeTimer = null;

    function mix(hex, base, amount) {
        const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
        const a = p(hex), b = p(base);
        return '#' + a.map((v, i) => Math.round(v * amount + b[i] * (1 - amount)).toString(16).padStart(2, '0')).join('');
    }

    function indexOf(id) { return state.results.findIndex((w) => w.id === id); }

    /*
     * 看图器是模态的：打开期间顶栏、筛选行和作品墙设为 inert，Tab / Shift+Tab 只在看图器里走，
     * 也点不到、输不进背景（不会在编辑时从背后改动搜索或筛选）。候选弹层、确认框、提示条都直接挂在 body 下，不受影响。
     */
    const background = () => document.querySelectorAll('body > .masthead, body > .filterbar, body > .wall-wrap');
    function setBackgroundInert(on) {
        background().forEach((node) => { node.inert = on; });
    }

    function openViewer(id, { edit = false } = {}) {
        // 打开前还在排队的搜索（停顿后才提交的那次）作废，不在看图 / 编辑期间改掉结果
        filters('cancelPendingSearch');
        filters('closePicker');
        state.viewer = { id, page: 0, info: false, scale: 1, fit: true, tx: 0, ty: 0, anchor: null };
        viewer.hidden = false;
        setBackgroundInert(true);
        document.body.classList.add('is-locked');
        viewer.focus({ preventScroll: true });
        showChrome();
        showWork(id, fx('startPage', id) || 0);
        fx('viewerOpened', id);
        if (edit) editor('open', id);
    }

    // 返回 false 表示用户选择留下继续编辑（或正在保存）；force 用于换查询等不可拒绝的场合
    async function closeViewer({ force = false } = {}) {
        if (!state.viewer) return true;
        if (force) editor('close');
        else if (!(await leaveEditor())) return false;
        if (!state.viewer) return true;
        const id = state.viewer.id;
        fx('viewerClosing', id);
        state.viewer = null;
        viewer.hidden = true;
        viewer.style.setProperty('--info', '0px');
        setNotice(null);
        document.body.classList.remove('is-locked');
        setBackgroundInert(false);
        const fig = pieces.get(id);
        if (fig && fig.isConnected) { fig.scrollIntoView({ block: 'nearest' }); fig.querySelector('.piece-open').focus({ preventScroll: true }); }
        return true;
    }

    /*
     * 序号与上一件 / 下一件。编辑保存后这件作品可能已不在当前结果中：序号显示“—”，
     * anchor 记着它原来的位置，上一件 / 下一件从那里接着翻，不会跳回开头。
     */
    function updatePosition() {
        const v = state.viewer;
        if (!v) return;
        const i = indexOf(v.id);
        const total = A.total() ?? state.results.length;
        const num = $('sv-num');
        if (i >= 0) {
            v.anchor = null;
            $('sv-prev').disabled = i <= 0;
            $('sv-next').disabled = i >= state.results.length - 1 && !A.hasMore();
            num.innerHTML = `${String(i + 1).padStart(2, '0')}<small>/ ${total}</small>`;
            num.title = '';
        } else {
            const k = v.anchor ?? 0;
            $('sv-prev').disabled = k <= 0;
            $('sv-next').disabled = k >= state.results.length && !A.hasMore();
            num.innerHTML = `—<small>/ ${total}</small>`;
            num.title = '这件作品已不在当前的搜索或筛选结果中';
        }
    }

    function showWork(id, page = 0) {
        fx('settle');
        const v = state.viewer;
        const work = A.get(id);
        if (v.id !== id) v.anchor = null;
        v.id = id;
        viewer.style.setProperty('--tint', mix(work.pages[0].tone || '#d8d3c8', '#121110', 0.32));
        updatePosition();
        const pagesBox = $('sv-pages');
        pagesBox.innerHTML = '';
        if (work.pages.length > 1) {
            work.pages.forEach((p, n) => {
                const b = el('button');
                b.type = 'button';
                b.setAttribute('aria-label', `第 ${n + 1} 页`);
                b.innerHTML = '<img alt="">';
                b.firstChild.src = p.src;
                b.addEventListener('click', () => showPage(n));
                pagesBox.appendChild(b);
            });
        }
        renderCaption();
        showPage(page);
        A.loadDetail(id);
        if (v.info) renderInfo();
    }

    function renderCaption() {
        const work = A.get(state.viewer.id);
        $('sv-title').textContent = A.displayTitle(work);
        const by = $('sv-by');
        by.innerHTML = '';
        by.append(A.authorName(work) || '未设置作者');
        const tags = work.detail ? work.detail.tags : [];
        if (tags.length) by.append('　·　' + tags.map((t) => t.name).join('、'));
        if (work.createdAt) by.append('　·　' + A.relativeDay(work.createdAt) + '导入');
        const source = $('sv-source');
        const url = work.detail && work.detail.sourceUrl;
        source.href = url || '#';
        source.setAttribute('aria-disabled', String(!url));
    }

    function setNotice(text, link) {
        const n = $('sv-notice');
        n.hidden = !text;
        n.innerHTML = '';
        if (!text) return;
        n.appendChild(el('span', null, text));
        if (link) {
            const a = el('a', null, link.label);
            a.href = link.href;
            n.appendChild(a);
        }
    }

    // 先用已缓存的缩略图按原比例铺满，再换成原图并按真实尺寸适应窗口
    function showPage(n) {
        const v = state.viewer;
        const work = A.get(v.id);
        const p = work.pages[n];
        v.page = n;
        setNotice(null);
        if (p.video) {
            setNotice('视频暂不支持在新版图库预览。', { label: '在旧图库打开 →', href: `/detail.html?id=${encodeURIComponent(work.id)}` });
        }
        const placeDims = (w, h) => {
            v.w = w;
            v.h = h;
            img.style.width = w + 'px';
            img.style.height = h + 'px';
        };
        if (p.fullWidth) {
            img.src = p.full;
            placeDims(p.fullWidth, p.fullHeight);
        } else {
            if (!p.width) A.measure(p).then(() => { if (state.viewer === v && v.page === n && !p.fullWidth) { placeDims(4000, 4000 * p.height / p.width); fitImage(); } });
            img.src = p.src;
            // 原图尺寸未知时按缩略图比例放大到足够大，适应窗口后与原图一致；原图载入后再校正
            const ratio = p.width ? p.height / p.width : 1.25;
            placeDims(4000, 4000 * ratio);
            if (p.full) {
                A.loadFull(p).then((ok) => {
                    if (state.viewer !== v || v.id !== work.id || v.page !== n) return;
                    if (!ok) { setNotice('原图无法载入，正在显示缩略图。'); return; }
                    img.src = p.full;
                    placeDims(p.fullWidth, p.fullHeight);
                    v.fit ? fitImage() : (clampPan(), applyTransform(false));
                    if (v.info) renderInfo();
                });
            }
        }
        fx('pageShown');
        [...$('sv-pages').children].forEach((b, i) => b.setAttribute('aria-current', String(i === n)));
        $('sv-pos').textContent = work.pages.length > 1 ? `第 ${n + 1} / ${work.pages.length} 页` : '';
        requestAnimationFrame(fitImage);
    }

    function fitScale() {
        const v = state.viewer;
        return Math.min((canvas.clientWidth - 64) / v.w, (canvas.clientHeight - 24) / v.h, 1);
    }

    function fitImage() {
        const v = state.viewer;
        if (!v) return;
        v.fit = true;
        v.scale = fitScale();
        v.tx = (canvas.clientWidth - v.w * v.scale) / 2;
        v.ty = (canvas.clientHeight - v.h * v.scale) / 2;
        applyTransform(false);
    }

    function clampPan() {
        const v = state.viewer;
        const cw = canvas.clientWidth, ch = canvas.clientHeight, iw = v.w * v.scale, ih = v.h * v.scale;
        v.tx = iw <= cw ? (cw - iw) / 2 : Math.min(0, Math.max(cw - iw, v.tx));
        v.ty = ih <= ch ? (ch - ih) / 2 : Math.min(0, Math.max(ch - ih, v.ty));
    }

    function zoomTo(scale, cx = canvas.clientWidth / 2, cy = canvas.clientHeight / 2) {
        const v = state.viewer;
        const next = Math.max(fitScale() * 0.5, Math.min(8, scale));
        v.tx = cx - (cx - v.tx) * (next / v.scale);
        v.ty = cy - (cy - v.ty) * (next / v.scale);
        v.scale = next;
        v.fit = false;
        clampPan();
        applyTransform(true);
    }

    function applyTransform(announce) {
        const v = state.viewer;
        img.style.transform = `translate(${v.tx}px, ${v.ty}px) scale(${v.scale})`;
        canvas.classList.toggle('can-pan', v.w * v.scale > canvas.clientWidth + 1 || v.h * v.scale > canvas.clientHeight + 1);
        $('sv-zoom').textContent = v.fit ? '' : Math.round(v.scale * 100) + '%';
        if (announce) showChrome();
    }

    // 翻到已载入列表的末尾时，先载入下一页再前进；只在当前查询的结果里翻，查询换了就停下
    // 切到另一件作品前先过编辑器的“离开”确认；当前作品已移出结果时从原位置接着翻
    async function step(delta) {
        const v = state.viewer;
        const gen = A.generation();
        let i = indexOf(v.id);
        if (i < 0 && v.anchor != null) i = delta > 0 ? v.anchor - 1 : v.anchor;
        i += delta;
        if (i < 0) return;
        if (i >= state.results.length && A.hasMore()) {
            await A.loadMore();
            if (state.viewer !== v || A.generation() !== gen) return;
        }
        const target = state.results[i];
        if (!target) return;
        if (!(await leaveEditor()) || state.viewer !== v) return;
        fx('navigate', delta);
        showWork(target.id);
    }

    function stepPage(delta) {
        const v = state.viewer;
        const next = v.page + delta;
        if (next >= 0 && next < A.get(v.id).pages.length) { fx('navigate', delta); showPage(next); }
        else step(delta);
    }

    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        fx('panStart');
        const r = canvas.getBoundingClientRect();
        zoomTo(state.viewer.scale * Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
    }, { passive: false });
    canvas.addEventListener('dblclick', (e) => {
        const r = canvas.getBoundingClientRect();
        state.viewer.fit ? zoomTo(1, e.clientX - r.left, e.clientY - r.top) : fitImage();
    });
    let pan = null;
    canvas.addEventListener('pointerdown', (e) => {
        if (e.button !== 0 || !canvas.classList.contains('can-pan')) return;
        fx('panStart');
        pan = { x: e.clientX, y: e.clientY, tx: state.viewer.tx, ty: state.viewer.ty };
        canvas.setPointerCapture(e.pointerId);
        canvas.classList.add('is-panning');
    });
    canvas.addEventListener('pointermove', (e) => {
        if (!pan) return;
        const v = state.viewer;
        v.tx = pan.tx + e.clientX - pan.x;
        v.ty = pan.ty + e.clientY - pan.y;
        clampPan();
        applyTransform(false);
        fx('panMove', e);
    });
    const endPan = () => {
        if (pan) fx('panEnd');
        pan = null;
        canvas.classList.remove('is-panning');
    };
    canvas.addEventListener('pointerup', endPan);
    canvas.addEventListener('pointercancel', endPan);

    // 鼠标静止 2.5 秒后隐藏界面，只留作品；信息 / 菜单打开时保持显示
    function showChrome() {
        viewer.classList.add('chrome-on');
        clearTimeout(chromeTimer);
        chromeTimer = setTimeout(() => {
            const v = state.viewer;
            if (!v || v.info || editor('isOpen') || !$('sv-menu').hidden) return;
            if (viewer.querySelector('.sv-top:hover, .sv-caption:hover')) return;
            viewer.classList.remove('chrome-on');
        }, 2500);
    }
    viewer.addEventListener('mousemove', showChrome);

    function setInfo(open) {
        const v = state.viewer;
        v.info = open;
        $('sv-info').hidden = !open;
        $('sv-info-toggle').setAttribute('aria-pressed', String(open));
        viewer.style.setProperty('--info', open ? 'min(300px, 40vh)' : '0px');
        if (open) renderInfo();
        showChrome();
    }

    function renderInfo() {
        const work = A.get(state.viewer.id);
        const box = $('sv-info');
        box.innerHTML = '';
        const col = () => box.appendChild(el('div'));
        const section = (parent, title) => {
            const s = el('section');
            s.appendChild(el('h3', null, title));
            parent.appendChild(s);
            return s;
        };
        const c1 = col(), c2 = col(), c3 = col();
        const detail = work.detail;
        const pending = !detail && !work.detailError;

        // 作者和标签可以点：回到图库，只看这位作者 / 这个标签
        const filterButton = (cls, label, kind, value) => {
            const b = el('button', cls, label);
            b.type = 'button';
            b.title = `回到图库，只看“${label}”`;
            b.addEventListener('click', async () => { if (await closeViewer()) filters('only', kind, value); });
            return b;
        };
        const sa = section(c1, '作者');
        if (work.author) {
            sa.appendChild(filterButton('info-author', A.authorName(work), 'author', work.author));
            if (work.author.xUsername) sa.appendChild(el('p', 'info-handle', '@' + work.author.xUsername));
        } else sa.appendChild(el('p', 'info-empty', '未设置'));

        const st = section(c1, '标签');
        const sn = section(c2, '备注');
        const ss = section(c2, '来源');
        if (work.detailError) {
            const p = el('p', 'info-empty', '详情载入失败：' + work.detailError.message + '　');
            const retry = el('button', 'link', '重试');
            retry.type = 'button';
            retry.addEventListener('click', () => { A.loadDetail(work.id); renderInfo(); });
            p.appendChild(retry);
            st.appendChild(p);
        } else if (pending) {
            st.appendChild(el('p', 'info-empty', '正在载入…'));
        } else if (detail.tags.length) {
            const row = el('div', 'info-tags');
            detail.tags.forEach((t) => row.appendChild(filterButton(null, t.name, 'tag', t)));
            st.appendChild(row);
        } else st.appendChild(el('p', 'info-empty', '还没有标签'));
        if (detail) {
            sn.appendChild(el('p', detail.note ? 'info-note' : 'info-empty', detail.note || '没有备注'));
            if (detail.sourceUrl) {
                const a = el('a', 'info-source', detail.sourceUrl);
                a.href = detail.sourceUrl;
                a.target = '_blank';
                a.rel = 'noreferrer';
                ss.appendChild(a);
            } else ss.appendChild(el('p', 'info-empty', '没有来源链接'));
        } else {
            sn.appendChild(el('p', 'info-empty', pending ? '正在载入…' : '—'));
            ss.appendChild(el('p', 'info-empty', pending ? '正在载入…' : '—'));
        }

        const sf = section(c3, '文件');
        const p = work.pages[state.viewer.page];
        const dl = el('dl', 'info-files');
        const sizes = work.pages.map((x) => x.size);
        const sum = sizes.every((s) => s != null) ? sizes.reduce((s, x) => s + x, 0) : null;
        [['页数', `${work.pages.length} 页`],
            ['格式', (p.format || '—').toUpperCase()],
            ['尺寸', p.fullWidth ? `${p.fullWidth} × ${p.fullHeight}` : '—'],
            ['大小', A.formatSize(sum)],
            ['文件', p.originalFilename || '—'],
            ['导入', A.formatDate(work.createdAt)]]
            .forEach(([k, val]) => { dl.appendChild(el('dt', null, k)); dl.appendChild(el('dd', null, val)); });
        sf.appendChild(dl);
    }

    A.subscribe((event) => {
        if (event.type === 'reset') {
            // 换了查询：看图器不再翻旧结果；旧作品墙先变淡、不可点，等新结果第一页到了再整体换掉（动效层据此过渡）。
            // 看图器开着时换查询只来自“点作者 / 标签”，那条路径已先经过编辑器的离开确认，这里直接关闭。
            closeViewer({ force: true });
            state.focusId = null;
            const wall = $('wall');
            wall.classList.add('is-refreshing');
            wall.setAttribute('aria-busy', 'true');
            if (window.scrollY > 0) window.scrollTo({ top: 0 });
            renderStatus();
        } else if (event.type === 'append') {
            renderWall();
            updatePosition();
        } else if (event.type === 'refresh') {
            // 编辑保存后按服务端结果重读了已载入的列表：看图器停在已移出结果的作品上时记下它原来的位置
            const v = state.viewer;
            const gone = v && event.removed.find((r) => r.id === v.id);
            if (gone) v.anchor = gone.index;
            renderWall();
            updatePosition();
        } else if (event.type === 'updated') {
            const work = A.get(event.id);
            if (work && pieces.has(event.id)) pieceFor(work);
            if (state.viewer && state.viewer.id === event.id) {
                renderCaption();
                if (state.viewer.info) renderInfo();
            }
            updatePosition();
            renderStatus();
        } else if (event.type === 'error') {
            if (!state.results.length) renderWall();
            else renderStatus();
        } else if (event.type === 'loading') {
            renderStatus();
        } else if (event.type === 'detail' && state.viewer && state.viewer.id === event.id) {
            renderCaption();
            if (state.viewer.info) renderInfo();
        }
    });

    $('sv-back').addEventListener('click', () => closeViewer());
    $('sv-edit-toggle').addEventListener('click', () => { if (state.viewer) editor('open', state.viewer.id); });
    $('sv-prev').addEventListener('click', () => step(-1));
    $('sv-next').addEventListener('click', () => step(1));
    $('sv-info-toggle').addEventListener('click', () => setInfo(!state.viewer.info));
    $('sv-more').addEventListener('click', () => setMenu($('sv-menu').hidden));
    $('sv-fit').addEventListener('click', () => { setMenu(false); fitImage(); });
    $('sv-actual').addEventListener('click', () => {
        setMenu(false);
        const v = state.viewer;
        const p = A.get(v.id).pages[v.page];
        zoomTo(p.fullWidth ? 1 : fitScale());
    });
    $('sv-original').addEventListener('click', (e) => {
        const v = state.viewer;
        const p = A.get(v.id).pages[v.page];
        if (!p.full) { e.preventDefault(); return; }
        e.currentTarget.href = p.full;
        setMenu(false);
    });
    $('sv-source').addEventListener('click', (e) => {
        if (e.currentTarget.getAttribute('aria-disabled') === 'true') e.preventDefault();
        setMenu(false);
    });
    document.addEventListener('mousedown', (e) => { if (!$('sv-menu').hidden && !e.target.closest('.sv-more')) setMenu(false); });
    function setMenu(open) {
        $('sv-menu').hidden = !open;
        $('sv-more').setAttribute('aria-expanded', String(open));
    }
    new ResizeObserver(() => {
        if (!state.viewer || !state.viewer.w) return;
        state.viewer.fit ? fitImage() : (clampPan(), applyTransform(false));
    }).observe(canvas);

    // 未接入的入口：按钮保持禁用；即使通过快捷键触发，也只说明尚未接入，不做任何假操作
    document.querySelectorAll('[data-not-yet]').forEach((b) => {
        b.disabled = true;
        b.title = b.dataset.notYet || NOT_YET;
    });

    /* ---------------- 键盘 ---------------- */

    document.addEventListener('keydown', (e) => {
        if (!$('shortcuts').hidden) {
            if (e.key === 'Escape' || e.key === '?') { e.preventDefault(); $('shortcuts').hidden = true; }
            return;
        }
        // 放弃修改的确认框、编辑面板、导入面板里的按键由它们自己处理，不触发图库快捷键
        if (editor('isConfirming')) return;
        if (importer('isOpen')) {
            if (e.key === 'Escape') { e.preventDefault(); importer('close'); }
            return;
        }
        const typing = e.target instanceof Element && e.target.matches('input, textarea, select');
        if (typing) return;
        if (e.target instanceof Element && e.target.closest('#sv-editor')) return;
        // 作者 / 标签弹层开着而焦点不在它的输入框（例如刚点了筛选按钮）：Esc 关弹层，其余按键不触发作品墙快捷键
        if (filters('isPickerOpen')) {
            if (e.key === 'Escape') { e.preventDefault(); filters('closePicker', true); }
            return;
        }
        const ctrl = e.ctrlKey || e.metaKey;
        const notYet = () => { e.preventDefault(); toast(NOT_YET); };

        const v = state.viewer;
        if (v) {
            if (e.key === 'Escape') {
                if (!$('sv-menu').hidden) setMenu(false);
                else if (editor('isOpen')) editor('requestLeave');
                else if (v.info) setInfo(false);
                else closeViewer();
                return;
            }
            switch (e.key) {
                case 'ArrowLeft': step(-1); break;
                case 'ArrowRight': step(1); break;
                case 'PageUp': case 'ArrowUp': e.preventDefault(); stepPage(-1); break;
                case 'PageDown': case 'ArrowDown': e.preventDefault(); stepPage(1); break;
                case '+': case '=': zoomTo(v.scale * 1.25); break;
                case '-': zoomTo(v.scale / 1.25); break;
                case '0': fitImage(); break;
                case '1': zoomTo(1); break;
                case 'i': case 'I': setInfo(!v.info); break;
                case 'e': case 'E': e.preventDefault(); editor('open', v.id); break;
                case 'Delete': notYet(); break;
                case '?': openShortcuts(); break;
                default: return;
            }
            showChrome();
            return;
        }

        if (e.key === '?') { openShortcuts(); return; }
        if (e.key === '/' && !ctrl) { e.preventDefault(); $('q').focus(); $('q').select(); return; }
        if (ctrl && e.key.toLowerCase() === 'o') { e.preventDefault(); importer('open', { pick: true }); return; }
        if (ctrl && e.key.toLowerCase() === 'a') { notYet(); return; }
        const active = document.activeElement;
        const onPiece = !active || active === document.body || active.classList.contains('piece-open');
        const activePiece = active && active.closest ? active.closest('.piece') : null;
        if (activePiece) state.focusId = Number(activePiece.dataset.id);
        const focus = state.focusId != null && A.listed(state.focusId) ? state.focusId : null;
        if (e.key === 'Enter' && !onPiece) return;
        switch (e.key) {
            case 'ArrowLeft': case 'ArrowRight': case 'ArrowUp': case 'ArrowDown':
                e.preventDefault();
                moveFocus(e.key);
                break;
            case 'Enter': if (focus != null) { e.preventDefault(); openViewer(focus); } break;
            case 'e': case 'E':
                if (focus != null) { e.preventDefault(); openViewer(focus, { edit: true }); }
                else toast('先用方向键或鼠标选中一件作品，再按 E 编辑');
                break;
            case 'Delete': notYet(); break;
            default: return;
        }
    });

    function openShortcuts() {
        $('shortcuts').hidden = false;
        $('shortcuts-close').focus();
    }
    $('help-open').addEventListener('click', openShortcuts);
    $('shortcuts-close').addEventListener('click', () => { $('shortcuts').hidden = true; });
    $('shortcuts').addEventListener('mousedown', (e) => { if (e.target === $('shortcuts')) $('shortcuts').hidden = true; });

    // 供 fx.js 读取看图器状态、复用平移约束
    /*
     * 搜索、筛选要换查询之前先问这里：看图器开着时先关闭它（有未保存的编辑会先确认，保存中不放行），
     * 返回 false 时调用方不改查询。这样 setQuery 只在确认离开之后才清空列表状态。
     */
    const prepareQueryChange = async () => !state.viewer || closeViewer();

    window.GalleryApi = {
        state, pieces, canvas, img, viewer, clampPan, applyTransform, fitImage, renderWall, renderStatus, toast, showChrome,
        prepareQueryChange, setBackgroundInert, openViewer,
        isViewerOpen: () => !!state.viewer,
        markFresh: (ids) => ids.forEach((id) => freshIds.add(id)),
        // 筛选时列表总数不是整库数量：导入后由导入面板读一次整库数量，更新“全部”旁的数字
        setLibraryTotal: (n) => { libraryTotal = n; renderStatus(); }
    };

    renderWall();
    // 页面地址里带着条件（刷新、或从别处带条件打开）时直接按条件载入
    A.setQuery(A.queryFromSearch(location.search));
})();
