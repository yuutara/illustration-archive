/*
 * C 图库 · 单件删除
 *
 * 看图器“更多 → 删除作品”、看图器里的 Delete 键、作品墙上选中卡片时的 Delete 键都走 request()，打开同一个确认框。
 * - 确认框写明作品名称、图片数量，以及“永久删除、无法恢复”；默认焦点在“取消”，Esc、点背景、“取消”都不删除，
 *   当前作品和编辑面板里的草稿原样保留。编辑正在保存时不能删除。
 * - 删除中两个按钮都锁定，重复点击、连按都不会再次提交；结果只按服务端回应显示（archive-client.js 的 deleteWork）：
 *   已删除 / 本就不存在 → 关闭确认框，从列表移除，回到图库，焦点落在原位置的下一件；
 *   确定没有删除 → 说明原因，可以重试；
 *   无法确认（回执丢失、重新读取也失败）→ 如实说明，作品暂时留在列表里，可以“重新核实”（只读取，不再删除）。
 * - 确认框打开期间背后的看图器或图库设为 inert，Tab 只在确认框的按钮之间切换，按键不落到图库快捷键上。
 */
(function () {
    'use strict';

    const A = window.Archive;
    const $ = (id) => document.getElementById(id);
    const host = (name, ...args) => {
        const api = window.GalleryApi;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };
    const editor = (name, ...args) => {
        const api = window.CEditor;
        return api && typeof api[name] === 'function' ? api[name](...args) : undefined;
    };

    const box = $('delete-confirm');
    const dialog = $('del-dialog');
    const heading = $('del-title');
    const cover = $('del-cover');
    const thumb = $('del-thumb');
    const nameText = $('del-name');
    const metaText = $('del-meta');
    const explain = $('del-text');
    const draftNote = $('del-draft');
    const statusBox = $('del-status');
    const okButton = $('del-ok');
    const cancelButton = $('del-cancel');
    const viewer = $('viewer');

    const isDesktop = () => !!(window.UiCapabilities && window.UiCapabilities.desktop);
    const OK_TEXT = () => isDesktop()
        ? { confirm: '移入回收站', deleting: '正在移入…', kept: '重试移入', unknown: '重新核实', verifying: '正在核实…' }
        : { confirm: '永久删除', deleting: '正在删除…', kept: '重试删除', unknown: '重新核实', verifying: '正在核实…' };

    /*
     * 一次删除：confirm 等待确认 → deleting 删除中 → 成功即关闭；
     * kept 确定没有删除（可重试）；unknown 无法确认 → verifying 重新读取核实。
     */
    let current = null;

    const busy = () => !!current && (current.phase === 'deleting' || current.phase === 'verifying');

    /* ---------------- 打开与关闭 ---------------- */

    function request(id, { returnFocus = document.activeElement } = {}) {
        if (current) return false;
        const work = A.get(id);
        if (!work) return false;
        if (editor('isSaving')) {
            host('toast', '正在保存修改，保存完成后才能删除');
            return false;
        }
        current = {
            id, phase: 'confirm', message: '', returnFocus,
            title: A.displayTitle(work), pages: work.pages.length,
            behind: host('isViewerOpen') ? 'viewer' : 'gallery'
        };
        if (current.behind === 'viewer') viewer.inert = true;
        else host('setBackgroundInert', true);
        fill(work);
        box.hidden = false;
        render();
        cancelButton.focus();
        return true;
    }

    function requestBatch(ids, { returnFocus = document.activeElement } = {}) {
        if (current || !isDesktop()) return false;
        const works = (ids || []).map((id) => A.get(id)).filter(Boolean);
        if (!works.length) return false;
        if (editor('isSaving')) {
            host('toast', '正在保存修改，保存完成后才能删除');
            return false;
        }
        const totalPages = works.reduce((sum, w) => sum + w.pages.length, 0);
        current = {
            ids: works.map((w) => w.id), batch: true, phase: 'confirm', message: '', returnFocus,
            title: `已选 ${works.length} 件作品`, pages: totalPages,
            behind: 'gallery'
        };
        host('setBackgroundInert', true);
        fillBatch(works, totalPages);
        box.hidden = false;
        render();
        cancelButton.focus();
        return true;
    }

    function close({ restoreFocus }) {
        const mine = current;
        current = null;
        box.hidden = true;
        if (mine.behind === 'viewer') viewer.inert = false;
        else host('setBackgroundInert', false);
        if (!restoreFocus) {
            // 隐藏确认框不会立刻移走它身上的焦点：先交还给页面，图库随后据此把焦点安排到下一件或空状态
            const held = document.activeElement;
            if (held && box.contains(held)) held.blur();
            return;
        }
        const back = mine.returnFocus;
        if (back && back.isConnected && typeof back.focus === 'function') back.focus({ preventScroll: true });
        else if (mine.behind === 'viewer' && host('isViewerOpen')) viewer.focus({ preventScroll: true });
    }

    /* ---------------- 显示 ---------------- */

    function fill(work) {
        const n = work.pages.length;
        thumb.src = work.pages[0].src;
        cover.classList.toggle('is-stack', n > 1);
        nameText.textContent = current.title;
        const author = A.authorName(work);
        metaText.textContent = (author ? author + '　·　' : '') + (n > 1 ? `${n} 页，共 ${n} 张图片` : '1 张图片');
        if (isDesktop()) {
            explain.textContent = (n > 1 ? `这件作品和全部 ${n} 张图片` : '这件作品和它的图片')
                + '会移入回收站，可在 30 天内恢复；到期后自动永久清理。';
        } else {
            explain.textContent = (n > 1 ? `作品记录和全部 ${n} 张图片` : '作品记录和它的图片')
                + '都会从本机图库中永久删除，无法恢复。';
        }
        const dirty = editor('editingId') === work.id && editor('isDirty');
        draftNote.hidden = !dirty;
        draftNote.textContent = dirty ? '编辑面板里还有未保存的修改，移走后会一并放弃。' : '';
    }

    function fillBatch(works, totalPages) {
        const first = works[0];
        thumb.src = first.pages[0].src;
        cover.classList.toggle('is-stack', works.length > 1 || first.pages.length > 1);
        nameText.textContent = current.title;
        metaText.textContent = `共包含 ${totalPages} 张图片或视频`;
        explain.textContent = `这 ${works.length} 件作品都会移入回收站，可在回收站中恢复；30 天后自动永久清理。`;
        draftNote.hidden = true;
        draftNote.textContent = '';
    }

    function render() {
        const p = current.phase;
        const checking = p === 'unknown' || p === 'verifying';
        box.setAttribute('aria-busy', String(busy()));
        if (checking) heading.textContent = '无法确认是否已删除';
        else if (current.batch) heading.textContent = `将这 ${current.ids.length} 件作品移入回收站？`;
        else heading.textContent = isDesktop() ? '将这件作品移入回收站？' : '永久删除这件作品？';
        const labels = OK_TEXT();
        okButton.textContent = current.batch && p === 'confirm' ? `移入回收站（${current.ids.length} 件）` : labels[p];
        okButton.className = checking ? 'btn btn-ink' : 'btn btn-danger';
        okButton.disabled = busy();
        cancelButton.textContent = checking ? '关闭' : '取消';
        cancelButton.disabled = busy();
        statusBox.hidden = !current.message;
        statusBox.className = 'del-status' + (checking ? ' is-unknown' : ' is-error');
        statusBox.textContent = current.message;
    }

    /* ---------------- 删除、核实、取消 ---------------- */

    async function proceed() {
        const mine = current;
        if (!mine || busy()) return;
        if (mine.phase === 'confirm' || mine.phase === 'kept') {
            if (editor('isSaving')) {
                host('toast', '正在保存修改，保存完成后才能删除');
                return;
            }
            mine.phase = 'deleting';
        } else mine.phase = 'verifying';
        mine.message = '';
        render();
        // 按钮锁定后焦点留在确认框上，按键仍由它接住
        dialog.focus({ preventScroll: true });
        if (mine.batch) {
            try {
                const outcome = await A.moveBatchToTrash(mine.ids);
                if (current !== mine) return;
                close({ restoreFocus: false });
                const movedIds = outcome.movedIds && outcome.movedIds.length ? outcome.movedIds : mine.ids;
                await A.removeWorks(movedIds);
                if (window.AppShell && typeof window.AppShell.refreshTrash === 'function') window.AppShell.refreshTrash();
                host('toast', `已将 ${outcome.movedCount} 件作品移入回收站`);
            } catch (error) {
                if (current !== mine) return;
                mine.phase = 'kept';
                mine.message = '没有移入回收站：' + (error && error.message ? error.message : '操作失败');
                render();
                cancelButton.focus();
            }
            return;
        }
        const result = await (mine.phase === 'deleting' ? A.deleteWork(mine.id) : A.checkDeleted(mine.id));
        if (current !== mine) return;
        if (result.status === 'deleted' || result.status === 'gone') {
            close({ restoreFocus: false });
            // 移除后由图库关闭看图器、重排作品墙，并把焦点交给原位置的下一件
            await A.removeWork(mine.id);
            if (isDesktop() && window.AppShell && typeof window.AppShell.refreshTrash === 'function') window.AppShell.refreshTrash();
            host('toast', doneMessage(mine, result));
            return;
        }
        mine.phase = result.status === 'kept' ? 'kept' : 'unknown';
        mine.message = mine.phase === 'kept' ? (isDesktop() ? '没有移入回收站：' : '没有删除：') + result.message : result.message;
        render();
        (mine.phase === 'kept' ? cancelButton : okButton).focus();
    }

    function doneMessage(mine, result) {
        if (result.status === 'gone') return `“${mine.title}”已不存在（可能已在旧图库中删除），已从列表中移除`;
        const what = (isDesktop() ? '已移入回收站：' : '已永久删除') + `“${mine.title}”` + (mine.pages > 1 ? `（${mine.pages} 张图片）` : '');
        const fallback = isDesktop() ? '。移入时没有收到确切回应，已重新读取确认' : '。删除时没有收到确切回应，已重新读取确认';
        return result.verified ? what + fallback : what;
    }

    function cancel() {
        if (!current || busy()) return;
        const unsure = current.phase === 'unknown';
        close({ restoreFocus: true });
        if (unsure) host('toast', '无法确认是否已删除，作品暂时留在列表里；刷新图库即可看到实际结果');
    }

    okButton.addEventListener('click', proceed);
    cancelButton.addEventListener('click', cancel);
    box.addEventListener('mousedown', (e) => { if (e.target === box) cancel(); });
    box.addEventListener('keydown', (e) => {
        // 确认框里的按键只归它：Delete、方向键等都不触发看图器或图库快捷键
        e.stopPropagation();
        if (e.key === 'Escape') {
            e.preventDefault();
            cancel();
        } else if (e.key === 'Tab') {
            e.preventDefault();
            const buttons = [cancelButton, okButton].filter((b) => !b.disabled);
            if (!buttons.length) return;
            const i = buttons.indexOf(document.activeElement);
            buttons[(i + (e.shiftKey ? buttons.length - 1 : 1)) % buttons.length].focus();
        }
    });
    window.addEventListener('beforeunload', (e) => {
        if (busy()) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    window.CDelete = { request, requestBatch, isOpen: () => !!current, isBusy: busy };
})();
