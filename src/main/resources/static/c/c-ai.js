/*
 * 看图器信息栏里的 AI 画面文字翻译：按作品图序分组展示每页文字卡片（短注、简体中文译文、原文）与简短画面说明。
 *
 * 只有用户点击“翻译画面文字”才发送静态图片到 Google Gemini。
 * 结果不写入数据库，只在当前应用会话内按作品保留。
 */
(function () {
    'use strict';

    const el = (tag, cls, text) => {
        const node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text != null) node.textContent = text;
        return node;
    };

    // id → { status: 'loading' | 'ready' | 'error', result, error, needsKey, checkKey }
    const sessions = new Map();
    const enabled = () => !(window.UiCapabilities && window.UiCapabilities.aiAnalysis === false);

    async function analyze(id, rerender) {
        const existing = sessions.get(id);
        if (existing && existing.status === 'loading') return;
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
                session.error = body.message || `翻译没有完成（${response.status}）`;
                // 503 也可能是 Gemini 或模型暂时不可用，只有明确提示未启用时才引导去填 Key
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

    function buildGroups(result) {
        const pages = Array.isArray(result.pages) ? result.pages : [];
        const analyzed = Array.isArray(result.analyzedAssets) ? result.analyzedAssets : [];
        const skipped = Array.isArray(result.skippedAssets) ? result.skippedAssets : [];
        if (analyzed.length === pages.length && analyzed.length > 0) {
            const items = pages.map((page, i) => ({
                pos: Number.isFinite(Number(analyzed[i].position)) ? Number(analyzed[i].position) : page.index,
                page
            }));
            skipped.forEach((media) => items.push({ pos: Number(media.position) || 0, skipped: media }));
            items.sort((a, b) => a.pos - b.pos);
            return { items, trailingSkippedCount: 0 };
        }
        const items = pages.map((page) => ({ pos: page.index, page }));
        items.sort((a, b) => a.pos - b.pos);
        return { items, trailingSkippedCount: skipped.length };
    }

    function renderCard(text) {
        const card = el('div', 'ai-card');
        const note = typeof text.note === 'string' ? text.note.trim() : '';
        const translation = typeof text.translation === 'string' ? text.translation : '';
        const source = typeof text.source === 'string' ? text.source : '';
        if (note) card.appendChild(el('p', 'ai-card-note', note));
        const primary = translation || source;
        if (primary) card.appendChild(el('p', 'ai-card-tr', primary));
        if (source && translation && source.trim() !== translation.trim()) {
            card.appendChild(el('p', 'ai-card-src', source));
        }
        return card;
    }

    function renderResult(box, result) {
        const { items, trailingSkippedCount } = buildGroups(result);
        const multiPage = items.length > 1;
        items.forEach((item) => {
            const group = el('div', 'ai-group');
            if (multiPage) group.appendChild(el('h4', 'ai-page-title', `第 ${item.pos} 页`));
            if (item.skipped) {
                group.appendChild(el('p', 'ai-note', '动图或视频未参与翻译。'));
            } else {
                const texts = Array.isArray(item.page.texts) ? item.page.texts : [];
                if (texts.length) {
                    const cards = el('div', 'ai-cards');
                    texts.forEach((t) => cards.appendChild(renderCard(t)));
                    group.appendChild(cards);
                } else {
                    group.appendChild(el('p', 'ai-empty', '未发现可翻译文字'));
                }
                if (item.page.description) {
                    group.appendChild(el('p', 'ai-desc', item.page.description));
                }
            }
            box.appendChild(group);
        });
        if (trailingSkippedCount > 0) {
            box.appendChild(el('p', 'ai-note', `有 ${trailingSkippedCount} 个动图或视频未参与翻译。`));
        }
    }

    // 把 AI 分区加到信息栏末尾；rerender 由看图器提供，在异步结果回来时重画信息栏
    function attach(box, work, rerender) {
        if (!enabled()) return;
        const section = el('section', 'ai-section');
        section.appendChild(el('h3', null, 'AI 翻译'));
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
            section.appendChild(el('p', 'ai-note',
                '识别并翻译图中的对白、旁白和拟声词。点击后静态图会上传到 Google Gemini；是否计费取决于 Key 所属项目。'));
            section.appendChild(start('翻译画面文字'));
        } else if (session.status === 'loading') {
            section.appendChild(el('p', 'ai-note', '正在识别并翻译画面文字，请稍候…'));
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
