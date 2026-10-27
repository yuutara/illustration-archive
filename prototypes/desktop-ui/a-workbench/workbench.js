/*
 * 原型 A · 工作台
 * 侧栏筛选 + 等高行网格 + 常驻信息面板。编辑在面板内就地完成、失焦即保存；
 * 支持多选批量改作者 / 标签；看图器复用同一个面板。
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
    const icon = (name, cls = 'icon') => `<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;

    const SCOPE_LABEL = { all: '全部作品', recent: '最近 7 天导入', untidy: '待整理', multipage: '多页作品' };

    const state = {
        text: '', scope: 'all', authorIds: new Set(), tagIds: new Set(), sort: 'newest',
        rowHeight: 200, results: [], rows: [],
        selected: new Set(), focusId: null, anchorId: null,
        inspector: true,
        viewer: null
    };

    let quietInspector = false;
    const tiles = new Map();

    /* ---------------- 数据 → 界面 ---------------- */

    function runQuery({ keepSelection = false } = {}) {
        state.results = A.query({
            text: state.text, scope: state.scope, sort: state.sort,
            authorIds: [...state.authorIds], tagIds: [...state.tagIds]
        });
        if (!keepSelection) {
            const visible = new Set(state.results.map((w) => w.id));
            state.selected.forEach((id) => { if (!visible.has(id)) state.selected.delete(id); });
            if (!visible.has(state.focusId)) state.focusId = [...state.selected][0] ?? null;
        }
        renderSidebar();
        renderSummary();
        layoutGrid();
        if (!quietInspector) renderMainInspector();
    }

    function renderSidebar() {
        const c = A.counts();
        document.querySelectorAll('[data-count]').forEach((n) => { n.textContent = c[n.dataset.count]; });
        document.querySelectorAll('.side-item').forEach((b) => b.classList.toggle('is-active', b.dataset.scope === state.scope));

        renderFacet($('author-list'), $('authors-filter').value, A.authors.map((a) => ({
            id: a.id, label: a.name, title: a.handle ? '@' + a.handle : '', count: c.byAuthor.get(a.id) || 0
        })), state.authorIds);
        renderFacet($('tag-list'), $('tags-filter').value, A.tags.map((t) => ({
            id: t.id, label: t.name, count: c.byTag.get(t.id) || 0
        })), state.tagIds);
        $('authors-clear').hidden = !state.authorIds.size;
        $('tags-clear').hidden = !state.tagIds.size;
    }

    function renderFacet(list, filter, items, activeSet) {
        const q = filter.trim().toLowerCase().replace(/^@/, '');
        list.innerHTML = '';
        items
            .filter((i) => !q || (i.label + ' ' + (i.title || '')).toLowerCase().includes(q))
            .sort((a, b) => (activeSet.has(b.id) - activeSet.has(a.id)) || (b.count - a.count))
            .forEach((item) => {
                const li = el('li');
                const btn = el('button', 'facet-item' + (activeSet.has(item.id) ? ' is-active' : ''));
                btn.type = 'button';
                btn.title = item.title || item.label;
                btn.setAttribute('aria-pressed', String(activeSet.has(item.id)));
                btn.innerHTML = '<span class="facet-name"><span class="facet-box"></span><span></span></span><span class="count"></span>';
                btn.querySelector('.facet-name span:last-child').textContent = item.label;
                btn.querySelector('.count').textContent = item.count;
                btn.addEventListener('click', () => {
                    activeSet.has(item.id) ? activeSet.delete(item.id) : activeSet.add(item.id);
                    runQuery();
                });
                li.appendChild(btn);
                list.appendChild(li);
            });
        if (!list.children.length) list.appendChild(el('li', 'facet-empty', '没有匹配项'));
    }

    function renderSummary() {
        const box = $('summary');
        box.innerHTML = '';
        const total = el('span');
        total.innerHTML = `<strong>${state.results.length}</strong> 件`;
        box.appendChild(total);
        if (state.selected.size > 1) box.appendChild(el('span', null, `· 已选 ${state.selected.size} 件`));

        const chips = [];
        if (state.scope !== 'all') chips.push(['范围', SCOPE_LABEL[state.scope], () => { state.scope = 'all'; }]);
        if (state.text) chips.push(['搜索', state.text, () => { state.text = ''; $('search-input').value = ''; }]);
        state.authorIds.forEach((id) => {
            const a = A.authors.find((x) => x.id === id);
            if (a) chips.push(['作者', a.name, () => state.authorIds.delete(id)]);
        });
        state.tagIds.forEach((id) => {
            const t = A.tags.find((x) => x.id === id);
            if (t) chips.push(['标签', t.name, () => state.tagIds.delete(id)]);
        });
        chips.forEach(([kind, label, clear]) => {
            const chip = el('span', 'chip');
            chip.innerHTML = `<span class="chip-kind">${kind}</span><span></span><button type="button" aria-label="移除条件">${icon('close')}</button>`;
            chip.children[1].textContent = label;
            chip.querySelector('button').addEventListener('click', () => { clear(); runQuery(); });
            box.appendChild(chip);
        });
        if (chips.length > 1) {
            const all = el('button', 'link-btn', '清除全部');
            all.type = 'button';
            all.addEventListener('click', clearAllFilters);
            box.appendChild(all);
        }
    }

    function clearAllFilters() {
        state.scope = 'all';
        state.text = '';
        $('search-input').value = '';
        state.authorIds.clear();
        state.tagIds.clear();
        runQuery();
    }

    /* ---------------- 等高行网格 ---------------- */

    function tileFor(work) {
        let tile = tiles.get(work.id);
        if (!tile) {
            tile = el('div', 'tile');
            tile.dataset.id = work.id;
            tile.setAttribute('role', 'option');
            const img = el('img');
            img.alt = '';
            img.loading = 'lazy';
            img.decoding = 'async';
            tile.appendChild(img);
            tile.appendChild(el('div', 'tile-badges'));
            tile.appendChild(el('div', 'tile-caption'));
            tiles.set(work.id, tile);
        }
        const page = work.pages[0];
        const img = tile.querySelector('img');
        if (img.getAttribute('src') !== page.src) img.src = page.src;
        const author = A.authorOf(work);
        tile.querySelector('.tile-caption').textContent = A.displayTitle(work) + (author ? ' · ' + author.name : '');
        tile.setAttribute('aria-label', A.displayTitle(work));
        const badges = tile.querySelector('.tile-badges');
        badges.innerHTML = '';
        if (work.pages.length > 1) badges.insertAdjacentHTML('beforeend', `<span class="badge">${icon('pages')}${work.pages.length}</span>`);
        if (page.format === 'gif') badges.insertAdjacentHTML('beforeend', '<span class="badge">GIF</span>');
        let dot = tile.querySelector('.untidy-dot');
        if (A.isUntidy(work) && !dot) {
            dot = el('span', 'untidy-dot');
            dot.title = '待整理：缺少作者或标签';
            tile.appendChild(dot);
        } else if (!A.isUntidy(work) && dot) dot.remove();
        if (work.fresh) { tile.classList.add('is-fresh'); delete work.fresh; }
        return tile;
    }

    function layoutGrid() {
        const grid = $('grid');
        const scroll = $('grid-scroll');
        const width = scroll.clientWidth - 24;
        const gap = 6;
        const target = state.rowHeight;
        grid.innerHTML = '';
        state.rows = [];
        $('empty').hidden = state.results.length > 0;
        if (width <= 0) return;

        let row = [], ratioSum = 0;
        const flush = (last) => {
            if (!row.length) return;
            const avail = width - gap * (row.length - 1);
            const height = last ? Math.min(target, avail / ratioSum) : avail / ratioSum;
            const rowEl = el('div', 'row');
            const rowIds = [];
            let x = 0;
            row.forEach((work) => {
                const p = work.pages[0];
                const w = Math.floor((p.width / p.height) * height);
                const tile = tileFor(work);
                tile.style.width = w + 'px';
                tile.style.height = Math.round(height) + 'px';
                rowEl.appendChild(tile);
                rowIds.push({ id: work.id, center: x + w / 2 });
                x += w + gap;
            });
            grid.appendChild(rowEl);
            state.rows.push(rowIds);
            row = [];
            ratioSum = 0;
        };
        state.results.forEach((work) => {
            const p = work.pages[0];
            row.push(work);
            ratioSum += p.width / p.height;
            if (ratioSum * target + gap * (row.length - 1) >= width) flush(false);
        });
        flush(true);
        paintSelection();
    }

    function paintSelection() {
        tiles.forEach((tile, id) => {
            const on = state.selected.has(id);
            tile.classList.toggle('is-selected', on);
            tile.classList.toggle('is-focus', id === state.focusId);
            tile.setAttribute('aria-selected', String(on));
        });
    }

    /* ---------------- 选择 ---------------- */

    function indexOf(id) { return state.results.findIndex((w) => w.id === id); }

    function select(id, { toggle = false, range = false } = {}) {
        if (range && state.anchorId != null) {
            const a = indexOf(state.anchorId), b = indexOf(id);
            if (!toggle) state.selected.clear();
            state.results.slice(Math.min(a, b), Math.max(a, b) + 1).forEach((w) => state.selected.add(w.id));
        } else if (toggle) {
            state.selected.has(id) ? state.selected.delete(id) : state.selected.add(id);
            state.anchorId = id;
        } else {
            state.selected = new Set([id]);
            state.anchorId = id;
        }
        state.focusId = id;
        paintSelection();
        renderSummary();
        renderMainInspector();
        const tile = tiles.get(id);
        if (tile) tile.scrollIntoView({ block: 'nearest' });
    }

    function clearSelection() {
        state.selected.clear();
        state.focusId = null;
        paintSelection();
        renderSummary();
        renderMainInspector();
    }

    function moveFocus(key, extend) {
        if (!state.results.length) return;
        if (state.focusId == null) { select(state.results[0].id); return; }
        let next = null;
        if (key === 'ArrowLeft' || key === 'ArrowRight') {
            const i = indexOf(state.focusId) + (key === 'ArrowRight' ? 1 : -1);
            if (state.results[i]) next = state.results[i].id;
        } else {
            const r = state.rows.findIndex((row) => row.some((c) => c.id === state.focusId));
            const cur = state.rows[r].find((c) => c.id === state.focusId);
            const target = state.rows[r + (key === 'ArrowDown' ? 1 : -1)];
            if (target) next = target.reduce((best, c) => Math.abs(c.center - cur.center) < Math.abs(best.center - cur.center) ? c : best).id;
        }
        if (next != null) select(next, { range: extend });
    }

    /* ---------------- 信息面板 ---------------- */

    function renderMainInspector() {
        if (!state.inspector) return;
        renderInspector($('inspector'), [...state.selected], { inViewer: false });
    }

    function flashSaved(text = '已保存') {
        const s = $('saved');
        s.textContent = '✓ ' + text;
        s.classList.add('is-on');
        clearTimeout(flashSaved.t);
        flashSaved.t = setTimeout(() => s.classList.remove('is-on'), 1600);
    }

    function autosize(t) {
        t.style.height = 'auto';
        t.style.height = t.scrollHeight + 'px';
    }

    function bindField(input, work, field) {
        input.value = work[field] || '';
        let original = input.value;
        if (input.tagName === 'TEXTAREA') {
            requestAnimationFrame(() => autosize(input));
            input.addEventListener('input', () => autosize(input));
        }
        input.addEventListener('keydown', (e) => {
            e.stopPropagation();
            if (e.key === 'Escape') { input.value = original; input.blur(); if (input.tagName === 'TEXTAREA') autosize(input); }
            else if (e.key === 'Enter' && (field === 'title' || field === 'source' || e.ctrlKey)) { e.preventDefault(); input.blur(); }
        });
        input.addEventListener('blur', () => {
            const value = field === 'title' ? input.value.replace(/\s+/g, ' ').trim() : input.value.trim();
            if (value === original) return;
            quietInspector = true;
            A.update(work.id, { [field]: value });
            quietInspector = false;
            original = value;
            input.value = value;
            flashSaved();
        });
    }

    function authorPicker(anchor, ids, rerender) {
        Picker.open(anchor, {
            placeholder: '搜索或新建作者',
            items: () => A.authors.map((a) => ({ id: a.id, label: a.name, meta: a.handle ? '@' + a.handle : '' })),
            selected: () => {
                const set = new Set(ids.map((id) => A.get(id).authorId));
                return set.size === 1 ? set : new Set();
            },
            clearLabel: '不设置作者',
            onSelect: (id) => {
                A.updateMany(ids, { authorId: id });
                flashSaved(ids.length > 1 ? `已更新 ${ids.length} 件` : '已保存');
            },
            onCreate: (name) => A.createAuthor(name).id,
            createText: (q) => `新建作者 “${q}”`,
            onClose: rerender
        });
    }

    function tagPicker(anchor, ids, rerender) {
        Picker.open(anchor, {
            placeholder: '搜索或新建标签',
            multi: true,
            items: () => A.tags.map((t) => ({ id: t.id, label: t.name })),
            selected: () => new Set(A.tags.filter((t) => ids.every((id) => A.get(id).tagIds.includes(t.id))).map((t) => t.id)),
            onSelect: (id) => {
                const onAll = ids.every((wid) => A.get(wid).tagIds.includes(id));
                A.updateMany(ids, onAll ? { removeTagIds: [id] } : { addTagIds: [id] });
                flashSaved(ids.length > 1 ? `已更新 ${ids.length} 件` : '已保存');
                rerender();
            },
            onCreate: (name) => A.createTag(name).id,
            createText: (q) => `新建标签 “${q}”`,
            onClose: rerender
        });
    }

    function renderInspector(container, ids, { inViewer }) {
        const rerender = () => renderInspector(container, ids.filter((id) => A.get(id)), { inViewer });
        container.innerHTML = '';
        const works = ids.map((id) => A.get(id)).filter(Boolean);
        if (!works.length) {
            container.innerHTML = `<div class="insp-empty"><div><p>选择一件作品查看信息</p><p class="insp-hint">Ctrl / Shift + 点击可多选，批量设置作者和标签</p></div></div>`;
            return;
        }
        const box = el('div', 'insp');
        container.appendChild(box);

        if (works.length === 1) {
            const w = works[0];
            if (!inViewer) {
                const preview = el('button', 'insp-preview');
                preview.type = 'button';
                preview.title = '在看图器中打开（Enter）';
                preview.innerHTML = '<img alt="">';
                preview.querySelector('img').src = w.pages[0].src;
                preview.addEventListener('click', () => openViewer(w.id));
                box.appendChild(preview);
            }
            const title = el('textarea', 'insp-title');
            title.rows = 1;
            title.placeholder = '未命名作品';
            title.setAttribute('aria-label', '标题');
            title.dataset.role = 'title';
            bindField(title, w, 'title');
            box.appendChild(title);
        } else {
            const stack = el('div', 'insp-stack');
            works.slice(0, 3).forEach((w, i) => {
                const img = el('img');
                img.src = w.pages[0].src;
                img.style.transform = `translateX(calc(-50% + ${(i - 1) * 26}px)) rotate(${(i - 1) * 5}deg)`;
                img.style.zIndex = 3 - Math.abs(i - 1);
                stack.appendChild(img);
            });
            box.appendChild(stack);
            const head = el('div');
            head.innerHTML = `<div class="insp-title" style="margin:0">已选择 ${works.length} 件作品</div><p class="insp-hint">下面的修改会应用到全部所选作品</p>`;
            box.appendChild(head);
        }

        // 作者
        const authorField = el('div');
        authorField.appendChild(el('div', 'field-label', '作者'));
        const authorBtn = el('button', 'picker-button');
        authorBtn.type = 'button';
        authorBtn.setAttribute('aria-haspopup', 'dialog');
        const authorIds = new Set(works.map((w) => w.authorId));
        if (authorIds.size > 1) {
            authorBtn.classList.add('is-mixed');
            authorBtn.textContent = '多个作者';
        } else {
            const a = A.authorOf(works[0]);
            if (a) authorBtn.innerHTML = `<span></span><span class="handle"></span>`;
            else { authorBtn.classList.add('is-empty'); authorBtn.textContent = '未设置作者'; }
            if (a) {
                authorBtn.children[0].textContent = a.name;
                authorBtn.children[1].textContent = a.handle ? '@' + a.handle : '';
            }
        }
        authorBtn.addEventListener('click', () => authorPicker(authorBtn, works.map((w) => w.id), rerender));
        authorField.appendChild(authorBtn);
        box.appendChild(authorField);

        // 标签：全部拥有的可移除，部分拥有的以描边显示数量
        const tagField = el('div');
        tagField.appendChild(el('div', 'field-label', '标签'));
        const row = el('div', 'tag-row');
        const union = new Map();
        works.forEach((w) => w.tagIds.forEach((id) => union.set(id, (union.get(id) || 0) + 1)));
        [...union.entries()].forEach(([id, n]) => {
            const tag = A.tags.find((t) => t.id === id);
            const chip = el('span', 'tag-chip' + (n < works.length ? ' is-partial' : ''));
            chip.appendChild(el('span', null, tag.name + (n < works.length ? ` ${n}/${works.length}` : '')));
            const x = el('button');
            x.type = 'button';
            x.setAttribute('aria-label', `移除标签 ${tag.name}`);
            x.innerHTML = icon('close');
            x.addEventListener('click', () => {
                A.updateMany(works.map((w) => w.id), { removeTagIds: [id] });
                flashSaved();
                rerender();
            });
            chip.appendChild(x);
            row.appendChild(chip);
        });
        const add = el('button', 'tag-add', '+ 标签');
        add.type = 'button';
        add.dataset.role = 'tag-add';
        add.title = '添加标签（T）';
        add.addEventListener('click', () => tagPicker(add, works.map((w) => w.id), rerender));
        row.appendChild(add);
        tagField.appendChild(row);
        box.appendChild(tagField);

        if (works.length === 1) {
            const w = works[0];
            const sourceField = el('div');
            sourceField.appendChild(el('div', 'field-label', '来源'));
            const sr = el('div', 'source-row');
            const source = el('input', 'field-input');
            source.type = 'url';
            source.placeholder = '添加来源链接';
            source.setAttribute('aria-label', '来源链接');
            bindField(source, w, 'source');
            sr.appendChild(source);
            const open = el('a', 'icon-btn');
            open.href = w.source || '#';
            open.target = '_blank';
            open.rel = 'noreferrer';
            open.title = '在浏览器中打开来源';
            open.innerHTML = icon('link');
            open.hidden = !w.source;
            sr.appendChild(open);
            sourceField.appendChild(sr);
            box.appendChild(sourceField);

            const noteField = el('div');
            noteField.appendChild(el('div', 'field-label', '备注'));
            const note = el('textarea', 'field-input');
            note.rows = 2;
            note.placeholder = '添加备注';
            note.setAttribute('aria-label', '备注');
            bindField(note, w, 'note');
            noteField.appendChild(note);
            box.appendChild(noteField);

            const p = w.pages[0];
            const totalSize = w.pages.reduce((s, x) => s + x.size, 0);
            const meta = el('dl', 'meta-table');
            [['页数', w.pages.length + ' 页'], ['格式', p.format.toUpperCase()], ['尺寸', `${p.width} × ${p.height}`],
                ['大小', A.formatSize(totalSize)], ['导入', A.formatDate(w.importedAt, true)]]
                .forEach(([k, v]) => { meta.appendChild(el('dt', null, k)); meta.appendChild(el('dd', null, v)); });
            box.appendChild(meta);
        }

        const actions = el('div', 'insp-actions');
        if (!inViewer) {
            const openBtn = el('button', 'btn', works.length > 1 ? '逐张查看' : '在看图器中打开');
            openBtn.type = 'button';
            openBtn.addEventListener('click', () => openViewer(works[0].id));
            actions.appendChild(openBtn);
        }
        const del = el('button', 'btn btn-danger-ghost');
        del.type = 'button';
        del.innerHTML = icon('trash') + (works.length > 1 ? `删除 ${works.length} 件` : '删除');
        del.addEventListener('click', () => confirmDelete(works.map((w) => w.id)));
        actions.appendChild(del);
        box.appendChild(actions);
    }

    /* ---------------- 删除 ---------------- */

    let confirmAction = null;
    function confirmDelete(ids) {
        const one = ids.length === 1 ? A.get(ids[0]) : null;
        $('confirm-title').textContent = one ? `删除“${A.displayTitle(one)}”？` : `删除所选的 ${ids.length} 件作品？`;
        $('confirm-text').textContent = '记录和原图文件都会从图库中移除，作者与标签保留。此操作无法撤销。';
        $('confirm').hidden = false;
        $('confirm-ok').focus();
        confirmAction = () => {
            const v = state.viewer;
            let nextId = null;
            if (v) {
                const i = indexOf(v.id);
                const rest = state.results.filter((w) => !ids.includes(w.id));
                nextId = rest[Math.min(i, rest.length - 1)]?.id ?? null;
            }
            A.remove(ids);
            ids.forEach((id) => { state.selected.delete(id); tiles.delete(id); });
            flashSaved(`已删除 ${ids.length} 件`);
            if (v) nextId != null ? showWork(nextId) : closeViewer();
        };
    }
    function closeConfirm() { $('confirm').hidden = true; confirmAction = null; }

    /* ---------------- 看图器 ---------------- */

    const stage = $('viewer-stage');
    const vImg = $('viewer-image');

    function openViewer(id) {
        state.viewer = { id, page: 0, scale: 1, fit: true, tx: 0, ty: 0, panel: state.viewer?.panel ?? true, strip: state.viewer?.strip ?? true };
        $('viewer').hidden = false;
        renderStrip();
        showWork(id);
    }

    function closeViewer() {
        if (!state.viewer) return;
        const id = state.viewer.id;
        state.viewer = null;
        Picker.close();
        $('viewer').hidden = true;
        if (A.get(id)) select(id);
        else renderMainInspector();
        $('grid-scroll').focus({ preventScroll: true });
    }

    function showWork(id, page = 0) {
        const v = state.viewer;
        v.id = id;
        v.page = page;
        v.fit = true;
        const work = A.get(id);
        const i = indexOf(id);
        $('viewer-title').textContent = A.displayTitle(work);
        const author = A.authorOf(work);
        $('viewer-sub').textContent = author ? author.name : '未设置作者';
        $('viewer-pos').textContent = i >= 0 ? `${i + 1} / ${state.results.length}` : '';
        $('viewer-prev').disabled = i <= 0;
        $('viewer-next').disabled = i < 0 || i >= state.results.length - 1;
        $('viewer').classList.toggle('no-panel', !v.panel);
        $('viewer').classList.toggle('no-strip', !v.strip);
        $('panel-toggle').setAttribute('aria-pressed', String(v.panel));
        $('strip-toggle').setAttribute('aria-pressed', String(v.strip));
        showPage(page);
        renderInspector($('viewer-panel'), [id], { inViewer: true });
        document.querySelectorAll('.strip-item').forEach((b) => {
            const on = Number(b.dataset.id) === id;
            b.setAttribute('aria-current', String(on));
            if (on) b.scrollIntoView({ block: 'nearest', inline: 'center' });
        });
    }

    function showPage(page) {
        const v = state.viewer;
        const work = A.get(v.id);
        v.page = page;
        const p = work.pages[page];
        vImg.src = p.src;
        vImg.style.width = p.width + 'px';
        vImg.style.height = p.height + 'px';
        v.w = p.width;
        v.h = p.height;
        fitImage();
        const sw = $('page-switch');
        sw.hidden = work.pages.length < 2;
        sw.innerHTML = '';
        work.pages.forEach((_, n) => {
            const b = el('button', null, String(n + 1));
            b.type = 'button';
            b.setAttribute('aria-label', `第 ${n + 1} 页`);
            b.setAttribute('aria-current', String(n === page));
            b.addEventListener('click', () => showPage(n));
            sw.appendChild(b);
        });
        $('viewer-sub').textContent = (A.authorOf(work)?.name || '未设置作者') + (work.pages.length > 1 ? ` · 第 ${page + 1} / ${work.pages.length} 页` : '');
    }

    function renderStrip() {
        const strip = $('viewer-strip');
        strip.innerHTML = '';
        state.results.forEach((w) => {
            const b = el('button', 'strip-item');
            b.type = 'button';
            b.dataset.id = w.id;
            b.title = A.displayTitle(w);
            const img = el('img');
            img.alt = '';
            img.loading = 'lazy';
            img.src = w.pages[0].src;
            b.appendChild(img);
            b.addEventListener('click', () => showWork(w.id));
            strip.appendChild(b);
        });
    }

    function fitScale() {
        const v = state.viewer;
        const pad = 32;
        return Math.min((stage.clientWidth - pad * 2) / v.w, (stage.clientHeight - pad * 2) / v.h, 1);
    }

    function fitImage() {
        const v = state.viewer;
        v.fit = true;
        v.scale = fitScale();
        v.tx = (stage.clientWidth - v.w * v.scale) / 2;
        v.ty = (stage.clientHeight - v.h * v.scale) / 2;
        applyTransform();
    }

    function clampPan() {
        const v = state.viewer;
        const sw = stage.clientWidth, sh = stage.clientHeight;
        const iw = v.w * v.scale, ih = v.h * v.scale;
        v.tx = iw <= sw ? (sw - iw) / 2 : Math.min(0, Math.max(sw - iw, v.tx));
        v.ty = ih <= sh ? (sh - ih) / 2 : Math.min(0, Math.max(sh - ih, v.ty));
    }

    function zoomTo(scale, cx = stage.clientWidth / 2, cy = stage.clientHeight / 2) {
        const v = state.viewer;
        const next = Math.max(Math.min(fitScale(), 0.1) * 0.5, Math.min(8, scale));
        v.tx = cx - (cx - v.tx) * (next / v.scale);
        v.ty = cy - (cy - v.ty) * (next / v.scale);
        v.scale = next;
        v.fit = false;
        clampPan();
        applyTransform();
    }

    function applyTransform() {
        const v = state.viewer;
        vImg.style.transform = `translate(${v.tx}px, ${v.ty}px) scale(${v.scale})`;
        const pct = Math.round(v.scale * 100) + '%';
        $('zoom-label').textContent = v.fit ? `适应 ${pct}` : pct;
        stage.classList.toggle('can-pan', v.w * v.scale > stage.clientWidth || v.h * v.scale > stage.clientHeight);
    }

    function step(delta) {
        const i = indexOf(state.viewer.id) + delta;
        if (state.results[i]) showWork(state.results[i].id);
    }

    function stepPage(delta) {
        const v = state.viewer;
        const work = A.get(v.id);
        const next = v.page + delta;
        if (next >= 0 && next < work.pages.length) showPage(next);
        else step(delta);
    }

    function togglePanel(force) {
        const v = state.viewer;
        v.panel = force ?? !v.panel;
        $('viewer').classList.toggle('no-panel', !v.panel);
        $('panel-toggle').setAttribute('aria-pressed', String(v.panel));
    }

    stage.addEventListener('wheel', (e) => {
        e.preventDefault();
        const r = stage.getBoundingClientRect();
        zoomTo(state.viewer.scale * Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
    }, { passive: false });

    stage.addEventListener('dblclick', (e) => {
        if (e.target.closest('button')) return;
        const r = stage.getBoundingClientRect();
        state.viewer.fit ? zoomTo(1, e.clientX - r.left, e.clientY - r.top) : fitImage();
    });

    let pan = null;
    stage.addEventListener('pointerdown', (e) => {
        if (e.button !== 0 || e.target.closest('button') || !stage.classList.contains('can-pan')) return;
        pan = { x: e.clientX, y: e.clientY, tx: state.viewer.tx, ty: state.viewer.ty };
        stage.setPointerCapture(e.pointerId);
        stage.classList.add('is-panning');
    });
    stage.addEventListener('pointermove', (e) => {
        if (!pan) return;
        const v = state.viewer;
        v.tx = pan.tx + e.clientX - pan.x;
        v.ty = pan.ty + e.clientY - pan.y;
        clampPan();
        applyTransform();
    });
    const endPan = () => { pan = null; stage.classList.remove('is-panning'); };
    stage.addEventListener('pointerup', endPan);
    stage.addEventListener('pointercancel', endPan);

    $('viewer-close').addEventListener('click', closeViewer);
    $('viewer-prev').addEventListener('click', () => step(-1));
    $('viewer-next').addEventListener('click', () => step(1));
    $('zoom-in').addEventListener('click', () => zoomTo(state.viewer.scale * 1.25));
    $('zoom-out').addEventListener('click', () => zoomTo(state.viewer.scale / 1.25));
    $('zoom-label').addEventListener('click', () => (state.viewer.fit ? zoomTo(1) : fitImage()));
    $('panel-toggle').addEventListener('click', () => togglePanel());
    $('strip-toggle').addEventListener('click', () => {
        const v = state.viewer;
        v.strip = !v.strip;
        $('viewer').classList.toggle('no-strip', !v.strip);
        $('strip-toggle').setAttribute('aria-pressed', String(v.strip));
    });

    /* ---------------- 导入 ---------------- */

    let importedIds = [];
    async function startImport(fileList) {
        const files = [...fileList];
        if (!files.length) return;
        const task = $('task');
        task.hidden = false;
        task.classList.remove('is-done');
        const list = $('task-list');
        list.innerHTML = '';
        const rows = files.map((f) => {
            const li = el('li');
            li.innerHTML = '<span></span><span class="st-pending">等待</span>';
            li.children[0].textContent = f.name;
            list.appendChild(li);
            return li;
        });
        $('task-text').textContent = `正在导入 0 / ${files.length}`;
        $('task-bar').style.width = '0';
        const label = { success: '已导入', duplicate: '重复', failed: '失败' };
        const results = await A.importFiles(files, (r, i, n) => {
            const li = rows[i];
            li.children[1].className = 'st-' + r.status;
            li.children[1].textContent = label[r.status];
            if (r.message) li.appendChild(el('small', null, r.message));
            $('task-text').textContent = `正在导入 ${i + 1} / ${n}`;
            $('task-bar').style.width = ((i + 1) / n) * 100 + '%';
        });
        const n = (s) => results.filter((r) => r.status === s).length;
        importedIds = results.filter((r) => r.work).map((r) => r.work.id);
        task.classList.add('is-done');
        $('task-text').textContent = `导入完成 · 成功 ${n('success')}` + (n('duplicate') ? ` · 重复 ${n('duplicate')}` : '') + (n('failed') ? ` · 失败 ${n('failed')}` : '');
        $('task-show').hidden = !importedIds.length;
        if (n('duplicate') || n('failed')) setTaskPopover(true);
    }

    function setTaskPopover(open) {
        $('task-popover').hidden = !open;
        $('task-button').setAttribute('aria-expanded', String(open));
    }

    $('task-button').addEventListener('click', () => setTaskPopover($('task-popover').hidden));
    $('task-dismiss').addEventListener('click', () => { setTaskPopover(false); $('task').hidden = true; });
    $('task-show').addEventListener('click', () => {
        setTaskPopover(false);
        state.scope = 'recent';
        state.text = '';
        $('search-input').value = '';
        state.authorIds.clear();
        state.tagIds.clear();
        state.sort = 'newest';
        $('sort-select').value = 'newest';
        state.selected = new Set(importedIds.filter((id) => A.get(id)));
        state.focusId = importedIds[0] ?? null;
        state.anchorId = state.focusId;
        runQuery();
        $('grid-scroll').scrollTop = 0;
    });

    $('import-button').addEventListener('click', () => $('file-input').click());
    $('file-input').addEventListener('change', (e) => { startImport(e.target.files); e.target.value = ''; });

    let dragDepth = 0;
    const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
    window.addEventListener('dragenter', (e) => { if (!hasFiles(e)) return; e.preventDefault(); dragDepth++; $('drop-overlay').hidden = false; });
    window.addEventListener('dragover', (e) => { if (hasFiles(e)) e.preventDefault(); });
    window.addEventListener('dragleave', () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $('drop-overlay').hidden = true; });
    window.addEventListener('drop', (e) => {
        if (!hasFiles(e)) return;
        e.preventDefault();
        dragDepth = 0;
        $('drop-overlay').hidden = true;
        startImport(e.dataTransfer.files);
    });

    /* ---------------- 事件 ---------------- */

    let searchTimer;
    $('search-input').addEventListener('input', (e) => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => { state.text = e.target.value.trim(); runQuery(); }, 140);
    });
    $('search-form').addEventListener('submit', (e) => { e.preventDefault(); state.text = $('search-input').value.trim(); runQuery(); });
    $('search-input').addEventListener('keydown', (e) => {
        if (e.key === 'Escape') { e.target.value ? (e.target.value = '', state.text = '', runQuery()) : e.target.blur(); }
        if (e.key === 'ArrowDown') { e.preventDefault(); $('grid-scroll').focus(); if (!state.selected.size && state.results[0]) select(state.results[0].id); }
    });

    document.querySelectorAll('.side-item').forEach((b) => b.addEventListener('click', () => { state.scope = b.dataset.scope; runQuery(); }));
    $('authors-filter').addEventListener('input', renderSidebar);
    $('tags-filter').addEventListener('input', renderSidebar);
    $('authors-clear').addEventListener('click', () => { state.authorIds.clear(); runQuery(); });
    $('tags-clear').addEventListener('click', () => { state.tagIds.clear(); runQuery(); });
    $('empty-clear').addEventListener('click', clearAllFilters);
    $('sort-select').addEventListener('change', (e) => { state.sort = e.target.value; runQuery({ keepSelection: true }); });
    $('size-range').addEventListener('input', (e) => { state.rowHeight = Number(e.target.value); layoutGrid(); });
    $('inspector-toggle').addEventListener('click', () => setInspector(!state.inspector));

    function setInspector(on) {
        state.inspector = on;
        $('app').classList.toggle('no-inspector', !on);
        $('inspector-toggle').setAttribute('aria-pressed', String(on));
        if (on) renderMainInspector();
    }

    $('grid').addEventListener('click', (e) => {
        const tile = e.target.closest('.tile');
        if (!tile) return;
        select(Number(tile.dataset.id), { toggle: e.ctrlKey || e.metaKey, range: e.shiftKey });
        $('grid-scroll').focus({ preventScroll: true });
    });
    $('grid').addEventListener('dblclick', (e) => {
        const tile = e.target.closest('.tile');
        if (tile) openViewer(Number(tile.dataset.id));
    });
    $('grid-scroll').addEventListener('mousedown', (e) => {
        const blank = e.target.id === 'grid' || e.target.classList.contains('row');
        if (blank && !e.ctrlKey && !e.shiftKey) clearSelection();
    });

    new ResizeObserver(layoutGrid).observe($('grid-scroll'));
    new ResizeObserver(() => {
        if (!state.viewer || !state.viewer.w) return;
        state.viewer.fit ? fitImage() : (clampPan(), applyTransform());
    }).observe(stage);

    $('confirm-cancel').addEventListener('click', closeConfirm);
    $('confirm-ok').addEventListener('click', () => { const fn = confirmAction; closeConfirm(); if (fn) fn(); });
    $('help-button').addEventListener('click', () => { $('shortcuts').hidden = false; $('shortcuts-close').focus(); });
    $('shortcuts-close').addEventListener('click', () => { $('shortcuts').hidden = true; });

    function focusTitle() {
        const scope = state.viewer ? $('viewer-panel') : $('inspector');
        if (state.viewer && !state.viewer.panel) togglePanel(true);
        if (!state.viewer && !state.inspector) setInspector(true);
        const t = scope.querySelector('[data-role="title"]');
        if (t) { t.focus(); t.select(); }
    }

    function openTagPicker() {
        const scope = state.viewer ? $('viewer-panel') : $('inspector');
        if (state.viewer && !state.viewer.panel) togglePanel(true);
        if (!state.viewer && !state.inspector) setInspector(true);
        const b = scope.querySelector('[data-role="tag-add"]');
        if (b) b.click();
    }

    document.addEventListener('keydown', (e) => {
        if (!$('confirm').hidden) {
            if (e.key === 'Escape') closeConfirm();
            return;
        }
        if (!$('shortcuts').hidden) {
            if (e.key === 'Escape' || e.key === '?') $('shortcuts').hidden = true;
            return;
        }
        const typing = e.target instanceof Element && e.target.matches('input, textarea, select');
        const ctrl = e.ctrlKey || e.metaKey;
        if (ctrl && e.key.toLowerCase() === 'k') { e.preventDefault(); if (state.viewer) closeViewer(); $('search-input').focus(); $('search-input').select(); return; }
        if (ctrl && e.key.toLowerCase() === 'o') { e.preventDefault(); $('file-input').click(); return; }
        if (typing) return;
        if (e.key === '?') { $('shortcuts').hidden = false; return; }

        if (state.viewer) {
            const v = state.viewer;
            switch (e.key) {
                case 'Escape': closeViewer(); break;
                case 'ArrowLeft': step(-1); break;
                case 'ArrowRight': step(1); break;
                case 'PageUp': case 'ArrowUp': e.preventDefault(); stepPage(-1); break;
                case 'PageDown': case 'ArrowDown': e.preventDefault(); stepPage(1); break;
                case '+': case '=': zoomTo(v.scale * 1.25); break;
                case '-': zoomTo(v.scale / 1.25); break;
                case '0': fitImage(); break;
                case '1': zoomTo(1); break;
                case 'i': case 'I': togglePanel(); break;
                case 'f': case 'F': $('strip-toggle').click(); break;
                case 'e': case 'E': e.preventDefault(); focusTitle(); break;
                case 't': case 'T': e.preventDefault(); openTagPicker(); break;
                case 'Delete': confirmDelete([v.id]); break;
                default: return;
            }
            return;
        }

        if (e.key === '/') { e.preventDefault(); $('search-input').focus(); return; }
        if (ctrl && e.key.toLowerCase() === 'a') {
            e.preventDefault();
            state.selected = new Set(state.results.map((w) => w.id));
            paintSelection(); renderSummary(); renderMainInspector();
            return;
        }
        switch (e.key) {
            case 'ArrowLeft': case 'ArrowRight': case 'ArrowUp': case 'ArrowDown':
                e.preventDefault(); moveFocus(e.key, e.shiftKey); break;
            case 'Enter': if (state.focusId != null) openViewer(state.focusId); break;
            case ' ': e.preventDefault(); if (state.focusId != null) openViewer(state.focusId); break;
            case 'Escape': clearSelection(); break;
            case 'Delete': if (state.selected.size) confirmDelete([...state.selected]); break;
            case 'e': case 'E': if (state.selected.size === 1) { e.preventDefault(); focusTitle(); } break;
            case 't': case 'T': if (state.selected.size) { e.preventDefault(); openTagPicker(); } break;
            case 'i': case 'I': setInspector(!state.inspector); break;
            default: return;
        }
    });

    // 数据变化（编辑、导入、删除）后刷新列表与计数；就地编辑时不重绘面板，避免打断输入
    A.subscribe((detail) => {
        runQuery({ keepSelection: detail.type === 'update' });
        if (state.viewer && A.get(state.viewer.id)) {
            const work = A.get(state.viewer.id);
            $('viewer-title').textContent = A.displayTitle(work);
            if (detail.type !== 'update') renderStrip();
            if (!quietInspector && detail.type !== 'update') renderInspector($('viewer-panel'), [state.viewer.id], { inViewer: true });
            showPageMetaOnly(work);
        }
    });

    function showPageMetaOnly(work) {
        $('viewer-sub').textContent = (A.authorOf(work)?.name || '未设置作者') + (work.pages.length > 1 ? ` · 第 ${state.viewer.page + 1} / ${work.pages.length} 页` : '');
        const i = indexOf(work.id);
        $('viewer-pos').textContent = i >= 0 ? `${i + 1} / ${state.results.length}` : '不在当前结果中';
    }

    runQuery();
    if (state.results[0]) select(state.results[0].id);
})();
