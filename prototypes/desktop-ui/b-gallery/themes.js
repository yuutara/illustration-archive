/*
 * 原型 B · 视觉方案切换（定稿对照用的原型工具，不属于设计方案本身）
 * 在 <head> 里先设好 data-look / data-mode，避免首屏闪一下旧配色；界面加载后在“效果”按钮旁放一个切换器。
 * 也可以用网址直达：?look=paper|gallery|glow|current&mode=light|dark。Alt + T 轮换方案，Alt + D 切换浅色 / 深色。
 */
(function () {
    'use strict';

    const KEY = 'ia-proto-theme-v2';   // v2：默认改为 C 暖纸，旧的本机选择不再沿用
    const LOOKS = [['current', '现版'], ['paper', 'C 暖纸'], ['gallery', 'A 展厅'], ['glow', 'B 微光']];
    const MODES = [['light', '浅色'], ['dark', '深色']];
    const root = document.documentElement;
    let state = { look: 'paper', mode: 'light' };   // 暂定方案 C 暖纸（2026-10-28），浅色 / 深色都保留

    try { Object.assign(state, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) { /* 没有存储时用默认值 */ }
    const params = new URLSearchParams(location.search);
    if (params.has('look')) state.look = params.get('look');
    if (params.has('mode')) state.mode = params.get('mode');
    if (!LOOKS.some(([k]) => k === state.look)) state.look = 'paper';
    if (!MODES.some(([k]) => k === state.mode)) state.mode = 'light';

    let panel = null;

    function apply() {
        if (state.look === 'current') {
            delete root.dataset.look;
            delete root.dataset.mode;
        } else {
            root.dataset.look = state.look;
            root.dataset.mode = state.mode;
        }
        try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) { /* 同上 */ }
        sync();
    }

    function sync() {
        if (!panel) return;
        panel.querySelectorAll('[data-look]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.look === state.look)));
        panel.querySelectorAll('[data-mode]').forEach((b) => {
            b.disabled = state.look === 'current';   // 现版只有浅色
            b.setAttribute('aria-pressed', String(state.look !== 'current' && b.dataset.mode === state.mode));
        });
    }

    function build() {
        panel = document.createElement('div');
        panel.className = 'themesw';
        panel.setAttribute('role', 'group');
        panel.setAttribute('aria-label', '视觉方案');
        panel.title = 'Alt + T 轮换方案，Alt + D 切换浅色 / 深色';
        panel.innerHTML = `<span class="themesw-label">方案</span>
            <span class="themesw-seg">${LOOKS.map(([k, t]) => `<button type="button" data-look="${k}">${t}</button>`).join('')}</span>
            <span class="themesw-sep" aria-hidden="true"></span>
            <span class="themesw-seg">${MODES.map(([k, t]) => `<button type="button" data-mode="${k}">${t}</button>`).join('')}</span>`;
        panel.addEventListener('click', (e) => {
            const b = e.target.closest('button');
            if (!b || b.disabled) return;
            if (b.dataset.look) state.look = b.dataset.look;
            if (b.dataset.mode) state.mode = b.dataset.mode;
            apply();
        });
        // 有效果面板时与“效果”按钮并排（看图器里随它移到顶部），否则单独放在右下角
        const pill = document.querySelector('.fxlab-pill');
        if (pill) {
            const row = document.createElement('div');
            row.className = 'fxlab-bar';
            pill.replaceWith(row);
            row.append(pill, panel);
        } else {
            panel.classList.add('is-standalone');
            document.body.appendChild(panel);
        }
        sync();
    }

    addEventListener('keydown', (e) => {
        if (!e.altKey || e.ctrlKey || e.metaKey) return;
        const k = e.key.toLowerCase();
        if (k !== 't' && k !== 'd') return;
        e.preventDefault();
        e.stopPropagation();
        if (k === 't') {
            const i = LOOKS.findIndex(([key]) => key === state.look);
            state.look = LOOKS[(i + 1) % LOOKS.length][0];
        } else if (state.look !== 'current') {
            state.mode = state.mode === 'dark' ? 'light' : 'dark';
        }
        apply();
    }, true);

    apply();
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
    else build();
})();
