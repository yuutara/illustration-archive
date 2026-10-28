/*
 * C 图库 · 搜索与作者 / 标签筛选（只读）
 *
 * 条件只有三组：关键词、作者（可多选）、标签（可多选）。作者之间、标签之间是“或”，三组之间是“且”，
 * 全部交给服务端在整个图库上筛选（archive-client.js 的 setQuery），不在已载入的作品里过滤。
 *
 * - 搜索框边输入边搜索（停顿 300ms）；中文输入法拼写、选词期间不搜索，选词用的 Enter / Esc 也不当作命令。
 * - 作者 / 标签弹层向服务端按关键词搜索候选，滚到底继续往下翻，找得到前 20 条之外的项。
 * - 已选条件显示为筛选条，可逐个去掉或一次清除；条件同步到页面地址，刷新后保留。
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

    const SEARCH_DELAY = 300;
    const KINDS = {
        author: { label: '作者', key: 'authorIds', button: 'author-filter', placeholder: '搜索作者名或 @账号', hint: '可多选：满足其中任一位作者即可' },
        tag: { label: '标签', key: 'tagIds', button: 'tag-filter', placeholder: '搜索标签', hint: '可多选：带有其中任一个标签即可' }
    };

    /* ---------------- 条件与名称 ---------------- */

    // id → 显示名：来自候选列表、作品上的作者 / 标签，以及列表响应里服务端回带的 filters
    const names = { author: new Map(), tag: new Map() };

    function learn(kind, item) {
        if (!item || item.id == null) return;
        if (kind === 'author') {
            const handle = item.xUsername ? '@' + item.xUsername : '';
            names.author.set(item.id, { label: item.displayName || handle || `作者 #${item.id}`, meta: item.displayName ? handle : '' });
        } else {
            names.tag.set(item.id, { label: item.name, meta: '' });
        }
    }

    function learnFromResponse() {
        const f = A.filters();
        if (!f) return;
        (f.authors || []).forEach((a) => learn('author', a));
        (f.tags || []).forEach((t) => learn('tag', t));
    }

    function nameOf(kind, id) {
        const known = names[kind].get(id);
        if (known) return known.label;
        // 服务端已经回带了条件、里面却没有这个 id：它已被删除（这一组照常按 id 过滤，结果里不会有它）
        return A.filters() ? `已不存在的${KINDS[kind].label} #${id}` : `${KINDS[kind].label} #${id}`;
    }

    function apply(change) {
        A.setQuery({ ...A.query(), ...change });
    }

    function only(kind, item) {
        learn(kind, item);
        closePicker();
        apply({ [KINDS[kind].key]: [item.id] });
    }

    function clearAll() {
        clearTimeout(searchTimer);
        input.value = '';
        syncSearchBox();
        closePicker();
        apply({ q: '', authorIds: [], tagIds: [] });
    }

    function syncUrl() {
        const url = location.pathname + A.searchOf(A.query()) + location.hash;
        try {
            if (url !== location.pathname + location.search + location.hash) history.replaceState(history.state, '', url);
        } catch (e) { /* 地址不同步不影响浏览 */ }
    }

    /* ---------------- 搜索框 ---------------- */

    const input = $('q');
    const clearButton = $('q-clear');
    let composing = false;
    let searchTimer = 0;

    function syncSearchBox() {
        clearButton.hidden = !input.value;
        $('omnibox').classList.toggle('has-value', !!input.value);
    }

    function commitSearch() {
        clearTimeout(searchTimer);
        apply({ q: input.value });
    }

    function scheduleSearch() {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(commitSearch, SEARCH_DELAY);
    }

    input.addEventListener('compositionstart', () => { composing = true; clearTimeout(searchTimer); });
    input.addEventListener('compositionend', () => { composing = false; syncSearchBox(); scheduleSearch(); });
    input.addEventListener('input', (e) => {
        syncSearchBox();
        if (composing || e.isComposing) return;
        scheduleSearch();
    });
    input.addEventListener('keydown', (e) => {
        if (composing || e.isComposing || e.keyCode === 229) return;
        if (e.key === 'Enter') {
            e.preventDefault();
            commitSearch();
        } else if (e.key === 'Escape') {
            e.preventDefault();
            if (input.value) { input.value = ''; syncSearchBox(); commitSearch(); } else input.blur();
        }
    });
    clearButton.addEventListener('click', () => {
        input.value = '';
        syncSearchBox();
        commitSearch();
        input.focus();
    });

    /* ---------------- 已选条件 ---------------- */

    function renderSummary() {
        const q = A.query();
        const box = $('active-filters');
        box.innerHTML = '';
        const chips = [];
        if (q.q) {
            chips.push({ group: 'q', kind: '搜索', label: `“${q.q}”`, remove: () => { input.value = ''; syncSearchBox(); apply({ q: '' }); } });
        }
        Object.entries(KINDS).forEach(([kind, cfg]) => {
            q[cfg.key].forEach((id) => chips.push({
                group: kind, kind: cfg.label, label: nameOf(kind, id),
                remove: () => apply({ [cfg.key]: A.query()[cfg.key].filter((x) => x !== id) })
            }));
        });
        chips.forEach((chip, i) => {
            // 同一组里相邻两个条件之间写明“或”，组与组之间是“且”（不另外标）
            if (i > 0 && chips[i - 1].group === chip.group) box.appendChild(el('span', 'fjoin', '或'));
            const node = el('span', 'fchip');
            node.appendChild(el('small', null, chip.kind));
            node.appendChild(el('span', 'fchip-label', chip.label));
            const x = el('button');
            x.type = 'button';
            x.setAttribute('aria-label', `去掉条件：${chip.kind} ${chip.label}`);
            x.title = '去掉这个条件';
            x.innerHTML = icon('close');
            x.addEventListener('click', chip.remove);
            node.appendChild(x);
            box.appendChild(node);
        });
        if (chips.length > 1) {
            const all = el('button', 'clear-all', '清除全部');
            all.type = 'button';
            all.addEventListener('click', clearAll);
            box.appendChild(all);
        }
        if (chips.length) {
            const total = A.total();
            const text = A.lastError() && total == null ? '' : total == null ? '查找中…' : `找到 ${total} 件`;
            box.appendChild(el('span', 'result-count', text));
        }
        Object.entries(KINDS).forEach(([, cfg]) => {
            const b = $(cfg.button);
            const n = q[cfg.key].length;
            b.classList.toggle('has-value', n > 0);
            b.firstChild.textContent = n ? `${cfg.label} · ${n} ` : `${cfg.label} `;
        });
    }

    /* ---------------- 作者 / 标签弹层 ---------------- */

    let picker = null;

    function position(p) {
        const r = p.anchor.getBoundingClientRect();
        const w = p.root.offsetWidth, h = p.root.offsetHeight;
        let top = r.bottom + 6;
        if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6);
        p.root.style.left = Math.max(8, Math.min(r.left, window.innerWidth - w - 8)) + 'px';
        p.root.style.top = top + 'px';
    }

    function closePicker(restoreFocus = false) {
        if (!picker) return;
        const p = picker;
        picker = null;
        clearTimeout(p.timer);
        document.removeEventListener('mousedown', p.onOutside, true);
        p.root.remove();
        p.anchor.setAttribute('aria-expanded', 'false');
        if (restoreFocus) p.anchor.focus();
    }

    function openPicker(kind) {
        if (picker && picker.kind === kind) { closePicker(); return; }
        closePicker();
        const cfg = KINDS[kind];
        const anchor = $(cfg.button);
        const root = el('div', 'pk');
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-label', `按${cfg.label}筛选`);
        root.innerHTML = '<input class="pk-input" type="search" autocomplete="off" spellcheck="false" maxlength="100">'
            + '<p class="pk-hint"></p><ul class="pk-list" role="listbox" aria-multiselectable="true"></ul>';
        const p = {
            kind, cfg, anchor, root,
            input: root.querySelector('.pk-input'),
            list: root.querySelector('.pk-list'),
            search: A.facetSearch(kind),
            // 打开（或换关键词）时已选的项排在最前；勾选 / 取消只改勾，不让行在鼠标下跳动
            pinned: [],
            rows: [], active: 0, composing: false, timer: 0
        };
        p.input.placeholder = cfg.placeholder;
        p.input.setAttribute('aria-label', cfg.placeholder);
        root.querySelector('.pk-hint').textContent = cfg.hint;
        p.onOutside = (e) => { if (!root.contains(e.target) && !anchor.contains(e.target)) closePicker(); };
        document.addEventListener('mousedown', p.onOutside, true);
        picker = p;
        document.body.appendChild(root);
        anchor.setAttribute('aria-expanded', 'true');
        wirePicker(p);
        runFacetSearch(p, '');
        position(p);
        p.input.focus();
    }

    function runFacetSearch(p, keyword) {
        p.pinned = keyword.trim() ? [] : [...A.query()[p.cfg.key]];
        p.active = 0;
        const pending = p.search.search(keyword);
        renderPicker(p);
        pending.then(() => { if (picker === p) renderPicker(p); });
    }

    function loadMoreCandidates(p) {
        const s = p.search.state;
        if (s.loading || s.done || s.error) return;
        const pending = p.search.more();
        renderPicker(p);
        pending.then(() => { if (picker === p) renderPicker(p); });
    }

    function renderPicker(p) {
        const s = p.search.state;
        const selected = new Set(A.query()[p.cfg.key]);
        const pinned = new Set(p.pinned);
        const rowOf = (id) => ({ id, label: nameOf(p.kind, id), meta: (names[p.kind].get(id) || {}).meta || '' });
        s.items.forEach((item) => learn(p.kind, item));
        p.rows = [...p.pinned.map(rowOf), ...s.items.filter((item) => !pinned.has(item.id)).map((item) => rowOf(item.id))];
        p.active = Math.min(p.active, Math.max(0, p.rows.length - 1));

        const list = p.list;
        const scroll = list.scrollTop;
        list.innerHTML = '';
        const section = (text) => list.appendChild(el('li', 'pk-section', text)).setAttribute('role', 'presentation');
        if (p.pinned.length) section('已选');
        p.rows.forEach((row, i) => {
            if (p.pinned.length && i === p.pinned.length && p.rows.length > i) section(`全部${p.cfg.label}`);
            const li = el('li', 'pk-item');
            li.setAttribute('role', 'option');
            const on = selected.has(row.id);
            li.setAttribute('aria-selected', String(on));
            li.classList.toggle('is-selected', on);
            li.classList.toggle('is-active', i === p.active);
            li.innerHTML = '<span class="pk-check" aria-hidden="true"></span><span class="pk-label"></span><span class="pk-meta"></span>';
            li.querySelector('.pk-label').textContent = row.label;
            li.querySelector('.pk-meta').textContent = row.meta;
            li.addEventListener('mousedown', (e) => e.preventDefault());
            li.addEventListener('click', () => { p.active = i; toggle(p, row.id); });
            li.addEventListener('mousemove', () => { if (p.active !== i) { p.active = i; markActive(p); } });
            list.appendChild(li);
        });

        if (s.error) {
            const li = el('li', 'pk-status is-error', `载入失败：${s.error.message}　`);
            const retry = el('button', 'link', '重试');
            retry.type = 'button';
            retry.addEventListener('mousedown', (e) => e.preventDefault());
            retry.addEventListener('click', () => {
                const pending = p.search.more();
                renderPicker(p);
                pending.then(() => { if (picker === p) renderPicker(p); });
            });
            li.appendChild(retry);
            list.appendChild(li);
        } else if (s.loading) {
            list.appendChild(el('li', 'pk-status is-loading', s.items.length ? '正在载入更多…' : '正在查找…'));
        } else if (!p.rows.length) {
            list.appendChild(el('li', 'pk-empty', s.keyword ? `没有匹配“${s.keyword}”的${p.cfg.label}` : `还没有${p.cfg.label}`));
        } else if (!s.done) {
            const li = el('li', 'pk-status');
            const more = el('button', 'link', '载入更多');
            more.type = 'button';
            more.addEventListener('mousedown', (e) => e.preventDefault());
            more.addEventListener('click', () => loadMoreCandidates(p));
            li.appendChild(more);
            list.appendChild(li);
        }
        list.scrollTop = scroll;
        // 候选不满一屏、还没到底时继续往下取，保证能滚动
        if (!s.loading && !s.done && !s.error && list.scrollHeight <= list.clientHeight + 4) loadMoreCandidates(p);
    }

    function optionNodes(p) {
        return [...p.list.querySelectorAll('.pk-item')];
    }

    function markActive(p) {
        optionNodes(p).forEach((li, i) => li.classList.toggle('is-active', i === p.active));
        const li = optionNodes(p)[p.active];
        if (li) li.scrollIntoView({ block: 'nearest' });
    }

    function toggle(p, id) {
        const ids = A.query()[p.cfg.key];
        apply({ [p.cfg.key]: ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id] });
        renderPicker(p);
    }

    function wirePicker(p) {
        p.input.addEventListener('compositionstart', () => { p.composing = true; clearTimeout(p.timer); });
        p.input.addEventListener('compositionend', () => {
            p.composing = false;
            clearTimeout(p.timer);
            p.timer = setTimeout(() => runFacetSearch(p, p.input.value), 200);
        });
        p.input.addEventListener('input', (e) => {
            if (p.composing || e.isComposing) return;
            clearTimeout(p.timer);
            p.timer = setTimeout(() => runFacetSearch(p, p.input.value), 200);
        });
        p.input.addEventListener('keydown', (e) => {
            e.stopPropagation();
            if (p.composing || e.isComposing || e.keyCode === 229) return;
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                p.active = Math.min(p.rows.length - 1, p.active + 1);
                markActive(p);
                if (p.active >= p.rows.length - 3) loadMoreCandidates(p);
            } else if (e.key === 'ArrowUp') {
                e.preventDefault();
                p.active = Math.max(0, p.active - 1);
                markActive(p);
            } else if (e.key === 'Enter') {
                e.preventDefault();
                if (p.rows[p.active]) toggle(p, p.rows[p.active].id);
            } else if (e.key === 'Escape') {
                e.preventDefault();
                if (p.input.value) { p.input.value = ''; runFacetSearch(p, ''); } else closePicker(true);
            } else if (e.key === 'Tab') {
                closePicker();
            }
        });
        // 点弹层里的空白、提示或分组标题时焦点留在输入框：方向键、空格、Esc 始终由弹层处理，不落到作品墙
        p.root.addEventListener('mousedown', (e) => { if (e.target !== p.input) e.preventDefault(); });
        p.list.addEventListener('scroll', () => {
            if (p.list.scrollTop + p.list.clientHeight >= p.list.scrollHeight - 48) loadMoreCandidates(p);
        }, { passive: true });
    }

    Object.keys(KINDS).forEach((kind) => {
        $(KINDS[kind].button).addEventListener('click', () => openPicker(kind));
    });
    window.addEventListener('resize', () => { if (picker) position(picker); });
    window.addEventListener('scroll', () => { if (picker) position(picker); }, { passive: true });

    /* ---------------- 跟随查询 ---------------- */

    A.subscribe((event) => {
        if (event.type === 'reset') {
            syncUrl();
            // 条件从别处改变（去掉筛选条、清除全部）时让搜索框跟上；正在输入时不打断
            if (document.activeElement !== input) {
                input.value = A.query().q;
                syncSearchBox();
            }
            renderSummary();
        } else if (event.type === 'append') {
            learnFromResponse();
            renderSummary();
            if (picker) renderPicker(picker);
        } else if (event.type === 'error') {
            renderSummary();
        }
    });

    input.value = A.query().q;
    syncSearchBox();
    renderSummary();

    window.CFilters = { only, clearAll, renderSummary, closePicker, isPickerOpen: () => !!picker };
})();
