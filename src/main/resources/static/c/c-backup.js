/*
 * 设置 · 备份、恢复与从旧版导入
 *
 * 文件都在应用自己的原生对话框里选，页面不接触路径。备份：选好位置后由原生小窗口显示进度，图库暂停使用，完成后窗口回到这里。
 * 恢复分两步：先在后台解包并校验备份（图库照常可用，这里显示进度、可取消），通过后弹出确认框，写明备份内容和
 * “备份之后的改动会消失”，默认焦点在“取消”；确认后同样交给原生小窗口。确认只带检查返回的操作号，换不了别的文件。
 * 从旧版导入同样两步：选旧版配置文件后在后台复制并核对一份，确认框并列旧版与当前图库的数量；有媒体问题时只列出问题，不能导入。
 * 恢复与导入同一时间只有一项，GET /api/desktop/restore 的 kind 说明是哪一项。
 * 页面刷新或从别的标签页打开时，按服务端状态接着显示：检查中继续显示进度，等待确认就再弹出确认框。
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const POLL_MILLIS = 500;

    let view = { phase: 'IDLE' };
    let busy = false;
    let pollTimer = 0;
    /** 媒体问题的列表在服务端已经消掉，由用户自己关掉 */
    let problemsOpen = false;

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

    function showError(id, text) {
        $(id).hidden = !text;
        $(id).textContent = text || '';
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

    const counts = (s) => `${s.works} 件作品、${s.assets} 张图片或视频、${s.authors} 位作者、${s.tags} 个标签、${s.inbox} 条 X 收藏`;

    /** 确认框里的事实：备份是哪个文件、什么时候、有多少东西、数据库会不会升级。 */
    function facts(preview) {
        const s = preview.summary;
        const rows = [
            ['备份文件', preview.fileName],
            ['备份时间', `${time(preview.createdAt)}（应用版本 ${preview.appVersion}）`],
            ['内容', counts(s)],
            ['大小', size(s.bytes)]
        ];
        if (preview.schemaBefore !== preview.schemaAfter) {
            rows.push(['数据库', `备份时为 V${preview.schemaBefore}，恢复时升级到 V${preview.schemaAfter}`]);
        }
        return rows;
    }

    /** 旧版图库从哪里来、有多少东西，旁边是将被替换的当前图库。 */
    function importFacts(preview) {
        const rows = [
            ['来源', `MySQL ${preview.source}`],
            ['原图文件夹', preview.mediaRoot],
            ['旧版图库', `${counts(preview.incoming)}，原图共 ${size(preview.incoming.bytes)}`],
            ['当前图库', preview.current ? `${counts(preview.current)}，将被替换` : '没能统计，将被替换']
        ];
        rows.push(['数据库', preview.schemaBefore === preview.schemaAfter ? `V${preview.schemaAfter}`
            : `旧版为 V${preview.schemaBefore}，只在复制出的图库里升级到 V${preview.schemaAfter}，旧版数据库不变`]);
        if (preview.problemCount > 0) rows.push(['媒体问题', `${preview.problemCount} 个文件缺失或内容与记录不符`]);
        return rows;
    }

    function fillFacts(id, rows) {
        $(id).replaceChildren(...rows.flatMap(([label, value]) => {
            const dt = document.createElement('dt');
            dt.textContent = label;
            const dd = document.createElement('dd');
            dd.textContent = value;
            return [dt, dd];
        }));
    }

    function setButtons(disabled) {
        $('backup-create').disabled = disabled;
        $('restore-choose').disabled = disabled;
        $('import-choose').disabled = disabled;
    }

    function showProgress(prefix, shown) {
        $(`${prefix}-check`).hidden = !shown;
        if (!shown) return;
        $(`${prefix}-stage`).textContent = view.stage || (prefix === 'import' ? '正在读取旧版图库…' : '正在检查备份…');
        const known = view.fraction >= 0;
        $(`${prefix}-bar`).dataset.unknown = String(!known);
        $(`${prefix}-fill`).style.width = known ? `${Math.round(view.fraction * 100)}%` : '';
    }

    function render(next) {
        view = next;
        clearTimeout(pollTimer);
        const checking = view.phase === 'CHECKING';
        const importing = view.kind === 'IMPORT';
        showProgress('restore', checking && !importing);
        showProgress('import', checking && importing);
        setButtons(busy || view.phase !== 'IDLE' && view.phase !== 'FAILED');
        if (checking) pollTimer = setTimeout(poll, POLL_MILLIS);

        if (view.phase === 'READY' && !importing) restoreSheet.open(view.id, fillRestore); else restoreSheet.close();
        if (view.phase === 'READY' && importing) importSheet.open(view.id, fillImport);
        else if (!problemsOpen) importSheet.close();

        if (view.phase === 'FAILED') {
            if (importing && view.importPreview) {
                problemsOpen = true;
                importSheet.open(`problems-${view.message}`, fillProblems);
            } else {
                showError(importing ? 'import-error' : 'backup-error', `${importing ? '没有导入' : '没有恢复'}：${view.message}`);
            }
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
        showError('backup-error', '');
        showError('import-error', '');
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
                showError('backup-error', `没有开始备份：${e.message}`);
            }
        });
    }

    function chooseRestore() {
        return exclusive(async () => {
            try {
                view = await send('/api/desktop/restore/check', 'POST');
            } catch (e) {
                showError('backup-error', `没有开始恢复：${e.message}`);
            }
        });
    }

    function chooseImport() {
        return exclusive(async () => {
            try {
                view = await send('/api/desktop/import/check', 'POST');
            } catch (e) {
                showError('import-error', `没有开始导入：${e.message}`);
            }
        });
    }

    async function cancelCheck() {
        try {
            render(await send('/api/desktop/restore/cancel', 'POST', { id: null }));
        } catch (e) {
            showError(view.kind === 'IMPORT' ? 'import-error' : 'backup-error', `取消失败：${e.message}`);
        }
    }

    /* ---------------- 确认框 ---------------- */

    /**
     * 恢复与导入各一个确认框：打开时焦点在“取消”，Tab 只在两个按钮之间切换，Esc、点背景和“取消”都等于取消。
     * {@code key} 相同就不重复打开，轮询不会把用户的焦点抢回来。
     */
    function sheet(name, { onCancel, onConfirm }) {
        const box = $(`${name}-confirm`);
        const cancel = $(`${name}-cancel`);
        const ok = $(`${name}-ok`);
        let shown = null;
        let returnFocus = null;
        box.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                e.preventDefault();
                onCancel();
            } else if (e.key === 'Tab') {
                e.preventDefault();
                const next = document.activeElement === cancel ? ok : cancel;
                if (!next.disabled && !next.hidden) next.focus();
            }
        });
        box.addEventListener('click', (e) => { if (e.target === box) onCancel(); });
        cancel.addEventListener('click', onCancel);
        ok.addEventListener('click', onConfirm);
        return {
            open(key, fill) {
                if (shown === key) return;
                shown = key;
                fill();
                returnFocus = document.activeElement;
                box.hidden = false;
                document.querySelector('main').inert = true;
                cancel.focus();
            },
            close() {
                if (shown === null) return;
                shown = null;
                box.hidden = true;
                document.querySelector('main').inert = false;
                if (returnFocus && returnFocus.focus) returnFocus.focus();
                returnFocus = null;
            }
        };
    }

    function resetButtons(name, okText, cancelText) {
        $(`${name}-confirm-error`).hidden = true;
        $(`${name}-ok`).disabled = false;
        $(`${name}-cancel`).disabled = false;
        $(`${name}-ok`).textContent = okText;
        $(`${name}-cancel`).textContent = cancelText;
    }

    function fillRestore() {
        fillFacts('restore-facts', facts(view.preview));
        $('restore-error').hidden = true;
        $('restore-ok').disabled = false;
        $('restore-cancel').disabled = false;
        $('restore-ok').textContent = '恢复整个图库';
    }

    function fillImport() {
        problemsOpen = false;
        fillFacts('import-facts', importFacts(view.importPreview));
        $('import-title').textContent = '用旧版图库替换整个当前图库？';
        $('import-problems').hidden = true;
        $('import-warning').hidden = false;
        $('import-kept').hidden = false;
        $('import-ok').hidden = false;
        resetButtons('import', '替换为旧版图库', '取消');
    }

    function fillProblems() {
        const preview = view.importPreview;
        fillFacts('import-facts', importFacts(preview));
        $('import-title').textContent = '旧版图库有媒体问题，不能导入';
        const items = preview.problems.map((problem) => {
            const li = document.createElement('li');
            li.textContent = problem;
            return li;
        });
        if (preview.problemCount > preview.problems.length) {
            const more = document.createElement('li');
            more.textContent = `……另有 ${preview.problemCount - preview.problems.length} 个`;
            items.push(more);
        }
        $('import-problems').replaceChildren(...items);
        $('import-problems').hidden = false;
        $('import-warning').hidden = true;
        $('import-kept').hidden = true;
        $('import-ok').hidden = true;
        resetButtons('import', '替换为旧版图库', '知道了');
        $('import-confirm-error').hidden = false;
        $('import-confirm-error').textContent = '当前图库没有任何改动。请在旧版里补齐这些文件后再导入。';
    }

    async function cancelPending(name, error) {
        if ($(`${name}-cancel`).disabled) return;
        try {
            render(await send('/api/desktop/restore/cancel', 'POST', { id: view.id }));
        } catch (e) {
            $(error).hidden = false;
            $(error).textContent = `取消失败：${e.message}`;
        }
    }

    async function confirmPending(name, url, okText, error, verb) {
        $(`${name}-ok`).disabled = true;
        $(`${name}-cancel`).disabled = true;
        $(`${name}-ok`).textContent = '正在开始…';
        $(error).hidden = true;
        try {
            await send(url, 'POST', { id: view.id });
            // 交给原生窗口：服务即将关闭，完成后窗口会重新载入这一页
        } catch (e) {
            $(error).hidden = false;
            $(error).textContent = `没有开始${verb}：${e.message}`;
            $(`${name}-ok`).disabled = false;
            $(`${name}-cancel`).disabled = false;
            $(`${name}-ok`).textContent = okText;
        }
    }

    const restoreSheet = sheet('restore', {
        onCancel: () => cancelPending('restore', 'restore-error'),
        onConfirm: () => confirmPending('restore', '/api/desktop/restore/apply', '恢复整个图库', 'restore-error', '恢复')
    });

    const importSheet = sheet('import', {
        onCancel: () => {
            if (problemsOpen) {
                // 服务端早已消掉这条，只是把列表收起来
                problemsOpen = false;
                importSheet.close();
                return;
            }
            cancelPending('import', 'import-confirm-error');
        },
        onConfirm: () => confirmPending('import', '/api/desktop/import/apply', '替换为旧版图库', 'import-confirm-error', '导入')
    });

    $('backup-create').addEventListener('click', createBackup);
    $('restore-choose').addEventListener('click', chooseRestore);
    $('import-choose').addEventListener('click', chooseImport);
    $('restore-check-cancel').addEventListener('click', cancelCheck);
    $('import-check-cancel').addEventListener('click', cancelCheck);

    (async function load() {
        try {
            render(await send('/api/desktop/restore', 'GET'));
        } catch (e) {
            // 服务端模式没有这些接口
            if (e.status === 404) $('backup').hidden = true;
        }
    })();
})();
