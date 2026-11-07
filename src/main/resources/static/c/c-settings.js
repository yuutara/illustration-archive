/* 设置：X 账号、AI 密钥、代理、关闭窗口的方式与数据位置。密钥一旦保存就不再从服务端读回。 */
(function () {
    'use strict';

    const M = window.XInboxModel;
    const $ = (id) => document.getElementById(id);
    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };
    const POLL_MILLIS = 1500;
    const fromX = new URLSearchParams(location.search).get('from') === 'x';

    let pollTimer = 0;
    let lastState = null;
    let guideDecided = false;
    let connecting = false;
    let keyBusy = false;
    let keySaved = false;

    function toast(text) {
        const t = $('toast');
        t.textContent = text;
        t.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { t.hidden = true; }, 3200);
    }

    function showError(id, text) {
        $(id).hidden = !text;
        $(id).textContent = text || '';
    }

    async function send(url, method, body) {
        const response = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        if (!response.ok) {
            const failure = await response.json().catch(() => ({}));
            throw new Error(failure.message || `HTTP ${response.status}`);
        }
        return response.status === 204 ? null : response.json();
    }

    async function copy(text, done) {
        try {
            await navigator.clipboard.writeText(text);
            toast(done);
        } catch (e) {
            toast('没能写入剪贴板，请手动选中复制');
        }
    }

    function renderSteps(box, steps) {
        box.replaceChildren(...steps.map((step) => {
            const row = el('div', 'set-step');
            row.dataset.tone = step.tone;
            row.appendChild(el('dt', null, step.label));
            row.appendChild(el('dd', null, step.value));
            return row;
        }));
    }

    /* ---------------- X 账号 ---------------- */

    function button(text, cls, onClick) {
        const b = el('button', cls, text);
        b.type = 'button';
        b.addEventListener('click', onClick);
        return b;
    }

    // 连点两下“授权”会开两个授权页，第二个还会让第一个作废，所以一次只走一个操作
    async function exclusive(work) {
        if (connecting) return;
        connecting = true;
        const buttons = [...$('x-actions').children];
        buttons.forEach((b) => { b.disabled = true; });
        try {
            await work();
        } finally {
            connecting = false;
            buttons.forEach((b) => { b.disabled = false; });
        }
    }

    function waitingText({ authorizeUrl, browserOpened }) {
        if (!authorizeUrl) return '浏览器已经返回，正在向 X 确认授权…';
        if (!browserOpened) return '没能自动打开浏览器。请复制授权链接，粘贴到已登录 X 的浏览器。';
        return '请在打开的浏览器页面中完成授权。如果 X 的页面报错，多半是应用类型或回调地址不符，可以取消后再核对。';
    }

    /*
     * 每次同步最多读多少条。选项由服务端给出，选中项总是服务端读回的值：没保存成功就回到原来那一项。
     * 只有桌面版有这项设置，读不到选项时整块不显示。
     */
    let readLimitBusy = false;
    let readLimitShown = false;

    function renderReadLimit(connection) {
        const box = $('x-read-limit');
        const choices = Array.isArray(connection.scanBudgets) ? connection.scanBudgets : [];
        box.hidden = choices.length === 0;
        $('x-read-limit-choices').replaceChildren(...choices.map((reads) => {
            const label = el('label', 'set-choice');
            const input = el('input');
            input.type = 'radio';
            input.name = 'x-read-limit';
            input.value = String(reads);
            input.checked = reads === connection.scanBudget;
            input.disabled = readLimitBusy;
            input.addEventListener('change', () => saveReadLimit(reads, connection));
            label.appendChild(input);
            label.appendChild(el('span', null, M.readLimitLabel(reads)));
            return label;
        }));
        // 从 X 收藏页的“调整”过来：这块刚显示出来，浏览器自己的锚点跳转已经错过了
        if (!box.hidden && !readLimitShown && location.hash === '#x-read-limit' && box.scrollIntoView) box.scrollIntoView({ block: 'center' });
        readLimitShown = readLimitShown || !box.hidden;
    }

    async function saveReadLimit(reads, before) {
        if (readLimitBusy) return;
        readLimitBusy = true;
        showError('x-read-limit-error', '');
        let shown = before;
        try {
            shown = await send('/api/desktop/settings/x-read-limit', 'PUT', { reads });
            toast(`已保存：每次同步最多读 ${shown.scanBudget} 条`);
        } catch (e) {
            showError('x-read-limit-error', `没有保存：${e.message}`);
        }
        readLimitBusy = false;
        renderConnection(shown);
    }

    function renderConnection(connection) {
        const { state, failure, authorizeUrl, savedLogin } = connection.status;
        $('x-callback').textContent = connection.callbackUrl;
        $('x-cost').textContent = M.syncCost(connection.scanBudget);
        renderReadLimit(connection);
        const input = $('x-client-id');
        if (!input.value) input.value = connection.clientId || '';
        if (!guideDecided) {
            guideDecided = true;
            $('x-guide').open = fromX && !connection.clientId;
        }
        renderSteps($('x-steps'), M.connectionSteps(connection));
        const connected = savedLogin === 'USABLE';
        const waiting = state === 'WAITING';
        const box = $('x-state');
        const actions = $('x-actions');
        box.replaceChildren();
        actions.replaceChildren();
        $('x-setup').hidden = waiting || (connected && !!connection.clientId);
        const kept = connected ? ' 原来的连接没有变化。' : '';
        showError('x-error', state === 'FAILED' ? M.connectionProblem(failure) + kept : '');
        clearTimeout(pollTimer);
        if (lastState === 'WAITING' && state === 'CONNECTED') toast('授权完成。还没有读取任何 X 数据，账号要到第一次同步时确认');
        lastState = state;

        if (waiting) {
            box.appendChild(el('p', null, waitingText(connection.status)));
            if (authorizeUrl) {
                actions.appendChild(button('复制授权链接', 'btn', () => copy(authorizeUrl, '已复制，可以粘贴到已登录 X 的浏览器')));
            }
            actions.appendChild(button('取消', 'btn', cancel));
            pollTimer = setTimeout(loadConnection, POLL_MILLIS);
        } else if (connected) {
            if (!connection.verifiedAt) box.appendChild(el('p', null, '授权已保存，但还没有读取过 X。到“X 收藏”点“同步”时，才会确认账号并开始计费。'));
            actions.appendChild(button('回到 X 收藏', 'btn btn-ink', () => { location.href = '/c/x.html'; }));
            actions.appendChild(button('重新授权', 'btn', connect));
            actions.appendChild(button('断开连接', 'btn', disconnect));
        } else {
            if (savedLogin === 'UNUSABLE') box.appendChild(el('p', null, '这份登录可能来自开发版，或文件已经损坏，桌面版不能使用。重新授权即可，已保存的收藏不受影响。'));
            actions.appendChild(button('在浏览器中授权', 'btn btn-ink', connect));
            if (savedLogin === 'UNUSABLE') actions.appendChild(button('移除这份登录', 'btn', disconnect));
        }
    }

    async function loadConnection() {
        try {
            renderConnection(await send('/api/x-auth/status', 'GET'));
        } catch (e) {
            showError('x-error', `读取连接状态失败：${e.message}`);
        }
    }

    function connect() {
        const clientId = $('x-client-id').value.trim();
        if (!clientId) { showError('x-error', '请先填写 Client ID'); return undefined; }
        showError('x-error', '');
        return exclusive(async () => {
            try {
                renderConnection(await send('/api/x-auth/start', 'POST', { clientId }));
            } catch (e) {
                showError('x-error', /format/i.test(e.message) ? 'Client ID 的格式不对，请从开发者控制台重新复制。' : `没有开始授权：${e.message}。可以再试一次。`);
            }
        });
    }

    function cancel() {
        return exclusive(async () => {
            try {
                renderConnection(await send('/api/x-auth/cancel', 'POST'));
                toast('已取消，这次授权没有保存');
            } catch (e) {
                showError('x-error', `取消失败：${e.message}`);
            }
        });
    }

    function disconnect() {
        return exclusive(async () => {
            try {
                const result = await send('/api/x-auth/disconnect', 'POST');
                toast(result.revoked ? '已断开连接，本机的登录已移除' : '本机的登录已移除；没能通知 X，可到 X 的“已连接的应用”中手动撤销');
                await loadConnection();
            } catch (e) {
                showError('x-error', `断开失败：${e.message}`);
            }
        });
    }

    /* ---------------- 其余设置 ---------------- */

    function renderKey(saved, verifiedAt = null) {
        keySaved = saved;
        renderSteps($('ai-steps'), M.aiSteps({ saved, verifiedAt }));
        $('ai-clear').hidden = !saved;
        $('ai-save').textContent = saved ? '替换' : '保存';
        $('ai-key').placeholder = saved ? '粘贴新的 Key，替换已保存的' : '粘贴后保存';
        $('ai-key').value = '';
    }

    async function keyWork(work) {
        if (keyBusy) return;
        keyBusy = true;
        $('ai-save').disabled = true;
        $('ai-clear').disabled = true;
        try {
            await work();
        } finally {
            keyBusy = false;
            $('ai-save').disabled = false;
            $('ai-clear').disabled = false;
        }
    }

    function saveKey() {
        const key = $('ai-key').value.trim();
        if (!key) { showError('ai-error', '请先粘贴 API Key'); return undefined; }
        showError('ai-error', '');
        return keyWork(async () => {
            const replacing = keySaved;
            try {
                await send('/api/desktop/settings/gemini-key', 'PUT', { apiKey: key });
                renderKey(true);
                toast(replacing ? '已替换，新的 Key 保存在本机，尚未验证' : '已保存在本机，尚未验证');
            } catch (e) {
                const kept = replacing ? '原来的 Key 没有变化。' : '';
                showError('ai-error', /format/i.test(e.message) ? 'API Key 的格式不对，不能有空格或换行。' : `没有保存：${e.message}。${kept}可以再试一次。`);
            }
        });
    }

    function clearKey() {
        showError('ai-error', '');
        return keyWork(async () => {
            try {
                await send('/api/desktop/settings/gemini-key', 'DELETE');
                renderKey(false);
                toast('已移除，本机不再保存 API Key');
            } catch (e) {
                showError('ai-error', `没有移除：${e.message}。可以再试一次。`);
            }
        });
    }

    async function saveProxy() {
        showError('proxy-error', '');
        try {
            await send('/api/desktop/settings/proxy', 'PUT', { address: $('proxy').value.trim() });
            toast('已保存');
        } catch (e) {
            showError('proxy-error', '格式应类似 127.0.0.1:7890');
        }
    }

    const closeChoices = () => [...document.querySelectorAll('input[name="close-action"]')];
    let closeAction = 'ASK';

    function renderCloseAction() {
        closeChoices().forEach((choice) => { choice.checked = choice.value === closeAction; });
    }

    async function saveCloseAction(action) {
        showError('close-error', '');
        try {
            await send('/api/desktop/settings/close-action', 'PUT', { action });
            closeAction = action;
            toast('已保存');
        } catch (e) {
            showError('close-error', `没有保存：${e.message}`);
        }
        renderCloseAction();
    }

    async function loadSettings() {
        try {
            const settings = await send('/api/desktop/settings', 'GET');
            $('data-dir').textContent = settings.dataDirectory;
            $('version').textContent = settings.version;
            $('proxy').value = settings.proxy;
            renderKey(settings.geminiKeySaved, settings.geminiVerifiedAt);
            closeAction = settings.closeAction;
            renderCloseAction();
            $('window').hidden = !window.iaDesktop;
        } catch (e) {
            toast(`设置载入失败：${e.message}`);
        }
    }

    $('ai-save').addEventListener('click', saveKey);
    $('ai-clear').addEventListener('click', clearKey);
    $('proxy-save').addEventListener('click', saveProxy);
    closeChoices().forEach((choice) => choice.addEventListener('change', () => saveCloseAction(choice.value)));
    $('data-open').addEventListener('click', async () => {
        try {
            const result = await send('/api/desktop/open-data-folder', 'POST');
            if (!result.opened) toast('没能打开文件夹，请手动访问上面的路径');
        } catch (e) { toast(e.message); }
    });
    $('x-copy-callback').addEventListener('click', () => copy($('x-callback').textContent, '已复制回调地址'));

    function syncTheme() {
        const dark = window.CTheme.get() === 'dark';
        const b = $('theme-toggle');
        b.setAttribute('aria-pressed', String(dark));
        b.querySelector('use').setAttribute('href', dark ? '#i-sun' : '#i-moon');
    }
    $('theme-toggle').addEventListener('click', () => {
        window.CTheme.set(window.CTheme.get() === 'dark' ? 'light' : 'dark');
        syncTheme();
    });
    syncTheme();

    // 玻璃透明度：拖动时只改画面，松手（change）才保存
    const glass = $('glass');
    glass.value = String(window.CTheme.glass());
    glass.addEventListener('input', () => window.CTheme.setGlass(glass.value, false));
    glass.addEventListener('change', () => window.CTheme.setGlass(glass.value));
    $('glass-reset').addEventListener('click', () => {
        window.CTheme.setGlass(window.CTheme.GLASS_DEFAULT);
        glass.value = String(window.CTheme.glass());
    });

    loadConnection();
    loadSettings();
})();
