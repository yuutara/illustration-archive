/*
 * 设置 · 备份与恢复
 *
 * 文件都在应用自己的原生对话框里选，页面不接触路径。备份：选好位置后由原生小窗口显示进度，图库暂停使用，完成后窗口回到这里。
 * 恢复分两步：先在后台解包并校验备份（图库照常可用，这里显示进度、可取消），通过后弹出确认框，写明备份内容和
 * “备份之后的改动会消失”，默认焦点在“取消”；确认后同样交给原生小窗口。确认只带检查返回的操作号，换不了别的文件。
 * 页面刷新或从别的标签页打开时，按服务端状态接着显示：检查中继续显示进度，等待确认就再弹出确认框。
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const POLL_MILLIS = 500;

    let view = { phase: 'IDLE' };
    let busy = false;
    let pollTimer = 0;
    let shownId = null;
    let returnFocus = null;

    async function send(url, method, body) {
        const response = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        if (!response.ok) {
            const failure = await response.json().catch(() => ({}));
            const error = new Error(failure.message || `HTTP ${response.status}`);
            error.status = response.status;
            throw error;
        }
        return response.status === 204 || response.status === 202 ? null : response.json();
    }

    function showError(text) {
        $('backup-error').hidden = !text;
        $('backup-error').textContent = text || '';
    }

    function size(bytes) {
        if (bytes < 1024 * 1024) return `${Math.max(1, Math.ceil(bytes / 1024))} KB`;
        const mb = bytes / 1024 / 1024;
        return mb < 1024 ? `${mb.toFixed(1)} MB` : `${(mb / 1024).toFixed(2)} GB`;
    }

    function time(iso) {
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) return String(iso);
        const two = (n) => String(n).padStart(2, '0');
        return `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())} ${two(date.getHours())}:${two(date.getMinutes())}`;
    }

    /** 确认框里的事实：备份是哪个文件、什么时候、有多少东西、数据库会不会升级。 */
    function facts(preview) {
        const s = preview.summary;
        const rows = [
            ['备份文件', preview.fileName],
            ['备份时间', `${time(preview.createdAt)}（应用版本 ${preview.appVersion}）`],
            ['内容', `${s.works} 件作品、${s.assets} 张图片或视频、${s.authors} 位作者、${s.tags} 个标签、${s.inbox} 条 X 收藏`],
            ['大小', size(s.bytes)]
        ];
        if (preview.schemaBefore !== preview.schemaAfter) {
            rows.push(['数据库', `备份时为 V${preview.schemaBefore}，恢复时升级到 V${preview.schemaAfter}`]);
        }
        return rows;
    }

    function setButtons(disabled) {
        $('backup-create').disabled = disabled;
        $('restore-choose').disabled = disabled;
    }

    function render(next) {
        view = next;
        clearTimeout(pollTimer);
        const checking = view.phase === 'CHECKING';
        $('restore-check').hidden = !checking;
        setButtons(busy || view.phase !== 'IDLE' && view.phase !== 'FAILED');
        if (checking) {
            $('restore-stage').textContent = view.stage || '正在检查备份…';
            const known = view.fraction >= 0;
            $('restore-bar').dataset.unknown = String(!known);
            $('restore-fill').style.width = known ? `${Math.round(view.fraction * 100)}%` : '';
            pollTimer = setTimeout(poll, POLL_MILLIS);
        }
        if (view.phase === 'READY') {
            openConfirm();
        } else {
            closeConfirm();
        }
        if (view.phase === 'FAILED') {
            showError(`没有恢复：${view.message}`);
            // 看过就算知道了：刷新页面不再重复这条
            send('/api/desktop/restore/cancel', 'POST', { id: null }).catch(() => { });
        }
    }

    async function poll() {
        try {
            render(await send('/api/desktop/restore', 'GET'));
        } catch (e) {
            // 维护期间服务暂时关闭，窗口会在完成后重新载入这一页
            pollTimer = setTimeout(poll, POLL_MILLIS);
        }
    }

    async function exclusive(work) {
        if (busy) return;
        busy = true;
        setButtons(true);
        showError('');
        try {
            await work();
        } finally {
            busy = false;
            render(view);
        }
    }

    function createBackup() {
        return exclusive(async () => {
            try {
                await send('/api/desktop/backup', 'POST');
            } catch (e) {
                showError(`没有开始备份：${e.message}`);
            }
        });
    }

    function chooseRestore() {
        return exclusive(async () => {
            try {
                view = await send('/api/desktop/restore/check', 'POST');
            } catch (e) {
                showError(`没有开始恢复：${e.message}`);
            }
        });
    }

    async function cancelCheck() {
        try {
            render(await send('/api/desktop/restore/cancel', 'POST', { id: null }));
        } catch (e) {
            showError(`取消失败：${e.message}`);
        }
    }

    /* ---------------- 确认框 ---------------- */

    const box = $('restore-confirm');

    function openConfirm() {
        if (shownId === view.id) return;
        shownId = view.id;
        const list = $('restore-facts');
        list.replaceChildren(...facts(view.preview).flatMap(([label, value]) => {
            const dt = document.createElement('dt');
            dt.textContent = label;
            const dd = document.createElement('dd');
            dd.textContent = value;
            return [dt, dd];
        }));
        $('restore-error').hidden = true;
        $('restore-ok').disabled = false;
        $('restore-cancel').disabled = false;
        $('restore-ok').textContent = '恢复整个图库';
        returnFocus = document.activeElement;
        box.hidden = false;
        document.querySelector('main').inert = true;
        $('restore-cancel').focus();
    }

    function closeConfirm() {
        if (shownId === null) return;
        shownId = null;
        box.hidden = true;
        document.querySelector('main').inert = false;
        if (returnFocus && returnFocus.focus) returnFocus.focus();
        returnFocus = null;
    }

    async function cancelRestore() {
        if ($('restore-cancel').disabled) return;
        try {
            render(await send('/api/desktop/restore/cancel', 'POST', { id: view.id }));
        } catch (e) {
            $('restore-error').hidden = false;
            $('restore-error').textContent = `取消失败：${e.message}`;
        }
    }

    async function confirmRestore() {
        $('restore-ok').disabled = true;
        $('restore-cancel').disabled = true;
        $('restore-ok').textContent = '正在开始…';
        $('restore-error').hidden = true;
        try {
            await send('/api/desktop/restore/apply', 'POST', { id: view.id });
            // 交给原生窗口：服务即将关闭，完成后窗口会重新载入这一页
        } catch (e) {
            $('restore-error').hidden = false;
            $('restore-error').textContent = `没有开始恢复：${e.message}`;
            $('restore-ok').disabled = false;
            $('restore-cancel').disabled = false;
            $('restore-ok').textContent = '恢复整个图库';
        }
    }

    box.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            e.preventDefault();
            cancelRestore();
        } else if (e.key === 'Tab') {
            // 只在两个按钮之间切换
            e.preventDefault();
            const next = document.activeElement === $('restore-cancel') ? $('restore-ok') : $('restore-cancel');
            if (!next.disabled) next.focus();
        }
    });
    box.addEventListener('click', (e) => { if (e.target === box) cancelRestore(); });
    $('restore-cancel').addEventListener('click', cancelRestore);
    $('restore-ok').addEventListener('click', confirmRestore);
    $('backup-create').addEventListener('click', createBackup);
    $('restore-choose').addEventListener('click', chooseRestore);
    $('restore-check-cancel').addEventListener('click', cancelCheck);

    (async function load() {
        try {
            render(await send('/api/desktop/restore', 'GET'));
        } catch (e) {
            // 服务端模式没有这些接口
            if (e.status === 404) $('backup').hidden = true;
        }
    })();
})();
