/*
 * 看图器信息栏里的 AI 分析：多图 / 漫画的摘要、逐页描述、日文 OCR 与中文翻译、角色与标签建议。
 *
 * 只有用户点了才发送：图片会上传到 Google，所以入口旁写明这一点。
 * 结果不写入数据库（与现有接口一致），只在这次打开应用期间按作品记住，来回翻看不必重新分析。
 */
(function () {
    'use strict';

    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };

    // id → { status: 'loading' | 'ready' | 'error', result, error, needsKey }
    const sessions = new Map();
    const enabled = () => !(window.UiCapabilities && window.UiCapabilities.aiAnalysis === false);

    async function analyze(id, rerender) {
        const session = { status: 'loading' };
        sessions.set(id, session);
        rerender();
        try {
            const response = await fetch(`/api/illustrations/${encodeURIComponent(id)}/ai-analysis`, {
                method: 'POST', headers: { Accept: 'application/json' }, cache: 'no-store'
            });
            const body = await response.json().catch(() => ({}));
            if (!response.ok) {
                session.status = 'error';
                session.error = body.message || `分析没有完成（${response.status}）`;
                // 503 也可能是 Gemini 暂时不可用，那种情况重试就好，不用去设置
                session.needsKey = response.status === 503 && /未启用/.test(session.error);
                session.checkKey = /到设置检查/.test(session.error);
            } else {
                session.status = 'ready';
                session.result = body;
            }
        } catch (e) {
            session.status = 'error';
            session.error = '无法连接到本地图库服务';
        }
        rerender();
    }

    function renderResult(box, result) {
        box.appendChild(el('h4', null, '摘要'));
        box.appendChild(el('p', 'ai-text', result.summary));
        (result.pages || []).forEach((page) => {
            const heading = (result.pages.length > 1 ? `第 ${page.index} 页` : '画面');
            box.appendChild(el('h4', null, heading));
            box.appendChild(el('p', 'ai-text', page.description));
            if (page.texts && page.texts.length) {
                const list = el('dl', 'ai-texts');
                page.texts.forEach((text) => {
                    list.appendChild(el('dt', null, text.source));
                    const translated = el('dd', null, text.translation);
                    if (text.note) translated.title = text.note;
                    list.appendChild(translated);
                });
                box.appendChild(list);
            }
        });
        if (result.characters && result.characters.length) {
            box.appendChild(el('h4', null, '角色'));
            result.characters.forEach((c) => box.appendChild(el('p', 'ai-text', `${c.name}：${c.description}`)));
        }
        if (result.relationships && result.relationships.length) {
            box.appendChild(el('h4', null, '关系（推测）'));
            result.relationships.forEach((r) => box.appendChild(el('p', 'ai-text', r)));
        }
        if (result.suggestedTags && result.suggestedTags.length) {
            box.appendChild(el('h4', null, '建议标签'));
            const row = el('div', 'info-tags');
            result.suggestedTags.forEach((t) => row.appendChild(el('span', 'ai-tag', t)));
            box.appendChild(row);
        }
        if (result.skippedAssets && result.skippedAssets.length) {
            box.appendChild(el('p', 'ai-note', `有 ${result.skippedAssets.length} 个动图或视频没有参与分析。`));
        }
    }

    // 把 AI 分区加到信息栏末尾；rerender 由看图器提供，在异步结果回来时重画信息栏
    function attach(box, work, rerender) {
        if (!enabled()) return;
        const section = el('section', 'ai-section');
        section.appendChild(el('h3', null, 'AI 分析'));
        const session = sessions.get(work.id);
        const start = (label) => {
            const button = el('button', 'btn', label);
            button.type = 'button';
            button.addEventListener('click', () => analyze(work.id, rerender));
            return button;
        };
        const settingsLink = (label) => {
            const link = el('a', 'link', label);
            link.href = '/c/settings.html#ai';
            return link;
        };
        if (!session) {
            section.appendChild(el('p', 'ai-note', '摘要、日文 OCR 与中文翻译。点击后，这件作品的静态图片会上传到 Google Gemini；是否计费取决于 Key 所属的 Google 项目。'));
            section.appendChild(start('分析这件作品'));
        } else if (session.status === 'loading') {
            section.appendChild(el('p', 'ai-note', '正在分析，通常需要几十秒…'));
        } else if (session.status === 'error') {
            if (session.needsKey) {
                section.appendChild(el('p', 'ai-note', '还没有填写 Gemini API Key。'));
                section.appendChild(settingsLink('到设置中填写'));
            } else {
                section.appendChild(el('p', 'ai-error', session.error));
                section.appendChild(start('重试'));
                if (session.checkKey) section.appendChild(settingsLink('打开设置'));
            }
        } else {
            renderResult(section, session.result);
            section.appendChild(el('p', 'ai-note', 'AI 的结果可能有误，尤其是看不清的文字。关闭应用后不保留。'));
        }
        box.appendChild(section);
    }

    window.CAi = { attach };
})();
