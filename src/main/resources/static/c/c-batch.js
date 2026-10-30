/*
 * C 图库 · 批量整理面板
 *
 * 作品墙的选择模式在 c-gallery.js；这里只负责选好之后的“整理”面板：统一改作者、添加或移除标签。
 * - 改动先在面板里写成草稿，看清要改什么再应用，避免点一下就改掉很多件作品。
 * - 应用时只发一次请求（archive-client.js 的 saveBatch），服务端在一个事务里完成：要么全部改好，要么都不改。
 *   失败时草稿保留；连接中断时无法确认结果，可以直接重试，重复应用不会出错。
 * - 标签按“添加 / 移除”提交，不整组替换：只有部分作品带着的标签，不动它就不会被改掉。
 * - 面板打开期间背后的图库设为 inert，按键不落到图库快捷键上。
 */
(function () {
    'use strict';

    const A = window.Archive;
    const F = window.CFilters;
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

    const sheet = $('batch-sheet');
    const authorButton = $('batch-author');
    const authorReset = $('batch-author-reset');
    const tagsBox = $('batch-tags');
    const errorBox = $('batch-error');
    const applyButton = $('batch-apply');
    const addTag = el('button', 'ed-tag-add', '+ 添加标签');
    addTag.type = 'button';
    addTag.setAttribute('aria-haspopup', 'dialog');
    addTag.setAttribute('aria-expanded', 'false');
    tagsBox.appendChild(addTag);

    // ids 所选作品；counts 已有标签及其件数（null 表示没读到）；author undefined 不改，{ id } 改成某位作者，id 为 null 表示清空
    let s = null;

    const fullyTagged = (id) => {
        const tag = s.counts && s.counts.find((t) => t.id === id);
        return !!tag && tag.count === s.ids.length;
    };

    function currentAuthors() {
        const seen = new Set();
        let unset = 0;
        s.ids.forEach((id) => {
            const work = A.get(id);
            if (work && work.author) seen.add(work.author.id);
            else unset++;
        });
        if (!seen.size) return '目前都没有作者';
        if (seen.size === 1 && !unset) return `目前都是 ${F.nameOf('author', [...seen][0])}`;
        return `目前有 ${seen.size} 位作者${unset ? `，${unset} 件没有作者` : ''}`;
    }

    function hasChanges() {
        return s.author !== undefined || s.add.size > 0 || s.remove.size > 0;
    }

    function chip(cls, label, count, actions) {
        const node = el('span', `ed-tag ${cls}`);
        node.appendChild(el('span', 'bt-label', label));
        if (count) node.appendChild(el('small', 'bt-count', count));
        actions.forEach(([text, title, run]) => {
            const b = el('button', 'bt-action', text);
            b.type = 'button';
            b.title = title;
            b.setAttribute('aria-label', `${title}：${label}`);
            b.disabled = s.saving;
            b.addEventListener('click', () => { run(); render(); });
            node.appendChild(b);
        });
        return node;
    }

    function renderTags() {
        tagsBox.querySelectorAll('.ed-tag, .bt-status').forEach((node) => node.remove());
        const n = s.ids.length;
        const chips = [];
        if (s.counts) {
            s.counts.forEach((tag) => {
                const undo = ['撤销', '撤销这项修改', () => { s.remove.delete(tag.id); s.add.delete(tag.id); }];
                if (s.remove.has(tag.id)) chips.push(chip('is-removing', tag.name, '将移除', [undo]));
                else if (s.add.has(tag.id)) chips.push(chip('', tag.name, '补到全部', [undo]));
                else {
                    const actions = [['×', '从这些作品移除', () => s.remove.add(tag.id)]];
                    if (tag.count < n) actions.unshift(['补齐', '加到其余作品上', () => s.add.add(tag.id)]);
                    chips.push(chip('is-kept', tag.name, tag.count < n ? `${tag.count}/${n}` : '', actions));
                }
            });
        }
        s.add.forEach((id) => {
            if (s.counts && s.counts.some((t) => t.id === id)) return;
            chips.push(chip('', F.nameOf('tag', id), '将添加', [['×', '不添加', () => s.add.delete(id)]]));
        });
        chips.forEach((node) => tagsBox.insertBefore(node, addTag));
        if (s.countsError) {
            const line = el('span', 'bt-status', '已有标签没有读到，仍可以添加。');
            const retry = el('button', 'link', '重试');
            retry.type = 'button';
            retry.addEventListener('click', loadCounts);
            line.appendChild(retry);
            tagsBox.insertBefore(line, addTag);
        } else if (!s.counts) tagsBox.insertBefore(el('span', 'bt-status', '正在读取已有标签…'), addTag);
    }

    function summaryText() {
        const parts = [];
        if (s.author) parts.push(s.author.id == null ? '清空作者' : `作者改为 ${F.nameOf('author', s.author.id)}`);
        if (s.add.size) parts.push(`添加 ${s.add.size} 个标签`);
        if (s.remove.size) parts.push(`移除 ${s.remove.size} 个标签`);
        return parts.join('，') || '还没有改动';
    }

    function render() {
        const n = s.ids.length;
        $('batch-title').textContent = `整理 ${n} 件作品`;
        authorButton.textContent = '';
        authorButton.appendChild(el('span', null, s.author === undefined ? '保持不变'
            : s.author.id == null ? '清空作者' : F.nameOf('author', s.author.id)));
        authorButton.classList.toggle('is-empty', s.author === undefined);
        authorButton.disabled = s.saving;
        $('batch-author-note').textContent = currentAuthors();
        authorReset.textContent = s.author === undefined ? '清空' : '撤销';
        authorReset.title = s.author === undefined ? '清空这些作品的作者' : '恢复为保持不变';
        authorReset.disabled = s.saving;
        addTag.disabled = s.saving;
        renderTags();
        $('batch-summary').textContent = summaryText();
        applyButton.textContent = s.saving ? '正在应用…' : `应用到 ${n} 件`;
        applyButton.disabled = s.saving || !hasChanges();
        $('batch-cancel').disabled = s.saving;
        $('batch-close').disabled = s.saving;
        errorBox.hidden = !s.error;
        errorBox.textContent = s.error || '';
    }

    function renderCovers() {
        const box = $('batch-covers');
        box.innerHTML = '';
        const shown = s.ids.slice(0, 6);
        shown.forEach((id) => {
            const work = A.get(id);
            if (!work) return;
            const image = el('img');
            image.alt = '';
            image.draggable = false;
            image.src = work.pages[0].src;
            box.appendChild(image);
        });
        if (s.ids.length > shown.length) box.appendChild(el('span', 'batch-more', `+${s.ids.length - shown.length}`));
    }

    function loadCounts() {
        const mine = s;
        mine.countsError = null;
        render();
        A.batchTagCounts(mine.ids).then((counts) => {
            if (s !== mine) return;
            mine.counts = counts;
            render();
        }).catch((error) => {
            if (s !== mine) return;
            mine.countsError = error;
            render();
        });
    }

    function open(ids) {
        if (s || !ids.length) return;
        if (ids.length > A.BATCH_MAX) {
            host('toast', `一次最多整理 ${A.BATCH_MAX} 件，请减少选择`);
            return;
        }
        s = { ids: [...ids], counts: null, countsError: null, author: undefined, add: new Set(), remove: new Set(), saving: false, error: null };
        sheet.hidden = false;
        host('setBackgroundInert', true);
        renderCovers();
        loadCounts();
        authorButton.focus();
    }

    function close({ restoreFocus = true } = {}) {
        if (!s || s.saving) return false;
        F.closePicker();
        s = null;
        sheet.hidden = true;
        host('setBackgroundInert', false);
        if (restoreFocus) $('bb-edit').focus();
        return true;
    }

    async function apply() {
        if (!s || s.saving || !hasChanges()) return;
        const mine = s;
        F.closePicker();
        mine.saving = true;
        mine.error = null;
        render();
        const change = { addTagIds: [...mine.add], removeTagIds: [...mine.remove] };
        if (mine.author !== undefined) change.authorId = mine.author.id;
        try {
            const result = await A.saveBatch(mine.ids, change);
            mine.saving = false;
            close({ restoreFocus: false });
            host('endSelecting');
            host('toast', result.refreshError
                ? `已保存，但图库列表没能刷新：${result.refreshError.message}。刷新页面即可更新。`
                : `已更新 ${mine.ids.length} 件作品`);
        } catch (error) {
            mine.saving = false;
            mine.error = error.message;
            render();
        }
    }

    authorButton.addEventListener('click', () => {
        if (!s || s.saving) return;
        F.openPicker('author', {
            anchor: authorButton,
            single: true,
            title: '选择作者',
            hint: '所选作品都会改成这位作者；新建作者请到旧图库',
            selected: () => (s.author && s.author.id != null ? [s.author.id] : []),
            onToggle: (id) => { s.author = { id }; render(); }
        });
    });
    authorReset.addEventListener('click', () => {
        if (!s) return;
        s.author = s.author === undefined ? { id: null } : undefined;
        render();
    });
    addTag.addEventListener('click', () => {
        if (!s || s.saving) return;
        F.openPicker('tag', {
            anchor: addTag,
            title: '添加标签',
            hint: '勾选的标签会加到所选作品上；新建标签请到旧图库',
            selected: () => {
                const on = new Set(s.add);
                (s.counts || []).forEach((t) => { if (t.count === s.ids.length && !s.remove.has(t.id)) on.add(t.id); });
                return [...on];
            },
            onToggle: (id) => {
                // 已经全部带着的标签，在这里取消勾选就是移除；其余的是添加
                if (fullyTagged(id)) s.remove.has(id) ? s.remove.delete(id) : s.remove.add(id);
                else if (s.add.has(id)) s.add.delete(id);
                else { s.add.add(id); s.remove.delete(id); }
                render();
            }
        });
    });
    applyButton.addEventListener('click', apply);
    $('batch-cancel').addEventListener('click', () => close());
    $('batch-close').addEventListener('click', () => close());
    sheet.addEventListener('mousedown', (e) => { if (e.target === sheet) close(); });

    window.CBatch = { open, close, isOpen: () => !!s };
})();
