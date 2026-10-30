/* 设置：X 账号、AI 密钥、代理与数据位置。密钥一旦保存就不再从服务端读回。 */
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

    let pollTimer = 0;

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

    /* ---------------- X 账号 ---------------- */

    function button(text, cls, onClick) {
        const b = el('button', cls, text);
        b.type = 'button';
        b.addEventListener('click', onClick);
        return b;
    }

    function renderConnection(connection) {
        const { state, failure, authorizeUrl } = connection.status;
        $('x-callback').textContent = connection.callbackUrl;
        const input = $('x-client-id');
        if (!input.value) input.value = connection.clientId || '';
        const box = $('x-state');
        const actions = $('x-actions');
        box.replaceChildren();
        actions.replaceChildren();
        const connected = state === 'CONNECTED';
        const waiting = state === 'WAITING';
        $('x-setup').hidden = connected || waiting;
        showError('x-error', state === 'FAILED' ? M.connectionProblem(failure) : '');
        clearTimeout(pollTimer);

        if (connected) {
            box.appendChild(el('p', 'set-ok', '已连接'));
            actions.appendChild(button('断开连接', 'btn', disconnect));
        } else if (waiting) {
            box.appendChild(el('p', null, '请在打开的浏览器页面中完成授权…'));
            if (authorizeUrl) {
                actions.appendChild(button('复制授权链接', 'btn', async () => {
                    await navigator.clipboard.writeText(authorizeUrl);
                    toast('已复制，可以粘贴到已登录 X 的浏览器');
                }));
            }
            actions.appendChild(button('取消', 'btn', async () => renderConnection(await send('/api/x-auth/cancel', 'POST'))));
            pollTimer = setTimeout(loadConnection, POLL_MILLIS);
        } else {
            box.appendChild(el('p', null, '未连接'));
            actions.appendChild(button('在浏览器中授权', 'btn btn-ink', connect));
        }
    }

    async function loadConnection() {
        try {
            renderConnection(await send('/api/x-auth/status', 'GET'));
        } catch (e) {
            showError('x-error', `读取连接状态失败：${e.message}`);
        }
    }

    async function connect() {
        showError('x-error', '');
        try {
            renderConnection(await send('/api/x-auth/start', 'POST', { clientId: $('x-client-id').value.trim() }));
        } catch (e) {
            showError('x-error', /format/i.test(e.message) ? 'Client ID 的格式不对，请从开发者控制台重新复制。' : e.message);
        }
    }

    async function disconnect() {
        try {
            const result = await send('/api/x-auth/disconnect', 'POST');
            toast(result.revoked ? '已断开连接' : '本机的登录已移除；没能通知 X，可到 X 的“已连接的应用”中手动撤销');
            await loadConnection();
        } catch (e) {
            showError('x-error', `断开失败：${e.message}`);
        }
    }

    /* ---------------- 其余设置 ---------------- */

    function renderKey(saved) {
        $('ai-state').textContent = saved ? '已保存 API Key。' : '填写自己的 Gemini API Key 后，看图时可以分析作品、识别日文并翻译。';
        $('ai-clear').hidden = !saved;
        $('ai-key').value = '';
    }

    async function saveKey() {
        const key = $('ai-key').value.trim();
        if (!key) { showError('ai-error', '请先粘贴 API Key'); return; }
        showError('ai-error', '');
        try {
            await send('/api/desktop/settings/gemini-key', 'PUT', { apiKey: key });
            renderKey(true);
            toast('已保存');
        } catch (e) {
            showError('ai-error', /format/i.test(e.message) ? 'API Key 的格式不对。' : e.message);
        }
    }

    async function clearKey() {
        try {
            await send('/api/desktop/settings/gemini-key', 'DELETE');
            renderKey(false);
            toast('已移除');
        } catch (e) {
            showError('ai-error', e.message);
        }
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

    async function loadSettings() {
        try {
            const settings = await send('/api/desktop/settings', 'GET');
            $('data-dir').textContent = settings.dataDirectory;
            $('version').textContent = settings.version;
            $('proxy').value = settings.proxy;
            renderKey(settings.geminiKeySaved);
        } catch (e) {
            toast(`设置载入失败：${e.message}`);
        }
    }

    $('ai-save').addEventListener('click', saveKey);
    $('ai-clear').addEventListener('click', clearKey);
    $('proxy-save').addEventListener('click', saveProxy);
    $('data-open').addEventListener('click', async () => {
        try {
            const result = await send('/api/desktop/open-data-folder', 'POST');
            if (!result.opened) toast('没能打开文件夹，请手动访问上面的路径');
        } catch (e) { toast(e.message); }
    });
    $('x-copy-callback').addEventListener('click', async () => {
        await navigator.clipboard.writeText($('x-callback').textContent);
        toast('已复制回调地址');
    });

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

    loadConnection();
    loadSettings();
})();
