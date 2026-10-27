/*
 * 原型 B · 画廊
 * 作品墙为主，搜索框兼做作者 / 标签筛选入口；看图器沉浸，编辑是显式进入、显式保存的独立模式；
 * 导入在弹层中完成，结束后可以给这一批作品统一设置作者和标签。
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
    const authorById = (id) => A.authors.find((a) => a.id === id) || null;
    const tagById = (id) => A.tags.find((t) => t.id === id) || null;

    const state = {
        text: '', scope: 'all', authorIds: new Set(), tagIds: new Set(), sort: 'newest',
        density: 'roomy', results: [], viewer: null,
        selecting: false, selected: new Set(), focusId: null, anchorId: null
    };

    // 动效实验层（fx.js）的可选挂钩：未加载或切回原版时什么也不做，原版行为不变
    const fx = (name, ...args) => {
        const hooks = window.GalleryFx;
        return hooks && typeof hooks[name] === 'function' ? hooks[name](...args) : undefined;
    };

    /* ---------------- 通用：提示与确认 ---------------- */

    function toast(text) {
        const t = $('toast');
        t.textContent = text;
        t.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { t.hidden = true; }, 1800);
    }

    let askResolve = null;
    function ask({ title, text, ok = '删除', danger = true }) {
        $('confirm-title').textContent = title;
        $('confirm-text').textContent = text;
        $('confirm-ok').textContent = ok;
        $('confirm-ok').className = 'btn ' + (danger ? 'btn-danger' : 'btn-ink');
        $('confirm').hidden = false;
        $('confirm-ok').focus();
        return new Promise((resolve) => { askResolve = resolve; });
    }
    function answer(value) {
        $('confirm').hidden = true;
        const r = askResolve;
        askResolve = null;
        if (r) r(value);
    }
    $('confirm-ok').addEventListener('click', () => answer(true));
    $('confirm-cancel').addEventListener('click', () => answer(false));

    /* ---------------- 图库 ---------------- */

    function runQuery() {
        state.results = A.query({
            text: state.text, scope: state.scope, sort: state.sort,
            authorIds: [...state.authorIds], tagIds: [...state.tagIds]
        });
        const visible = new Set(state.results.map((w) => w.id));
        state.selected.forEach((id) => { if (!visible.has(id)) state.selected.delete(id); });
        renderScopes();
        renderFilters();
        renderWall();
    }

    function renderScopes() {
        const c = A.counts();
        document.querySelectorAll('[data-count]').forEach((n) => { n.textContent = c[n.dataset.count]; });
        document.querySelectorAll('.scopes button').forEach((b) => b.setAttribute('aria-current', String(b.dataset.scope === state.scope)));
    }

    function renderFilters() {
        const box = $('active-filters');
        box.innerHTML = '';
        const chips = [];
        if (state.text) chips.push(['', `“${state.text}”`, () => { state.text = ''; $('q').value = ''; }]);
        state.authorIds.forEach((id) => { const a = authorById(id); if (a) chips.push(['作者', a.name, () => state.authorIds.delete(id)]); });
        state.tagIds.forEach((id) => { const t = tagById(id); if (t) chips.push(['标签', t.name, () => state.tagIds.delete(id)]); });
        chips.forEach(([kind, label, clear]) => {
            const chip = el('span', 'fchip');
            if (kind) chip.appendChild(el('small', null, kind));
            chip.appendChild(el('span', null, label));
            const x = el('button');
            x.type = 'button';
            x.setAttribute('aria-label', '移除筛选 ' + label);
            x.innerHTML = icon('close');
            x.addEventListener('click', () => { clear(); runQuery(); });
            chip.appendChild(x);
            box.appendChild(chip);
        });
        if (chips.length > 1) {
            const all = el('button', 'clear-all', '清除');
            all.type = 'button';
            all.addEventListener('click', clearFilters);
            box.appendChild(all);
        }
        if (chips.length || state.scope !== 'all') {
            box.appendChild(el('span', 'clear-all', `${state.results.length} 件`)).style.textDecoration = 'none';
        }
        const af = $('author-filter'), tf = $('tag-filter');
        af.classList.toggle('has-value', state.authorIds.size > 0);
        tf.classList.toggle('has-value', state.tagIds.size > 0);
        af.firstChild.textContent = state.authorIds.size ? `作者 · ${state.authorIds.size} ` : '作者 ';
        tf.firstChild.textContent = state.tagIds.size ? `标签 · ${state.tagIds.size} ` : '标签 ';
    }

    function clearFilters() {
        state.text = '';
        $('q').value = '';
        state.authorIds.clear();
        state.tagIds.clear();
        state.scope = 'all';
        runQuery();
    }

    const pieces = new Map();

    function pieceFor(work) {
        let fig = pieces.get(work.id);
        if (!fig) {
            fig = el('figure', 'piece');
            fig.dataset.id = work.id;
            fig.innerHTML = `<div class="piece-media">
                    <button class="piece-open" type="button"><img alt="" loading="lazy" decoding="async" draggable="false"></button>
                    <button class="piece-check" type="button" aria-pressed="false" aria-label="选择"></button>
                    <div class="piece-marks"></div>
                    <button class="piece-edit" type="button">${icon('pen')}编辑</button>
                </div>
                <figcaption><span class="piece-title"></span><button class="piece-author" type="button"></button></figcaption>`;
            fig.querySelector('.piece-open').addEventListener('click', (e) => onPieceClick(work.id, e));
            fig.querySelector('.piece-check').addEventListener('click', (e) => {
                if (e.shiftKey) selectRange(work.id, true);
                else toggleSelect(work.id);
            });
            fig.querySelector('.piece-edit').addEventListener('click', () => openViewer(work.id, { edit: true }));
            fig.querySelector('.piece-author').addEventListener('click', () => {
                const w = A.get(work.id);
                if (w.authorId == null) return;
                state.authorIds = new Set([w.authorId]);
                runQuery();
                window.scrollTo({ top: 0 });
            });
            pieces.set(work.id, fig);
        }
        const img = fig.querySelector('img');
        const p = work.pages[0];
        if (img.getAttribute('src') !== p.src) img.src = p.src;
        img.width = p.width;
        img.height = p.height;
        fig.querySelector('.piece-open').setAttribute('aria-label', '查看 ' + A.displayTitle(work));
        fig.querySelector('.piece-title').textContent = A.displayTitle(work);
        const author = A.authorOf(work);
        const ab = fig.querySelector('.piece-author');
        ab.textContent = author ? author.name : '未设置作者';
        ab.classList.toggle('is-none', !author);
        ab.title = author ? `只看 ${author.name} 的作品` : '';
        const marks = fig.querySelector('.piece-marks');
        marks.innerHTML = '';
        if (work.pages.length > 1) marks.appendChild(el('span', 'mark', `${work.pages.length} 页`));
        if (p.format === 'gif') marks.appendChild(el('span', 'mark', 'GIF'));
        if (work.fresh) { fig.classList.add('is-fresh'); delete work.fresh; }
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
        $('empty').hidden = state.results.length > 0;
        $('wall-end').hidden = !state.results.length;
        paintSelection();
        fx('playWall', before);
    }

    /* ---------------- 多选与批量整理（来自原型 A） ---------------- */

    function onPieceClick(id, e) {
        state.focusId = id;
        if (e.shiftKey && state.anchorId != null) selectRange(id, e.ctrlKey || e.metaKey);
        else if (e.ctrlKey || e.metaKey || state.selecting) toggleSelect(id);
        else openViewer(id);
    }

    function toggleSelect(id) {
        state.selected.has(id) ? state.selected.delete(id) : state.selected.add(id);
        state.anchorId = id;
        state.focusId = id;
        setSelecting(true);
    }

    function selectRange(id, additive) {
        const ids = state.results.map((w) => w.id);
        const a = ids.indexOf(state.anchorId), b = ids.indexOf(id);
        if (a < 0 || b < 0) { toggleSelect(id); return; }
        if (!additive) state.selected.clear();
        ids.slice(Math.min(a, b), Math.max(a, b) + 1).forEach((x) => state.selected.add(x));
        state.focusId = id;
        setSelecting(true);
    }

    function setSelecting(on) {
        state.selecting = on;
        if (!on) {
            state.selected.clear();
            state.anchorId = null;
            Picker.close();
        }
        paintSelection();
    }

    function paintSelection() {
        document.body.classList.toggle('is-selecting', state.selecting);
        pieces.forEach((fig, id) => {
            const on = state.selected.has(id);
            fig.classList.toggle('is-selected', on);
            const check = fig.querySelector('.piece-check');
            check.setAttribute('aria-pressed', String(on));
            check.setAttribute('aria-label', on ? '取消选择' : '选择');
        });
        const n = state.selected.size;
        $('batchbar').hidden = !state.selecting;
        $('bb-count').textContent = n ? `已选 ${n} 件` : '点击作品进行选择';
        ['bb-author', 'bb-tags', 'bb-delete'].forEach((id) => { $(id).disabled = !n; });
        $('bb-all').textContent = n && n === state.results.length ? '取消全选' : '全选';
        $('select-toggle').setAttribute('aria-pressed', String(state.selecting));
    }

    function batchAuthor(anchor) {
        const ids = [...state.selected];
        Picker.open(anchor, {
            placeholder: '搜索或新建作者',
            items: () => A.authors.map((a) => ({ id: a.id, label: a.name, meta: a.handle ? '@' + a.handle : '' })),
            selected: () => {
                const set = new Set(ids.map((id) => A.get(id)?.authorId));
                return set.size === 1 ? set : new Set();
            },
            clearLabel: '不设置作者',
            onSelect: (id) => {
                A.updateMany(ids, { authorId: id });
                toast(id == null ? `已清除 ${ids.length} 件的作者` : `已为 ${ids.length} 件设置作者“${authorById(id).name}”`);
            },
            onCreate: (name) => A.createAuthor(name).id,
            createText: (text) => `新建作者 “${text}”`
        });
    }

    // 全部所选都有的标签显示为已勾选，点击即从全部移除；部分拥有的显示数量，点击补齐到全部
    function batchTags(anchor) {
        const ids = [...state.selected];
        const countOn = (tid) => ids.filter((id) => A.get(id)?.tagIds.includes(tid)).length;
        Picker.open(anchor, {
            placeholder: '搜索或新建标签',
            multi: true,
            items: () => A.tags.map((t) => {
                const n = countOn(t.id);
                return { id: t.id, label: t.name, meta: n && n < ids.length ? `${n}/${ids.length}` : '' };
            }),
            selected: () => new Set(A.tags.filter((t) => countOn(t.id) === ids.length).map((t) => t.id)),
            onSelect: (tid) => {
                const onAll = countOn(tid) === ids.length;
                A.updateMany(ids, onAll ? { removeTagIds: [tid] } : { addTagIds: [tid] });
                const name = tagById(tid).name;
                toast(onAll ? `已从 ${ids.length} 件移除“${name}”` : `已为 ${ids.length} 件添加“${name}”`);
            },
            onCreate: (name) => A.createTag(name).id,
            createText: (text) => `新建标签 “${text}”`
        });
    }

    // 瀑布流按屏幕位置移动焦点：左右找相邻列中高度最接近的，上下在同一列内移动
    function moveFocus(key, extend) {
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
        const id = Number(target.dataset.id);
        if (extend) {
            if (state.anchorId == null) state.anchorId = state.focusId ?? id;
            selectRange(id, false);
        }
        state.focusId = id;
        target.querySelector('.piece-open').focus();
    }

    $('wall').addEventListener('focusin', (e) => {
        const fig = e.target.closest('.piece');
        if (fig) state.focusId = Number(fig.dataset.id);
    });
    // 空格用于选择；阻止按钮在 keyup 时把它当作点击
    $('wall').addEventListener('keyup', (e) => { if (e.key === ' ' && e.target.classList.contains('piece-open')) e.preventDefault(); });

    $('select-toggle').addEventListener('click', () => setSelecting(!state.selecting));
    $('bb-done').addEventListener('click', () => setSelecting(false));
    $('bb-all').addEventListener('click', () => {
        if (state.selected.size === state.results.length) state.selected.clear();
        else state.results.forEach((w) => state.selected.add(w.id));
        paintSelection();
    });
    $('bb-author').addEventListener('click', (e) => batchAuthor(e.currentTarget));
    $('bb-tags').addEventListener('click', (e) => batchTags(e.currentTarget));
    $('bb-delete').addEventListener('click', () => deleteWorks([...state.selected]));

    /* ---------------- 搜索建议：搜索框同时是筛选入口 ---------------- */

    const q = $('q');
    const suggest = $('suggest');
    let sgItems = [];
    let sgActive = -1;

    function highlight(text, needle) {
        const span = el('span');
        const i = needle ? text.toLowerCase().indexOf(needle.toLowerCase()) : -1;
        if (i < 0) { span.textContent = text; return span; }
        span.append(text.slice(0, i));
        span.appendChild(el('mark', null, text.slice(i, i + needle.length)));
        span.append(text.slice(i + needle.length));
        return span;
    }

    function renderSuggest() {
        const text = q.value.trim();
        const needle = text.replace(/^@/, '');
        const c = A.counts();
        suggest.innerHTML = '';
        sgItems = [];
        const group = (label) => {
            const g = el('div', 'sg-group');
            if (label) g.appendChild(el('div', 'sg-label', label));
            suggest.appendChild(g);
            return g;
        };
        const add = (node, action) => {
            node.addEventListener('mousedown', (e) => e.preventDefault());
            node.addEventListener('click', action);
            sgItems.push({ node, action });
        };

        if (!text) {
            const tg = group('常用标签');
            const chips = el('div', 'sg-chips');
            [...A.tags].sort((a, b) => (c.byTag.get(b.id) || 0) - (c.byTag.get(a.id) || 0)).slice(0, 10).forEach((t) => {
                const b = el('button', 'sg-chip' + (state.tagIds.has(t.id) ? ' is-active' : ''), t.name);
                b.type = 'button';
                add(b, () => { toggle(state.tagIds, t.id); renderSuggest(); });
                chips.appendChild(b);
            });
            tg.appendChild(chips);
            const ag = group('作者');
            const achips = el('div', 'sg-chips');
            [...A.authors].sort((a, b) => (c.byAuthor.get(b.id) || 0) - (c.byAuthor.get(a.id) || 0)).forEach((a) => {
                const b = el('button', 'sg-chip' + (state.authorIds.has(a.id) ? ' is-active' : ''), a.name);
                b.type = 'button';
                add(b, () => { toggle(state.authorIds, a.id); renderSuggest(); });
                achips.appendChild(b);
            });
            ag.appendChild(achips);
        } else {
            const g0 = group();
            const row = el('button', 'sg-item');
            row.type = 'button';
            row.innerHTML = `${icon('search')}<span></span><span class="sg-meta">Enter</span>`;
            row.children[1].textContent = `搜索 “${text}”`;
            add(row, () => commitText(text));
            g0.appendChild(row);

            const authors = A.authors.filter((a) => (a.name + ' ' + (a.handle || '')).toLowerCase().includes(needle.toLowerCase())).slice(0, 4);
            if (authors.length) {
                const g = group('筛选作者');
                authors.forEach((a) => {
                    const b = el('button', 'sg-item');
                    b.type = 'button';
                    b.appendChild(highlight(a.name, needle));
                    if (a.handle) b.appendChild(el('span', 'sg-kind', '@' + a.handle));
                    b.appendChild(el('span', 'sg-meta', `${c.byAuthor.get(a.id) || 0} 件`));
                    add(b, () => { state.authorIds.add(a.id); q.value = ''; closeSuggest(); runQuery(); });
                    g.appendChild(b);
                });
            }
            const tags = A.tags.filter((t) => t.name.toLowerCase().includes(needle.toLowerCase())).slice(0, 5);
            if (tags.length) {
                const g = group('筛选标签');
                tags.forEach((t) => {
                    const b = el('button', 'sg-item');
                    b.type = 'button';
                    b.appendChild(el('span', 'sg-kind', '#'));
                    b.appendChild(highlight(t.name, needle));
                    b.appendChild(el('span', 'sg-meta', `${c.byTag.get(t.id) || 0} 件`));
                    add(b, () => { state.tagIds.add(t.id); q.value = ''; closeSuggest(); runQuery(); });
                    g.appendChild(b);
                });
            }
        }
        sgActive = text ? 0 : -1;
        paintSuggest();
        suggest.hidden = false;
        q.setAttribute('aria-expanded', 'true');
    }

    function paintSuggest() {
        sgItems.forEach((it, i) => it.node.classList.toggle('is-active', i === sgActive));
    }

    function closeSuggest() {
        suggest.hidden = true;
        q.setAttribute('aria-expanded', 'false');
    }

    function toggle(set, id) {
        set.has(id) ? set.delete(id) : set.add(id);
        runQuery();
    }

    function commitText(text) {
        state.text = text;
        closeSuggest();
        q.blur();
        runQuery();
    }

    q.addEventListener('focus', renderSuggest);
    q.addEventListener('input', renderSuggest);
    q.addEventListener('blur', () => setTimeout(closeSuggest, 80));
    q.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') { e.preventDefault(); sgActive = Math.min(sgItems.length - 1, sgActive + 1); paintSuggest(); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); sgActive = Math.max(0, sgActive - 1); paintSuggest(); }
        else if (e.key === 'Enter') {
            e.preventDefault();
            if (sgItems[sgActive]) sgItems[sgActive].action();
            else commitText(q.value.trim());
        } else if (e.key === 'Escape') {
            if (q.value) { q.value = ''; renderSuggest(); } else { closeSuggest(); q.blur(); }
        }
        e.stopPropagation();
    });

    function facetPicker(anchor, kind) {
        const isAuthor = kind === 'author';
        const set = isAuthor ? state.authorIds : state.tagIds;
        const c = A.counts();
        Picker.open(anchor, {
            placeholder: isAuthor ? '搜索作者' : '搜索标签',
            multi: true,
            items: () => (isAuthor ? A.authors : A.tags).map((x) => ({
                id: x.id, label: x.name,
                meta: `${(isAuthor ? c.byAuthor : c.byTag).get(x.id) || 0}`
            })),
            selected: () => set,
            onSelect: (id) => toggle(set, id)
        });
    }

    $('author-filter').addEventListener('click', (e) => facetPicker(e.currentTarget, 'author'));
    $('tag-filter').addEventListener('click', (e) => facetPicker(e.currentTarget, 'tag'));
    $('sort').addEventListener('change', (e) => { state.sort = e.target.value; runQuery(); });
    $('empty-clear').addEventListener('click', clearFilters);
    document.querySelectorAll('.scopes button').forEach((b) => b.addEventListener('click', () => { state.scope = b.dataset.scope; runQuery(); }));
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

    function openViewer(id, { edit = false } = {}) {
        state.viewer = { id, page: 0, info: false, editing: false, draft: null, original: '', scale: 1, fit: true, tx: 0, ty: 0 };
        viewer.hidden = false;
        document.body.classList.add('is-locked');
        showChrome();
        showWork(id, fx('startPage', id) || 0);
        fx('viewerOpened', id);
        if (edit) openEditor();
    }

    async function closeViewer() {
        if (!state.viewer) return;
        if (state.viewer.editing && !(await leaveEditor())) return;
        const id = state.viewer.id;
        fx('viewerClosing', id);
        state.viewer = null;
        Picker.close();
        viewer.hidden = true;
        viewer.style.setProperty('--info', '0px');
        viewer.style.setProperty('--editor', '0px');
        document.body.classList.remove('is-locked');
        const fig = pieces.get(id);
        if (fig) { fig.scrollIntoView({ block: 'nearest' }); fig.querySelector('.piece-open').focus({ preventScroll: true }); }
    }

    async function go(id) {
        const v = state.viewer;
        if (v.editing && v.id !== id && !(await leaveEditor())) return;
        showWork(id);
        if (v.editing) fillEditor();
    }

    function showWork(id, page = 0) {
        fx('settle');
        const v = state.viewer;
        const work = A.get(id);
        v.id = id;
        const i = indexOf(id);
        viewer.style.setProperty('--tint', mix(work.pages[0].tone, '#121110', 0.32));
        $('sv-prev').disabled = i <= 0;
        $('sv-next').disabled = i < 0 || i >= state.results.length - 1;
        $('sv-num').innerHTML = i >= 0 ? `${String(i + 1).padStart(2, '0')}<small>/ ${state.results.length}</small>` : '';
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
        const source = $('sv-source');
        source.href = work.source || '#';
        source.setAttribute('aria-disabled', String(!work.source));
        renderCaption();
        showPage(page);
        if (v.info) renderInfo();
    }

    function renderCaption() {
        const work = A.get(state.viewer.id);
        $('sv-title').textContent = A.displayTitle(work);
        const by = $('sv-by');
        by.innerHTML = '';
        const author = A.authorOf(work);
        if (author) {
            const b = el('button', null, author.name);
            b.type = 'button';
            b.title = `只看 ${author.name} 的作品`;
            b.addEventListener('click', () => filterFromViewer('author', author.id));
            by.appendChild(b);
        } else by.append('未设置作者');
        const tags = A.tagsOf(work);
        if (tags.length) by.append('　·　' + tags.map((t) => t.name).join('、'));
        by.append('　·　' + A.relativeDay(work.importedAt) + '导入');
    }

    function showPage(n) {
        const v = state.viewer;
        const work = A.get(v.id);
        const p = work.pages[n];
        v.page = n;
        v.w = p.width;
        v.h = p.height;
        img.src = p.src;
        img.style.width = p.width + 'px';
        img.style.height = p.height + 'px';
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
        const z = $('sv-zoom');
        z.textContent = v.fit ? '' : Math.round(v.scale * 100) + '%';
        if (announce) showChrome();
    }

    function step(delta) {
        const i = indexOf(state.viewer.id) + delta;
        if (state.results[i]) { fx('navigate', delta); go(state.results[i].id); }
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

    // 鼠标静止 2.5 秒后隐藏界面，只留作品；信息 / 编辑 / 菜单打开时保持显示
    function showChrome() {
        viewer.classList.add('chrome-on');
        clearTimeout(chromeTimer);
        chromeTimer = setTimeout(() => {
            const v = state.viewer;
            if (!v || v.info || v.editing || !$('sv-menu').hidden || Picker.isOpen()) return;
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
        const sa = section(c1, '作者');
        const author = A.authorOf(work);
        if (author) {
            const b = el('button', 'info-author', author.name);
            b.type = 'button';
            b.addEventListener('click', () => filterFromViewer('author', author.id));
            sa.appendChild(b);
            if (author.handle) sa.appendChild(el('p', 'info-handle', '@' + author.handle));
        } else sa.appendChild(el('p', 'info-empty', '未设置'));
        const st = section(c1, '标签');
        const tags = A.tagsOf(work);
        if (tags.length) {
            const row = el('div', 'info-tags');
            tags.forEach((t) => {
                const b = el('button', null, t.name);
                b.type = 'button';
                b.title = `查看所有“${t.name}”作品`;
                b.addEventListener('click', () => filterFromViewer('tag', t.id));
                row.appendChild(b);
            });
            st.appendChild(row);
        } else st.appendChild(el('p', 'info-empty', '还没有标签'));
        const sn = section(c2, '备注');
        sn.appendChild(el('p', work.note ? 'info-note' : 'info-empty', work.note || '没有备注'));
        const ss = section(c2, '来源');
        if (work.source) {
            const a = el('a', 'info-source', work.source);
            a.href = work.source;
            a.target = '_blank';
            a.rel = 'noreferrer';
            ss.appendChild(a);
        } else ss.appendChild(el('p', 'info-empty', '没有来源链接'));
        const sf = section(c3, '文件');
        const p = work.pages[state.viewer.page];
        const dl = el('dl', 'info-files');
        [['页数', `${work.pages.length} 页`], ['格式', p.format.toUpperCase()], ['尺寸', `${p.width} × ${p.height}`],
            ['大小', A.formatSize(work.pages.reduce((s, x) => s + x.size, 0))], ['导入', A.formatDate(work.importedAt, true)]]
            .forEach(([k, val]) => { dl.appendChild(el('dt', null, k)); dl.appendChild(el('dd', null, val)); });
        sf.appendChild(dl);
    }

    async function filterFromViewer(kind, id) {
        if (state.viewer && state.viewer.editing && !(await leaveEditor())) return;
        if (state.viewer) state.viewer.editing = false;
        state.text = '';
        q.value = '';
        state.scope = 'all';
        state.authorIds = kind === 'author' ? new Set([id]) : new Set();
        state.tagIds = kind === 'tag' ? new Set([id]) : new Set();
        await closeViewer();
        runQuery();
        window.scrollTo({ top: 0 });
    }

    /* ---------------- 编辑模式：草稿 + 显式保存 ---------------- */

    function openEditor() {
        const v = state.viewer;
        v.editing = true;
        $('sv-editor').hidden = false;
        $('sv-edit-toggle').hidden = true;
        viewer.style.setProperty('--editor', window.innerWidth <= 1080 ? '360px' : '420px');
        fillEditor();
        showChrome();
        $('ed-title').focus({ preventScroll: true });
    }

    function fillEditor() {
        const v = state.viewer;
        const w = A.get(v.id);
        v.draft = { title: w.title || '', authorId: w.authorId, tagIds: [...w.tagIds], source: w.source || '', note: w.note || '' };
        v.original = JSON.stringify(v.draft);
        $('ed-title').value = v.draft.title;
        $('ed-source').value = v.draft.source;
        $('ed-note').value = v.draft.note;
        renderDraftAuthor($('ed-author'), v.draft.authorId);
        renderDraftTags($('ed-tags'), v.draft.tagIds, refreshDirty);
        refreshDirty();
    }

    function isDirty() {
        const v = state.viewer;
        return v && v.editing && JSON.stringify(v.draft) !== v.original;
    }

    function refreshDirty() {
        const dirty = isDirty();
        $('ed-dirty').hidden = !dirty;
        $('ed-save').disabled = !dirty;
    }

    function closeEditor() {
        const v = state.viewer;
        v.editing = false;
        v.draft = null;
        Picker.close();
        $('sv-editor').hidden = true;
        $('sv-edit-toggle').hidden = false;
        viewer.style.setProperty('--editor', '0px');
    }

    async function leaveEditor() {
        if (!isDirty()) { closeEditor(); return true; }
        const ok = await ask({ title: '放弃未保存的修改？', text: '这件作品的修改还没有保存。', ok: '放弃修改' });
        if (ok) closeEditor();
        return ok;
    }

    function renderDraftAuthor(btn, authorId) {
        const a = authorById(authorId);
        btn.innerHTML = '';
        btn.classList.toggle('is-empty', !a);
        const label = el('span', null, a ? a.name : '选择作者');
        if (a && a.handle) label.appendChild(el('span', 'handle', '　@' + a.handle));
        btn.appendChild(label);
    }

    function renderDraftTags(box, tagIds, onChange) {
        box.innerHTML = '';
        tagIds.forEach((id) => {
            const t = tagById(id);
            if (!t) return;
            const chip = el('span', 'ed-tag', t.name);
            const x = el('button');
            x.type = 'button';
            x.setAttribute('aria-label', '移除标签 ' + t.name);
            x.innerHTML = icon('close');
            x.addEventListener('click', () => {
                tagIds.splice(tagIds.indexOf(id), 1);
                renderDraftTags(box, tagIds, onChange);
                onChange();
            });
            chip.appendChild(x);
            box.appendChild(chip);
        });
        const add = el('button', 'ed-tag-add', '+ 添加标签');
        add.type = 'button';
        add.addEventListener('click', () => Picker.open(add, {
            placeholder: '搜索或新建标签',
            multi: true,
            items: () => A.tags.map((t) => ({ id: t.id, label: t.name })),
            selected: () => new Set(tagIds),
            onSelect: (id) => {
                const i = tagIds.indexOf(id);
                i >= 0 ? tagIds.splice(i, 1) : tagIds.push(id);
                onChange();
            },
            onCreate: (name) => A.createTag(name).id,
            createText: (text) => `新建标签 “${text}”`,
            onClose: () => renderDraftTags(box, tagIds, onChange)
        }));
        box.appendChild(add);
    }

    function pickDraftAuthor(btn, getId, setId) {
        Picker.open(btn, {
            placeholder: '搜索或新建作者',
            items: () => A.authors.map((a) => ({ id: a.id, label: a.name, meta: a.handle ? '@' + a.handle : '' })),
            selected: () => new Set(getId() == null ? [] : [getId()]),
            clearLabel: '不设置作者',
            onSelect: (id) => { setId(id); renderDraftAuthor(btn, id); },
            onCreate: (name) => A.createAuthor(name).id,
            createText: (text) => `新建作者 “${text}”`
        });
    }

    $('ed-author').addEventListener('click', (e) => pickDraftAuthor(e.currentTarget, () => state.viewer.draft.authorId, (id) => {
        state.viewer.draft.authorId = id;
        refreshDirty();
    }));
    [['ed-title', 'title'], ['ed-source', 'source'], ['ed-note', 'note']].forEach(([id, key]) => {
        $(id).addEventListener('input', (e) => { state.viewer.draft[key] = e.target.value; refreshDirty(); });
    });

    function saveEditor() {
        const v = state.viewer;
        if (!isDirty()) { closeEditor(); return; }
        const d = v.draft;
        A.update(v.id, {
            title: d.title.replace(/\s+/g, ' ').trim(),
            authorId: d.authorId,
            tagIds: d.tagIds,
            source: d.source.trim(),
            note: d.note.trim()
        });
        closeEditor();
        renderCaption();
        if (v.info) renderInfo();
        toast('已保存');
    }

    $('edit-form').addEventListener('submit', (e) => { e.preventDefault(); saveEditor(); });
    $('edit-form').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); saveEditor(); }
    });
    $('ed-cancel').addEventListener('click', leaveEditor);
    $('ed-delete').addEventListener('click', () => deleteWorks([state.viewer.id]));

    async function deleteWorks(ids) {
        if (!ids.length) return;
        const one = ids.length === 1 ? A.get(ids[0]) : null;
        const ok = await ask({
            title: one ? `删除“${A.displayTitle(one)}”？` : `删除所选的 ${ids.length} 件作品？`,
            text: '记录和原图文件都会从图库中移除，作者与标签保留。此操作无法撤销。'
        });
        if (!ok) return;
        const v = state.viewer;
        let next = null;
        if (v) {
            const i = indexOf(v.id);
            const rest = state.results.filter((x) => !ids.includes(x.id));
            next = rest[Math.min(i, rest.length - 1)] || null;
            if (v.editing) closeEditor();
        }
        ids.forEach((id) => { pieces.delete(id); state.selected.delete(id); });
        A.remove(ids);
        toast(ids.length > 1 ? `已删除 ${ids.length} 件` : '已删除');
        if (v) next ? showWork(next.id) : closeViewer();
    }

    function openDraftTagPicker() {
        const add = $('ed-tags').querySelector('.ed-tag-add');
        if (add) add.click();
    }

    $('sv-back').addEventListener('click', closeViewer);
    $('sv-prev').addEventListener('click', () => step(-1));
    $('sv-next').addEventListener('click', () => step(1));
    $('sv-info-toggle').addEventListener('click', () => setInfo(!state.viewer.info));
    $('sv-edit-toggle').addEventListener('click', openEditor);
    $('sv-more').addEventListener('click', () => setMenu($('sv-menu').hidden));
    $('sv-fit').addEventListener('click', () => { setMenu(false); fitImage(); });
    $('sv-actual').addEventListener('click', () => { setMenu(false); zoomTo(1); });
    $('sv-delete').addEventListener('click', () => { setMenu(false); deleteWorks([state.viewer.id]); });
    $('sv-source').addEventListener('click', () => setMenu(false));
    document.addEventListener('mousedown', (e) => { if (!$('sv-menu').hidden && !e.target.closest('.sv-more')) setMenu(false); });
    function setMenu(open) {
        $('sv-menu').hidden = !open;
        $('sv-more').setAttribute('aria-expanded', String(open));
    }
    // 信息区 / 编辑面板展开收起、窗口缩放都会改变画布尺寸：适应模式下重新适应，否则只校正平移
    new ResizeObserver(() => {
        if (!state.viewer || !state.viewer.w) return;
        state.viewer.fit ? fitImage() : (clampPan(), applyTransform(false));
    }).observe(canvas);

    /* ---------------- 导入 ---------------- */

    let importing = false;
    let batch = null;

    function openImport() {
        if (!importing && !batch) {
            $('import-list').innerHTML = '';
            $('dropzone').classList.remove('is-compact');
            $('tidy').hidden = true;
            $('import-summary').textContent = '';
            $('import-skip').hidden = true;
            $('import-done').hidden = true;
        }
        $('import-sheet').hidden = false;
    }

    function closeImport() {
        if (importing) { toast('导入完成后再关闭'); return; }
        $('import-sheet').hidden = true;
        batch = null;
    }

    async function startImport(fileList) {
        const files = [...fileList];
        if (!files.length) return;
        if (importing) { toast('请等待当前这批导入完成'); return; }
        openImport();
        importing = true;
        batch = { ids: [], authorId: null, tagIds: [] };
        $('dropzone').classList.add('is-compact');
        $('tidy').hidden = true;
        $('import-skip').hidden = true;
        $('import-done').hidden = true;
        const list = $('import-list');
        list.innerHTML = '';
        const rows = files.map((f) => {
            const li = el('li');
            const thumb = el(/^image\//.test(f.type) ? 'img' : 'span', 'thumb');
            if (thumb.tagName === 'IMG') { thumb.src = URL.createObjectURL(f); thumb.alt = ''; }
            li.appendChild(thumb);
            const name = el('div');
            name.appendChild(el('span', 'name', f.name));
            name.appendChild(el('small', null, A.formatSize(f.size)));
            li.appendChild(name);
            li.appendChild(el('span', 'st st-pending', '等待'));
            list.appendChild(li);
            return li;
        });
        $('import-summary').textContent = `正在导入 0 / ${files.length}`;
        const label = { success: '已导入', duplicate: '已在图库中', failed: '失败' };
        const results = await A.importFiles(files, (r, i, n) => {
            const st = rows[i].querySelector('.st');
            st.className = 'st st-' + r.status;
            st.textContent = label[r.status];
            if (r.message) rows[i].querySelector('small').textContent = r.message;
            $('import-summary').textContent = `正在导入 ${i + 1} / ${n}`;
        });
        importing = false;
        const count = (s) => results.filter((r) => r.status === s).length;
        batch.ids = results.filter((r) => r.work).map((r) => r.work.id);
        $('import-summary').textContent = [`成功 ${count('success')}`, count('duplicate') && `重复 ${count('duplicate')}`, count('failed') && `失败 ${count('failed')}`].filter(Boolean).join(' · ');
        $('import-done').hidden = false;
        if (batch.ids.length) {
            $('tidy').hidden = false;
            $('tidy-desc').textContent = `为刚导入的 ${batch.ids.length} 件作品统一设置，之后也可以逐件修改。`;
            renderDraftAuthor($('tidy-author'), null);
            renderDraftTags($('tidy-tags'), batch.tagIds, () => {});
            $('import-skip').hidden = false;
        }
        $('import-done').focus();
    }

    $('tidy-author').addEventListener('click', (e) => pickDraftAuthor(e.currentTarget, () => batch.authorId, (id) => { batch.authorId = id; }));

    function finishImport(applyTidy) {
        const b = batch;
        if (b && b.ids.length) {
            if (applyTidy && (b.authorId != null || b.tagIds.length)) {
                A.updateMany(b.ids, { addTagIds: b.tagIds, ...(b.authorId != null ? { authorId: b.authorId } : {}) });
            }
            state.scope = 'recent';
            state.text = '';
            q.value = '';
            state.authorIds.clear();
            state.tagIds.clear();
            state.sort = 'newest';
            $('sort').value = 'newest';
            runQuery();
            window.scrollTo({ top: 0 });
            toast(`已导入 ${b.ids.length} 件作品`);
        }
        batch = null;
        $('import-sheet').hidden = true;
    }

    $('import-open').addEventListener('click', openImport);
    $('import-close').addEventListener('click', closeImport);
    $('import-pick').addEventListener('click', () => $('file-input').click());
    $('file-input').addEventListener('change', (e) => { startImport(e.target.files); e.target.value = ''; });
    $('import-done').addEventListener('click', () => finishImport(true));
    $('import-skip').addEventListener('click', () => finishImport(false));
    $('import-sheet').addEventListener('mousedown', (e) => { if (e.target === $('import-sheet')) closeImport(); });

    let dragDepth = 0;
    // Browser image drags may contain Files too; only drags from outside this page should import.
    const internalDragType = 'application/x-illustration-archive-internal-drag';
    window.addEventListener('dragstart', (e) => {
        e.dataTransfer?.setData(internalDragType, '1');
    });
    const hasExternalFiles = (e) => {
        const types = [...(e.dataTransfer?.types || [])];
        return types.includes('Files') && !types.includes(internalDragType);
    };
    window.addEventListener('dragenter', (e) => {
        if (!hasExternalFiles(e)) return;
        e.preventDefault();
        dragDepth++;
        if (!state.viewer) openImport();
        $('dropzone').classList.add('is-over');
    });
    window.addEventListener('dragover', (e) => { if (hasExternalFiles(e)) e.preventDefault(); });
    window.addEventListener('dragleave', () => {
        dragDepth = Math.max(0, dragDepth - 1);
        if (!dragDepth) $('dropzone').classList.remove('is-over');
    });
    window.addEventListener('drop', (e) => {
        if (!hasExternalFiles(e)) return;
        e.preventDefault();
        dragDepth = 0;
        $('dropzone').classList.remove('is-over');
        if (state.viewer) return;
        startImport(e.dataTransfer.files);
    });

    /* ---------------- 键盘 ---------------- */

    document.addEventListener('keydown', (e) => {
        if (!$('confirm').hidden) {
            if (e.key === 'Escape') answer(false);
            return;
        }
        if (!$('shortcuts').hidden) {
            if (e.key === 'Escape' || e.key === '?') { e.preventDefault(); $('shortcuts').hidden = true; }
            return;
        }
        const typing = e.target instanceof Element && e.target.matches('input, textarea, select');
        const ctrl = e.ctrlKey || e.metaKey;
        if (ctrl && e.key.toLowerCase() === 'o') { e.preventDefault(); if (!state.viewer) { openImport(); $('file-input').click(); } return; }

        if (!$('import-sheet').hidden) {
            if (e.key === 'Escape') closeImport();
            return;
        }

        const v = state.viewer;
        if (v) {
            if (e.key === 'Escape') {
                if (!$('sv-menu').hidden) setMenu(false);
                else if (v.editing) leaveEditor();
                else if (v.info) setInfo(false);
                else closeViewer();
                return;
            }
            if (typing) return;
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
                case 'e': case 'E': if (!v.editing) { e.preventDefault(); openEditor(); } break;
                case 't': case 'T': e.preventDefault(); if (!v.editing) openEditor(); openDraftTagPicker(); break;
                case 'Delete': deleteWorks([v.id]); break;
                case '?': openShortcuts(); break;
                default: return;
            }
            showChrome();
            return;
        }

        if (typing || Picker.isOpen()) return;
        if (e.key === '?') { openShortcuts(); return; }
        if (e.key === '/' || (ctrl && e.key.toLowerCase() === 'k')) { e.preventDefault(); q.focus(); return; }
        if (ctrl && e.key.toLowerCase() === 'a') {
            e.preventDefault();
            state.results.forEach((w) => state.selected.add(w.id));
            setSelecting(true);
            return;
        }
        // 焦点在“编辑”“作者”等其他按钮上时，Enter / 空格保留按钮自身的行为
        const active = document.activeElement;
        const onPiece = !active || active === document.body || active.classList.contains('piece-open');
        const activePiece = active && active.closest ? active.closest('.piece') : null;
        if (activePiece) state.focusId = Number(activePiece.dataset.id);
        const focus = state.focusId != null && A.get(state.focusId) ? state.focusId : null;
        if ((e.key === 'Enter' || e.key === ' ') && !onPiece) return;
        switch (e.key) {
            case 'ArrowLeft': case 'ArrowRight': case 'ArrowUp': case 'ArrowDown':
                e.preventDefault();
                moveFocus(e.key, e.shiftKey);
                break;
            case 'Enter': if (focus != null) { e.preventDefault(); openViewer(focus); } break;
            case ' ': if (focus != null) { e.preventDefault(); toggleSelect(focus); } break;
            case 'Escape': if (state.selecting) setSelecting(false); break;
            case 'Delete':
                if (state.selected.size) deleteWorks([...state.selected]);
                else if (focus != null) deleteWorks([focus]);
                break;
            case 'e': case 'E': if (focus != null) { e.preventDefault(); openViewer(focus, { edit: true }); } break;
            case 't': case 'T':
                e.preventDefault();
                if (state.selected.size) batchTags($('bb-tags'));
                else if (focus != null) { openViewer(focus, { edit: true }); openDraftTagPicker(); }
                break;
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

    A.subscribe((detail) => {
        if (detail.type === 'update' || detail.type === 'remove' || detail.type === 'import') {
            if (detail.type === 'update') detail.ids.forEach((id) => { const w = A.get(id); if (w) pieceFor(w); });
            runQuery();
        }
    });

    // 供动效实验层读取看图器状态、复用平移约束；原版不依赖它
    window.GalleryApi = { state, pieces, canvas, img, viewer, clampPan, applyTransform, fitImage, renderWall };

    runQuery();
})();
