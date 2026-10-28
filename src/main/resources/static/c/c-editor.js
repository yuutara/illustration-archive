/*
 * C 图库 · 单件编辑
 *
 * 卡片上的“编辑”、看图器里的“编辑”和 E 键都走这里：在看图器右侧打开编辑面板。
 * - 先强制重读这件作品的完整详情，读完之前字段不可编辑，不会把没载入的字段当成空值保存。
 * - 草稿与原值比较，只把改动过的字段 PATCH 给服务端（archive-client.js 的 editSession / buildPatch）。
 * - 保存中按钮与字段都锁定，重复提交被忽略；失败时草稿原样保留并显示原因，可以直接重试。
 * - 取消、Esc、关闭看图器、切换作品、点作者 / 标签离开：都经过 requestLeave()，有未保存的修改先确认；保存中不放行。
 * - 面板与确认框里的按键不会落到图库快捷键上。
 * 作者 / 标签只能从已有的里选（复用筛选的候选弹层：服务端搜索、翻页）；新建与管理仍在旧图库。
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
    const icon = (name) => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
    const host = (name, ...args) => {
        const api = window.GalleryApi;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };

    const viewer = $('viewer');
    const panel = $('sv-editor');
    const form = $('edit-form');
    const fields = $('ed-fields');
    const titleInput = $('ed-title');
    const sourceInput = $('ed-source');
    const noteInput = $('ed-note');
    const authorButton = $('ed-author');
    const authorClear = $('ed-author-clear');
    const tagsBox = $('ed-tags');
    const status = $('ed-status');
    const errorBox = $('ed-error');
    const dirtyMark = $('ed-dirty');
    const saveButton = $('ed-save');
    const cancelButton = $('ed-cancel');
    const editToggle = $('sv-edit-toggle');

    // “添加标签”按钮常驻，标签条目插在它前面：弹层贴着它时重绘不会把锚点换掉
    const addTag = el('button', 'ed-tag-add', '+ 添加标签');
    addTag.type = 'button';
    tagsBox.appendChild(addTag);

    let session = null;

    const draft = () => session.state.draft;

    /* ---------------- 打开与关闭 ---------------- */

    function open(id) {
        if (session && session.state.id === id) {
            focusFirst();
            return;
        }
        close();
        const mine = A.editSession(id);
        session = mine;
        mine.onChange(() => { if (session === mine) render(); });
        panel.hidden = false;
        viewer.classList.add('is-editing');
        viewer.style.setProperty('--editor', window.innerWidth <= 1080 ? '360px' : '420px');
        editToggle.hidden = true;
        clearInputs();
        render();
        host('showChrome');
        load(mine);
    }

    function load(mine) {
        mine.load().then((ok) => {
            if (session !== mine || !ok) return;
            const detail = A.get(mine.state.id).detail;
            if (detail.author) F.learn('author', detail.author);
            detail.tags.forEach((t) => F.learn('tag', t));
            fill();
            render();
            focusFirst();
        });
    }

    function close() {
        if (!session) return;
        session.dispose();
        session = null;
        F.closePicker();
        panel.hidden = true;
        viewer.classList.remove('is-editing');
        viewer.style.setProperty('--editor', '0px');
        editToggle.hidden = false;
        errorBox.hidden = true;
        clearInputs();
    }

    function clearInputs() {
        titleInput.value = '';
        sourceInput.value = '';
        noteInput.value = '';
    }

    function fill() {
        const d = draft();
        titleInput.value = d.title;
        sourceInput.value = d.sourceUrl;
        noteInput.value = d.note;
    }

    function focusFirst() {
        if (session && session.state.phase === 'ready') titleInput.focus({ preventScroll: true });
    }

    /* ---------------- 显示 ---------------- */

    function render() {
        if (!session) return;
        const s = session.state;
        const ready = s.phase === 'ready';
        const saving = s.phase === 'saving';
        fields.disabled = !ready;
        panel.setAttribute('aria-busy', String(s.phase === 'loading' || saving));

        status.innerHTML = '';
        status.className = 'ed-status';
        status.hidden = !(s.phase === 'loading' || s.phase === 'error');
        if (s.phase === 'loading') {
            status.classList.add('is-loading');
            status.textContent = '正在载入作品信息…';
        } else if (s.phase === 'error') {
            status.classList.add('is-error');
            status.append(`作品信息载入失败：${s.error}。读完之前不能编辑。　`);
            const retry = el('button', 'link', '重试');
            retry.type = 'button';
            retry.addEventListener('click', () => load(session));
            status.appendChild(retry);
        }

        const dirty = session.dirty();
        dirtyMark.hidden = !dirty;
        saveButton.disabled = !(ready && dirty);
        saveButton.textContent = saving ? '正在保存…' : '保存';
        cancelButton.disabled = saving;
        errorBox.hidden = !(ready && s.error);
        errorBox.textContent = ready && s.error ? `保存失败：${s.error}。修改仍保留在这里，可以重试或取消。` : '';

        if (s.draft) {
            renderAuthor();
            renderTags();
        }
    }

    function renderAuthor() {
        const id = draft().authorId;
        authorButton.innerHTML = '';
        authorButton.classList.toggle('is-empty', id == null);
        const label = el('span', null, id == null ? '未设置作者（点此选择）' : F.nameOf('author', id));
        const known = id == null ? null : F.known('author', id);
        if (known && known.meta) label.appendChild(el('span', 'handle', '　' + known.meta));
        authorButton.appendChild(label);
        authorClear.hidden = id == null;
    }

    function renderTags() {
        tagsBox.querySelectorAll('.ed-tag').forEach((chip) => chip.remove());
        const ids = draft().tagIds;
        ids.forEach((id) => {
            const name = F.nameOf('tag', id);
            const chip = el('span', 'ed-tag', name);
            const x = el('button');
            x.type = 'button';
            x.setAttribute('aria-label', '移除标签 ' + name);
            x.title = '移除';
            x.innerHTML = icon('close');
            x.addEventListener('click', () => {
                session.set('tagIds', draft().tagIds.filter((t) => t !== id));
                addTag.focus();
            });
            chip.appendChild(x);
            tagsBox.insertBefore(chip, addTag);
        });
        addTag.textContent = ids.length ? '+ 添加标签' : '+ 添加标签（还没有）';
    }

    /* ---------------- 字段 ---------------- */

    [[titleInput, 'title'], [sourceInput, 'sourceUrl'], [noteInput, 'note']].forEach(([input, key]) => {
        input.addEventListener('input', () => { if (session) session.set(key, input.value); });
    });

    authorButton.addEventListener('click', () => {
        if (!session || session.state.phase !== 'ready') return;
        F.openPicker('author', {
            anchor: authorButton,
            single: true,
            title: '选择作者',
            hint: '从已有作者中选择；新建或修改作者请到旧图库',
            selected: () => (draft().authorId == null ? [] : [draft().authorId]),
            onToggle: (id) => session.set('authorId', id),
            clearLabel: '不设置作者',
            onClear: () => session.set('authorId', null)
        });
    });
    authorClear.addEventListener('click', () => {
        if (!session) return;
        session.set('authorId', null);
        authorButton.focus();
    });

    addTag.addEventListener('click', () => {
        if (!session || session.state.phase !== 'ready') return;
        F.openPicker('tag', {
            anchor: addTag,
            title: '选择标签',
            hint: '可多选；新建标签请到旧图库',
            selected: () => draft().tagIds,
            onToggle: (id) => {
                const ids = draft().tagIds;
                session.set('tagIds', ids.includes(id) ? ids.filter((t) => t !== id) : [...ids, id]);
            }
        });
    });

    /* ---------------- 保存、取消、离开 ---------------- */

    async function save() {
        if (!session) return;
        const mine = session;
        if (mine.state.phase !== 'ready') return; // 读取中、保存中都不提交
        if (!mine.dirty()) {
            close();
            return;
        }
        F.closePicker();
        const result = await mine.save();
        if (session !== mine || !result) {
            if (session === mine) saveButton.focus();
            return;
        }
        close();
        host('toast', savedMessage(result));
    }

    function savedMessage(result) {
        if (result.refreshError) return `已保存，但图库列表没能按新条件刷新：${result.refreshError.message}。刷新页面即可更新。`;
        if (result.detailError) return `已保存，但重新读取作品信息失败：${result.detailError.message}`;
        if (!result.listed) return '已保存。这件作品已不符合当前的搜索或筛选条件，回到图库后不再显示';
        return '已保存';
    }

    // 离开编辑：没有改动直接关闭；有改动先确认；保存中不放行。返回是否已经离开
    async function requestLeave() {
        if (!session) return true;
        if (session.state.phase === 'saving') {
            host('toast', '正在保存，请稍候');
            return false;
        }
        if (!session.dirty()) {
            close();
            return true;
        }
        const mine = session;
        const discard = await confirmDiscard();
        if (session !== mine) return true;
        if (discard) close();
        else focusFirst();
        return discard;
    }

    form.addEventListener('submit', (e) => {
        e.preventDefault();
        save();
    });
    cancelButton.addEventListener('click', () => requestLeave());
    form.addEventListener('keydown', (e) => {
        // 编辑面板里的按键只归编辑器：不触发看图器翻页、缩放等快捷键
        e.stopPropagation();
        if (e.isComposing || e.keyCode === 229) return;
        if (e.key === 'Escape') {
            e.preventDefault();
            requestLeave();
        } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            save();
        } else if (e.key === 'Enter' && e.target.tagName === 'INPUT') {
            e.preventDefault(); // 单行输入框里的 Enter 不提交，避免打字时误保存
        }
    });
    // 兜底：有未保存的修改或正在保存时，任何换查询都被数据层拒绝（正常路径会先经过 requestLeave 确认）
    A.setQueryGuard(() => !session || (session.state.phase !== 'saving' && !session.dirty()));

    window.addEventListener('beforeunload', (e) => {
        if (session && (session.dirty() || session.state.phase === 'saving')) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    /* ---------------- 放弃修改的确认 ---------------- */

    const confirmBox = $('confirm');
    let confirming = null;

    function confirmDiscard() {
        if (confirming) return confirming.promise;
        let resolve;
        const promise = new Promise((r) => { resolve = r; });
        confirming = { promise, resolve, returnFocus: document.activeElement };
        confirmBox.hidden = false;
        $('confirm-cancel').focus();
        return promise;
    }

    function answer(discard) {
        if (!confirming) return;
        const { resolve, returnFocus } = confirming;
        confirming = null;
        confirmBox.hidden = true;
        if (!discard && returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') returnFocus.focus();
        resolve(discard);
    }

    $('confirm-ok').addEventListener('click', () => answer(true));
    $('confirm-cancel').addEventListener('click', () => answer(false));
    confirmBox.addEventListener('mousedown', (e) => { if (e.target === confirmBox) answer(false); });
    confirmBox.addEventListener('keydown', (e) => {
        e.stopPropagation();
        if (e.key === 'Escape') {
            e.preventDefault();
            answer(false);
        } else if (e.key === 'Tab') {
            // 焦点只在两个按钮之间切换
            e.preventDefault();
            (document.activeElement === $('confirm-ok') ? $('confirm-cancel') : $('confirm-ok')).focus();
        }
    });

    window.CEditor = {
        open,
        close,
        save,
        requestLeave,
        isOpen: () => !!session,
        editingId: () => (session ? session.state.id : null),
        isDirty: () => !!session && session.dirty(),
        isConfirming: () => !!confirming
    };
})();
