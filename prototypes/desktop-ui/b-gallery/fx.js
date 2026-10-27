/*
 * 原型 B · 动效与材质实验层
 * 通过 gallery.js 里的可选挂钩（window.GalleryFx）介入；切回“原版”时所有挂钩直接返回，样式类全部移除。
 * 删掉 index.html 中 fx.css / fx.js 两行即回到纯原版。
 */
(function () {
    'use strict';

    const api = window.GalleryApi;
    if (!api) return;
    const root = document.documentElement;
    const $ = (id) => document.getElementById(id);

    const SNAPPY = getComputedStyle(root).getPropertyValue('--fx-snappy').trim() || 'ease-out';
    const BOUNCY = getComputedStyle(root).getPropertyValue('--fx-bouncy').trim() || 'ease-out';
    const GENTLE = getComputedStyle(root).getPropertyValue('--fx-gentle').trim() || 'ease-out';

    const AREAS = [
        ['press', '按压反馈', '按下微缩、松开按弹簧回弹；墨色按钮加一道釉面高光'],
        ['select', '选择反馈', '勾选圆点弹出、描边展开；全选时按位置依次点亮'],
        ['bar', '批量操作条', '从底部弹起；计数跳动；悬停高亮在按钮间滑动'],
        ['popover', '浮层与提示', '从触发按钮的位置展开；亚克力材质；对话框加烟雾遮罩'],
        ['wall', '作品墙', '筛选、换密度时作品滑到新位置；主色占位后渐显；悬停投影取作品色'],
        ['stack', '多页叠卡与滑动换页', '多图作品是一叠循环的牌，角标显示当前页；左右拖动都把顶牌抽出压到底：向左露出后一页，向右露出前一页'],
        ['tether', '卡片牵引', '按住卡片侧向短拉：卡片离开原位、倾斜抬起，越远越费力，松手平稳归位'],
        ['drag', '拖动浏览', '按住空白处或纵向拖动：图库 1:1 跟手滚动，松手顺势滑一段'],
        ['viewer', '看图器', '从缩略图放大进入、缩回退出；翻页滑入；工具栏成为浮动胶囊'],
        ['masthead', '顶栏材质', '滚动时顶栏半透明模糊，作品从下方透出']
    ];
    const EXTRAS = [
        ['refract', '玻璃折射', '液态玻璃边缘按透镜折射背后的画面（仅 Chromium 内核）'],
        ['sheen', '光泽跟随（实验）', '悬停作品时，一道柔光随鼠标移动'],
        ['inertia', '松手惯性（实验）', '放大后拖动图片，松手继续滑行一小段']
    ];
    const KEY = 'ia-proto-fx-v1';
    const DEFAULTS = {
        version: 'on',
        areas: Object.fromEntries(AREAS.map(([k]) => [k, true])),
        material: 'glass', refract: true, sheen: false, inertia: false, reduce: false
    };

    function load() {
        try {
            const saved = JSON.parse(localStorage.getItem(KEY) || 'null');
            if (saved) return { ...DEFAULTS, ...saved, areas: { ...DEFAULTS.areas, ...saved.areas } };
        } catch (e) { /* 隐私模式等情况下没有存储，使用默认值 */ }
        return structuredClone(DEFAULTS);
    }
    function save() {
        try { localStorage.setItem(KEY, JSON.stringify(cfg)); } catch (e) { /* 同上 */ }
    }

    let cfg = load();
    const has = (k) => root.classList.contains('fx-' + k);
    const systemReduce = matchMedia('(prefers-reduced-motion: reduce)');
    const reduced = () => systemReduce.matches || root.classList.contains('fx-reduce');

    function apply() {
        const on = cfg.version === 'on';
        root.classList.toggle('fx-on', on);
        AREAS.forEach(([k]) => root.classList.toggle('fx-' + k, on && !!cfg.areas[k]));
        ['solid', 'acrylic', 'glass'].forEach((m) => root.classList.toggle('fx-mat-' + m, on && cfg.material === m));
        root.classList.toggle('fx-refract', on && cfg.refract && cfg.material === 'glass');
        root.classList.toggle('fx-sheen', on && cfg.sheen);
        root.classList.toggle('fx-inertia', on && cfg.inertia);
        root.classList.toggle('fx-reduce', cfg.reduce);
        if (!on) { settleOpening(); settleClosing(); stopInertia(); stopFling(); }
        snapPulls();
        save();
        syncPanel();
        tagWall();
        placeIndicators(true);
        lenses.forEach((l) => l.update());
    }

    /* ---------------- 作品墙：FLIP 重排、主色占位、渐显 ---------------- */

    function tagWall() {
        api.pieces.forEach((fig, id) => {
            const w = window.Archive.get(id);
            if (!w) return;
            if (!fig.style.getPropertyValue('--fx-tone')) fig.style.setProperty('--fx-tone', w.pages[0].tone);
            ensureCard(fig, w);
            applyPage(fig, w);
            if (!has('wall')) return;
            const img = fig.querySelector('.piece-open img');
            if (img && !img.complete && !img.classList.contains('fx-pending')) {
                img.classList.add('fx-pending');
                const done = () => img.classList.remove('fx-pending');
                img.addEventListener('load', done, { once: true });
                img.addEventListener('error', done, { once: true });
            }
        });
    }

    /*
     * 卡片外壳：把图片按钮、勾选、页数、编辑按钮包进 .fx-card，牵引时整张卡片一起移动；
     * 外层 .piece-media 不动，留在原位当作“卡槽”。原版下这层外壳不带任何样式，不影响布局。
     * 多图作品在卡片身后放一叠实际的页面（每页一张，不是重复封面）：静止时只露出顶牌之后的两张，换牌时整叠参与轮转。
     */
    function ensureCard(fig, work) {
        const media = fig.querySelector('.piece-media');
        if (!media) return;
        let card = media.querySelector(':scope > .fx-card');
        if (!card) {
            card = document.createElement('div');
            card.className = 'fx-card';
            card.append(...media.childNodes);
            media.appendChild(card);
        }
        if (work.pages.length > 1 && !card.querySelector('.fx-layers')) {
            const layers = document.createElement('span');
            layers.className = 'fx-layers';
            layers.setAttribute('aria-hidden', 'true');
            work.pages.forEach((page, i) => {
                const img = new Image();
                img.alt = '';
                img.decoding = 'async';
                img.draggable = false;
                img.src = page.src;
                img.dataset.page = String(i);
                layers.appendChild(img);
            });
            card.prepend(layers);
        }
    }

    function captureWall() {
        if (!has('wall') || reduced()) return null;
        const rects = new Map();
        const vh = innerHeight;
        api.pieces.forEach((fig, id) => {
            if (!fig.isConnected) return;
            const r = fig.getBoundingClientRect();
            if (r.bottom > -240 && r.top < vh + 240) rects.set(id, r);
        });
        return { rects };
    }

    // 只动画视口内的作品：大图库下也只处理一屏，滚动和筛选保持流畅
    function playWall(snap) {
        tagWall();
        if (!snap || !has('wall') || reduced()) return;
        const vh = innerHeight;
        let entering = 0;
        api.state.results.forEach((w) => {
            const fig = api.pieces.get(w.id);
            if (!fig || !fig.isConnected) return;
            fig.getAnimations().forEach((a) => a.cancel());
            const r = fig.getBoundingClientRect();
            if (r.bottom < 0 || r.top > vh) return;
            const old = snap.rects.get(w.id);
            if (old) {
                const dx = old.left - r.left, dy = old.top - r.top, s = old.width / r.width;
                if (Math.abs(dx) < 1 && Math.abs(dy) < 1 && Math.abs(s - 1) < 0.01) return;
                fig.animate([
                    { transformOrigin: '0 0', transform: `translate(${dx}px, ${dy}px) scale(${s})` },
                    { transformOrigin: '0 0', transform: 'none' }
                ], { duration: 560, easing: GENTLE });
            } else if (snap.rects.size) {
                fig.animate([
                    { opacity: 0, transform: 'translateY(12px) scale(.98)' },
                    { opacity: 1, transform: 'none' }
                ], { duration: 420, delay: Math.min(entering++, 14) * 22, easing: SNAPPY, fill: 'backwards' });
            }
        });
    }

    // 全选：在 gallery.js 改变选中状态之前（捕获阶段）按屏幕位置写入延迟，形成由上到下的波纹
    let staggerTimer = 0;
    function prepStagger() {
        if (!has('select') || reduced()) return;
        const vh = innerHeight;
        const visible = [];
        api.state.results.forEach((w) => {
            const fig = api.pieces.get(w.id);
            if (!fig || !fig.isConnected) return;
            const r = fig.getBoundingClientRect();
            if (r.bottom > 0 && r.top < vh) visible.push([fig, r.top, r.left]);
        });
        visible.sort((a, b) => a[1] - b[1] || a[2] - b[2]);
        visible.forEach(([fig], i) => fig.style.setProperty('--fx-delay', Math.min(i * 18, 320) + 'ms'));
        clearTimeout(staggerTimer);
        staggerTimer = setTimeout(() => visible.forEach(([fig]) => fig.style.removeProperty('--fx-delay')), 1000);
    }
    document.addEventListener('click', (e) => { if (e.target.closest && e.target.closest('#bb-all')) prepStagger(); }, true);
    document.addEventListener('keydown', (e) => {
        const typing = e.target instanceof Element && e.target.matches('input, textarea, select');
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a' && !typing && !api.state.viewer) prepStagger();
    }, true);

    // 光泽跟随：只更新鼠标下那一张的 CSS 变量，每帧最多一次
    let sheenFrame = 0, sheenEvent = null;
    $('wall').addEventListener('pointermove', (e) => {
        if (!has('sheen')) return;
        sheenEvent = e;
        if (sheenFrame) return;
        sheenFrame = requestAnimationFrame(() => {
            sheenFrame = 0;
            const target = sheenEvent.target.closest && sheenEvent.target.closest('.piece-open');
            if (!target) return;
            const r = target.getBoundingClientRect();
            target.style.setProperty('--mx', (sheenEvent.clientX - r.left) + 'px');
            target.style.setProperty('--my', (sheenEvent.clientY - r.top) + 'px');
        });
    });

    /* ---------------- 滑动指示：范围下划线、密度滑块、按钮间滑动的悬停底 ---------------- */

    const scopes = document.querySelector('.scopes');
    const scopeInk = document.createElement('span');
    scopeInk.className = 'fx-scope-ink';
    scopes.appendChild(scopeInk);
    const density = document.querySelector('.density');
    const densityThumb = document.createElement('span');
    densityThumb.className = 'fx-density-thumb';
    density.prepend(densityThumb);

    function slideTo(node, target, jump) {
        if (!target) return;
        if (jump) node.classList.add('no-anim');
        node.style.width = target.offsetWidth + 'px';
        node.style.transform = `translateX(${target.offsetLeft}px)`;
        if (jump) { void node.offsetWidth; node.classList.remove('no-anim'); }
    }

    function placeIndicators(jump) {
        slideTo(scopeInk, scopes.querySelector('button[aria-current="true"]'), jump);
        slideTo(densityThumb, density.querySelector('button[aria-pressed="true"]'), jump);
    }
    new MutationObserver(() => placeIndicators(false)).observe(scopes, { subtree: true, attributes: true, attributeFilter: ['aria-current'], childList: true, characterData: true });
    new MutationObserver(() => placeIndicators(false)).observe(density, { subtree: true, attributes: true, attributeFilter: ['aria-pressed'] });
    addEventListener('resize', () => placeIndicators(true));

    function glider(container, selector) {
        const g = document.createElement('span');
        g.className = 'fx-glider';
        container.prepend(g);
        const move = (btn) => {
            const c = container.getBoundingClientRect(), r = btn.getBoundingClientRect();
            const first = !g.classList.contains('is-on');
            if (first) g.classList.add('no-anim');
            g.style.width = r.width + 'px';
            g.style.height = r.height + 'px';
            g.style.transform = `translate(${r.left - c.left}px, ${r.top - c.top}px)`;
            if (first) { void g.offsetWidth; g.classList.remove('no-anim'); }
            g.classList.add('is-on');
        };
        const hide = () => g.classList.remove('is-on');
        // 只停在可以滑入的按钮上；移到“编辑”这类实色按钮、缩放读数或下拉菜单时收起，避免高亮留在上一个按钮
        container.addEventListener('pointerover', (e) => {
            const btn = e.target.closest && e.target.closest(selector);
            if (btn && container.contains(btn) && !btn.disabled && !btn.closest('.sv-menu')) move(btn);
            else hide();
        });
        container.addEventListener('pointerleave', hide);
        container.addEventListener('focusin', (e) => {
            const btn = e.target.closest && e.target.closest(selector);
            if (btn && btn.matches(':focus-visible')) move(btn);
        });
        container.addEventListener('focusout', hide);
        return hide;
    }
    const hideBarGlider = glider($('batchbar'), '.bb-btn');
    const hideToolGlider = glider(document.querySelector('.sv-top-right'), '.sv-btn:not(.sv-btn-strong)');
    // 容器被隐藏时不会收到 pointerleave：看图器关闭、退出多选时主动清掉高亮
    new MutationObserver(() => { if (api.viewer.hidden) hideToolGlider(); }).observe(api.viewer, { attributes: true, attributeFilter: ['hidden'] });
    new MutationObserver(() => { if ($('batchbar').hidden) hideBarGlider(); }).observe($('batchbar'), { attributes: true, attributeFilter: ['hidden'] });

    /* ---------------- 液态玻璃：按控件形状生成透镜位移图，作为 backdrop-filter 使用 ---------------- */

    const svgNS = 'http://www.w3.org/2000/svg';
    const defsHost = document.createElementNS(svgNS, 'svg');
    defsHost.setAttribute('width', '0');
    defsHost.setAttribute('height', '0');
    defsHost.setAttribute('aria-hidden', 'true');
    defsHost.style.position = 'absolute';
    document.body.appendChild(defsHost);

    // 圆角矩形内侧 bezel 宽度内，采样点向内偏移，越靠边偏移越大：边缘把背后的画面“弯”进来，像一块凸透镜
    function lensMap(w, h, radius, bezel) {
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext('2d');
        const data = ctx.createImageData(w, h);
        const d = data.data;
        const cx = w / 2, cy = h / 2, hx = w / 2 - radius, hy = h / 2 - radius;
        for (let y = 0; y < h; y++) {
            for (let x = 0; x < w; x++) {
                const px = x + 0.5 - cx, py = y + 0.5 - cy;
                const qx = Math.abs(px) - hx, qy = Math.abs(py) - hy;
                const ox = Math.max(qx, 0), oy = Math.max(qy, 0);
                const sd = Math.hypot(ox, oy) + Math.min(Math.max(qx, qy), 0) - radius;
                const inside = -sd;
                let nx = 0, ny = 0, mag = 0;
                if (inside >= 0 && inside < bezel) {
                    if (qx > 0 && qy > 0) { const l = Math.hypot(qx, qy) || 1; nx = Math.sign(px) * qx / l; ny = Math.sign(py) * qy / l; }
                    else if (qx > qy) nx = Math.sign(px);
                    else ny = Math.sign(py);
                    const t = 1 - inside / bezel;
                    mag = t * t;
                }
                const i = (y * w + x) * 4;
                d[i] = 128 - nx * mag * 127;
                d[i + 1] = 128 - ny * mag * 127;
                d[i + 2] = 128;
                d[i + 3] = 255;
            }
        }
        ctx.putImageData(data, 0, 0);
        return canvas.toDataURL();
    }

    function lens(el, id, scale) {
        const filter = document.createElementNS(svgNS, 'filter');
        filter.id = id;
        filter.setAttribute('filterUnits', 'userSpaceOnUse');
        filter.setAttribute('primitiveUnits', 'userSpaceOnUse');
        filter.setAttribute('color-interpolation-filters', 'sRGB');
        filter.setAttribute('x', '0');
        filter.setAttribute('y', '0');
        const image = document.createElementNS(svgNS, 'feImage');
        image.setAttribute('x', '0');
        image.setAttribute('y', '0');
        image.setAttribute('preserveAspectRatio', 'none');
        image.setAttribute('result', 'map');
        const disp = document.createElementNS(svgNS, 'feDisplacementMap');
        disp.setAttribute('in', 'SourceGraphic');
        disp.setAttribute('in2', 'map');
        disp.setAttribute('scale', String(scale));
        disp.setAttribute('xChannelSelector', 'R');
        disp.setAttribute('yChannelSelector', 'G');
        filter.append(image, disp);
        defsHost.appendChild(filter);
        let size = '';
        const update = () => {
            if (!has('refract')) return;
            const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
            if (!w || !h || size === w + 'x' + h) return;
            size = w + 'x' + h;
            const radius = Math.min(parseFloat(getComputedStyle(el).borderTopLeftRadius) || h / 2, h / 2);
            [filter, image].forEach((n) => { n.setAttribute('width', w); n.setAttribute('height', h); });
            image.setAttribute('href', lensMap(w, h, radius, Math.min(h / 2, 16)));
            el.classList.add('fx-lens-ready');
        };
        new ResizeObserver(update).observe(el);
        return { update };
    }
    const lenses = [lens($('batchbar'), 'fx-lens-bar', 26), lens(document.querySelector('.sv-top-right'), 'fx-lens-tools', 20)];

    /* ---------------- 计数跳动、提示更新 ---------------- */

    new MutationObserver(() => {
        if (!has('bar') || reduced() || $('batchbar').hidden) return;
        $('bb-count').animate([{ transform: 'translateY(6px)', opacity: 0.35 }, { transform: 'none', opacity: 1 }], { duration: 320, easing: SNAPPY });
    }).observe($('bb-count'), { childList: true, characterData: true, subtree: true });

    new MutationObserver((records) => {
        const t = $('toast');
        if (!has('popover') || reduced() || t.hidden) return;
        if (records.some((r) => r.type === 'attributes')) return;
        t.animate([{ scale: '1.04' }, { scale: '1' }], { duration: 420, easing: BOUNCY });
    }).observe($('toast'), { childList: true, characterData: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });

    // 选择弹层：根据它出现在触发按钮的上方还是下方，设置展开原点
    new MutationObserver((records) => {
        records.forEach((r) => r.addedNodes.forEach((node) => {
            if (!(node instanceof HTMLElement) || !node.classList.contains('pk')) return;
            const p = node.getBoundingClientRect();
            let anchor = null, best = Infinity;
            document.querySelectorAll('[aria-expanded="true"]').forEach((b) => {
                const r2 = b.getBoundingClientRect();
                const dist = Math.hypot(r2.left - p.left, Math.min(Math.abs(r2.bottom - p.top), Math.abs(r2.top - p.bottom)));
                if (dist < best) { best = dist; anchor = r2; }
            });
            if (!anchor) return;
            const above = p.bottom <= anchor.top + 1;
            const ox = Math.max(0, Math.min(p.width, anchor.left + anchor.width / 2 - p.left));
            node.style.setProperty('--fx-origin', `${ox}px ${above ? '100%' : '0'}`);
            node.style.setProperty('--fx-dy', above ? '6px' : '-6px');
        }));
    }).observe(document.body, { childList: true });

    /* ---------------- 看图器：缩略图 ⇄ 大图的连续过渡 ---------------- */
    /*
     * 进入：让看图器里的真实大图直接从缩略图的位置“长”出来（独立的 translate / scale 属性，
     *       与平移缩放用的 transform 叠加），不再用替身图交接，避免交接那一下的跳动。
     * 退出：看图器立即隐藏、图库立即可操作，由替身图从大图“此刻”的实际位置飞回缩略图。
     * 随时可打断：翻页时进入动画直接落位；进入途中关闭、关闭途中再打开，都从当前画面位置接着走。
     */
    const OPEN = 'linear(0, 0.037, 0.111, 0.213, 0.325, 0.43, 0.537, 0.627, 0.712, 0.784, 0.84, 0.889, 0.927, 0.955, 0.977, 0.992, 1.003, 1.01, 1.014, 1.016, 1.016, 1.015, 1.014, 1.012, 1.011, 1.009, 1.007, 1.006, 1.004, 1.003, 1.002, 1.002, 1.001, 1.001, 1, 1, 1)';
    const LAND = 'linear(0, 0.04, 0.114, 0.216, 0.316, 0.424, 0.517, 0.608, 0.681, 0.75, 0.802, 0.85, 0.885, 0.916, 0.938, 0.956, 0.969, 0.98, 0.988, 0.993, 0.997, 0.999, 1.001, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.001, 1.001, 1.001, 1.001, 1.001, 1.001, 1)';

    let opening = null;
    let closing = null;
    let navDir = 0;

    function finishFlight(f) {
        f.anims.forEach((a) => { try { a.finish(); } catch (e) { a.cancel(); } });
        f.cleanup();
    }
    function settleOpening() { if (!opening) return; const f = opening; opening = null; finishFlight(f); }
    function settleClosing() { if (!closing) return; const f = closing; closing = null; finishFlight(f); }

    // 兜底：页面在后台时动画时间线可能暂停，到时直接收尾，不让看图器停在半透明状态
    function guard(alive, settleFn) {
        setTimeout(() => { if (alive()) settleFn(); }, 1200);
    }

    function place(node, r) {
        node.style.left = r.left + 'px';
        node.style.top = r.top + 'px';
        node.style.width = r.width + 'px';
        node.style.height = r.height + 'px';
    }

    function thumbRect(id) {
        const fig = api.pieces.get(id);
        const thumb = fig && fig.isConnected ? fig.querySelector('.piece-open img') : null;
        if (!thumb) return null;
        const r = thumb.getBoundingClientRect();
        return r.width && r.bottom > 0 && r.top < innerHeight ? { thumb, r } : null;
    }

    function transparentOf(color) {
        if (/^#[0-9a-f]{6}$/i.test(color)) return color + '00';
        const m = /^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/.exec(color);
        return m ? `rgba(${m[1]}, ${m[2]}, ${m[3]}, 0)` : 'transparent';
    }

    function viewerOpened(id) {
        // 关闭途中又点开同一件：从替身图此刻的位置接着放大，而不是先跳回缩略图
        const resume = closing && closing.id === id ? closing.clone.getBoundingClientRect() : null;
        settleClosing();
        settleOpening();
        snapPulls();
        if (!has('viewer')) return;
        const v = api.viewer;
        if (reduced()) { v.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 120 }); return; }
        const t = thumbRect(id);
        const from = resume || (t && t.r);
        if (!from || !from.width) {
            v.animate([{ opacity: 0, transform: 'scale(.985)' }, { opacity: 1, transform: 'none' }], { duration: 300, easing: SNAPPY });
            return;
        }
        const img = api.img;
        api.fitImage();                                   // 同步算出适应窗口后的位置：点击的当帧就开始动
        const to = img.getBoundingClientRect();
        const c = api.canvas.getBoundingClientRect();     // 大图布局原点（transform-origin 0 0）在画布左上角
        const kx = from.width / to.width, ky = from.height / to.height;
        const tx = from.left - c.left - kx * (to.left - c.left);
        const ty = from.top - c.top - ky * (to.top - c.top);
        if (t) t.thumb.style.visibility = 'hidden';
        const tint = getComputedStyle(v).backgroundColor;   // 跟随配色方案：可能是作品色调，也可能是方案固定的展墙色
        v.classList.add('fx-entering', 'fx-chrome-wait');
        const anims = [
            img.animate([
                { translate: `${tx}px ${ty}px`, scale: `${kx} ${ky}`, borderRadius: `${4 / kx}px` },
                { translate: '0px 0px', scale: '1 1', borderRadius: '0px' }
            ], { duration: 500, easing: OPEN }),
            v.animate([{ backgroundColor: transparentOf(tint) }, { backgroundColor: tint }], { duration: 320, easing: 'ease-out' })
        ];
        // 工具栏和标题在图片飞到一半时开始淡入，与落位重叠，而不是等它停稳
        const chromeTimer = setTimeout(() => v.classList.remove('fx-chrome-wait'), 170);
        const mine = {
            anims,
            cleanup: () => {
                clearTimeout(chromeTimer);
                v.classList.remove('fx-entering', 'fx-chrome-wait');
                if (t) t.thumb.style.visibility = '';
            }
        };
        opening = mine;
        anims[0].onfinish = () => { if (opening === mine) { opening = null; mine.cleanup(); } };
        guard(() => opening === mine, settleOpening);
    }

    function viewerClosing(id) {
        stopInertia();
        const v = api.viewer, img = api.img, vs = api.state.viewer;
        // 先读“此刻”画面上大图的位置和底色（可能正处在进入动画中途），再结束进入动画
        const fr = img.getBoundingClientRect();
        const bg = getComputedStyle(v).backgroundColor;
        const zoomedIn = vs && !vs.fit && !opening;
        const closingWork = window.Archive.get(id);
        if (has('stack') && vs && closingWork && closingWork.pages.length > 1) {
            pageOf.set(id, vs.page);
            const closingFig = api.pieces.get(id);
            if (closingFig) applyPage(closingFig, closingWork);
        }
        settleOpening();
        settleClosing();
        if (!has('viewer')) return;
        const backdrop = document.createElement('div');
        backdrop.className = 'fx-flight-backdrop';
        backdrop.style.background = bg;
        document.body.appendChild(backdrop);
        if (reduced() || zoomedIn || !fr.width) {
            const a = backdrop.animate([{ opacity: 1 }, { opacity: 0 }], { duration: reduced() ? 120 : 220, easing: 'ease-out', fill: 'forwards' });
            a.onfinish = () => backdrop.remove();
            return;
        }
        const clone = new Image();
        clone.className = 'fx-flight-img';
        clone.alt = '';
        clone.src = img.currentSrc || img.src;
        place(clone, fr);
        document.body.appendChild(clone);
        const anims = [backdrop.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 300, easing: 'ease-out', fill: 'forwards' })];
        const mine = {
            id, clone, anims, thumb: null,
            cleanup: () => { backdrop.remove(); clone.remove(); if (mine.thumb) mine.thumb.style.visibility = ''; }
        };
        closing = mine;
        // closeViewer 接下来会把这件作品滚动进视野；等它同步执行完（微任务）再取缩略图位置
        Promise.resolve().then(() => {
            if (closing !== mine) return;
            const to = thumbRect(id);
            let a;
            if (to) {
                mine.thumb = to.thumb;
                to.thumb.style.visibility = 'hidden';
                const s = to.r.width / fr.width;
                a = clone.animate([
                    { transform: 'none', borderRadius: '0px' },
                    { transform: `translate(${to.r.left - fr.left}px, ${to.r.top - fr.top}px) scale(${s}, ${to.r.height / fr.height})`, borderRadius: `${4 / s}px` }
                ], { duration: 420, easing: LAND, fill: 'forwards' });
            } else {
                a = clone.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.96)' }], { duration: 220, easing: 'ease-out', fill: 'forwards' });
            }
            anims.push(a);
            a.onfinish = () => { if (closing === mine) { closing = null; mine.cleanup(); } };
        });
        guard(() => closing === mine, settleClosing);
    }

    function navigate(delta) { navDir = Math.sign(delta); }

    document.addEventListener('click', (e) => {
        const b = e.target.closest && e.target.closest('#sv-pages button');
        if (!b || !api.state.viewer) return;
        const i = [...b.parentNode.children].indexOf(b);
        navDir = Math.sign(i - api.state.viewer.page);
    }, true);

    // 翻页 / 换作品：新图沿方向滑入；连按时上一段直接被替换，不排队
    function pageShown() {
        const dir = navDir;
        navDir = 0;
        if (!dir || !has('viewer') || opening) return;
        api.img.getAnimations().forEach((a) => a.cancel());
        if (reduced()) { api.img.animate([{ opacity: 0.4 }, { opacity: 1 }], { duration: 120 }); return; }
        api.img.animate([{ opacity: 0, translate: `${dir * 28}px 0` }, { opacity: 1, translate: '0 0' }], { duration: 300, easing: SNAPPY });
    }

    /* ---------------- 看图器拖动：1:1 跟手（原版逻辑），可选的松手惯性 ---------------- */

    let samples = [];
    let inertiaFrame = 0;
    function stopInertia() { cancelAnimationFrame(inertiaFrame); inertiaFrame = 0; }
    function panStart() { settleOpening(); stopInertia(); samples = []; }
    function panMove(e) {
        samples.push({ t: performance.now(), x: e.clientX, y: e.clientY });
        if (samples.length > 8) samples.shift();
    }
    function panEnd() {
        if (!has('inertia') || reduced()) return;
        const now = performance.now();
        const recent = samples.filter((s) => now - s.t < 90);
        if (recent.length < 2) return;
        const a = recent[0], b = recent[recent.length - 1];
        const dt = Math.max(1, b.t - a.t);
        let vx = (b.x - a.x) / dt, vy = (b.y - a.y) / dt;
        if (Math.hypot(vx, vy) < 0.2) return;
        let last = now;
        const tick = (t) => {
            const v = api.state.viewer;
            if (!v) return;
            const step = Math.min(32, t - last);
            last = t;
            const tx = v.tx + vx * step, ty = v.ty + vy * step;
            v.tx = tx;
            v.ty = ty;
            api.clampPan();
            if (v.tx !== tx) vx = 0;
            if (v.ty !== ty) vy = 0;
            api.applyTransform(false);
            const decay = Math.pow(0.994, step);
            vx *= decay;
            vy *= decay;
            inertiaFrame = Math.hypot(vx, vy) > 0.02 ? requestAnimationFrame(tick) : 0;
        };
        inertiaFrame = requestAnimationFrame(tick);
    }

    /* ---------------- 统一手势：纵向拖动浏览 / 侧向短拉牵引卡片 ---------------- */
    /*
     * 按下后先不判断；移动超过 6px 时按方向锁定一次，松手前不再切换，两种手势不会互抢：
     *   · 纵向为主，或按在卡片之间的空白处 → 拖动浏览：页面 1:1 跟手，松手顺势滑一段；
     *   · 侧向为主且按在卡片上 → 卡片牵引：卡片离开原位，带少量倾斜、抬起和阴影，越远阻力越大，松手平稳归位。
     * 只在非选择态、鼠标左键、无修饰键时接管；Ctrl / Shift + 点击仍是多选。
     * 一旦进入任一手势，随后那次 click 在捕获阶段被吞掉；Esc、窗口失焦或系统取消指针时撤销本次手势并归位。
     */
    const wallWrap = document.querySelector('.wall-wrap');
    const SLOP = 6;
    const PULL_X = 140, PULL_Y = 50, RUBBER = 0.55;
    const LAYER_BASE = { 1: { x: 4, y: -4, r: 2 }, 2: { x: 8, y: -8, r: 4 } };
    const ZERO = { ox: 0, oy: 0, rot: 0, lift: 0, s: 0 };
    const pulls = new Map();
    let gesture = null;
    let swallowClick = false;
    let flingFrame = 0;

    // 橡皮筋阻力（与 iOS 滚动越界同一公式）：位移随拉动距离增长但越来越慢，永远到不了上限 L
    const rubber = (x, L) => Math.sign(x) * (1 - 1 / (Math.abs(x) * RUBBER / L + 1)) * L;
    const unrubber = (y, L) => {
        const a = Math.min(Math.abs(y), L * 0.98);
        return Math.sign(y) * (L / RUBBER) * (1 / (1 - a / L) - 1);
    };

    // 抓卡片上半部往侧面拉，上边先走；抓下半部则反过来 —— 像按住纸片一角拖动时的自然转向
    function pullState(rx, ry, pivot) {
        const ox = rubber(rx, PULL_X), oy = rubber(ry, PULL_Y);
        return {
            ox, oy,
            rot: Math.max(-6, Math.min(6, (ox / PULL_X) * (2 + 4 * pivot) * 1.5)),
            lift: Math.min(1, Math.hypot(rx, ry) / 36),
            s: Math.min(1, Math.abs(ox) / 60)
        };
    }

    function scaled(st, k) {
        return { ox: st.ox * k, oy: st.oy * k, rot: st.rot * k, lift: st.lift * k, s: st.s * k };
    }

    // 卡片整体位移 / 倾斜 / 抬起；后层按深度滞后、扇形展开，前后层动得不一样
    function renderPull(p, st) {
        p.card.style.transform = `translate(${st.ox.toFixed(2)}px, ${st.oy.toFixed(2)}px) rotate(${st.rot.toFixed(2)}deg) scale(${(1 + 0.03 * st.lift).toFixed(4)})`;
        p.fig.style.setProperty('--fx-lift', st.lift.toFixed(3));
        p.layers.forEach((img) => {
            const d = Number(img.dataset.depth), b = LAYER_BASE[d];
            const lag = 0.25 * d;
            img.style.transform = `translate(${(b.x * (1 + 1.4 * st.s) - st.ox * lag).toFixed(2)}px, ${(b.y * (1 + 1.4 * st.s) - st.oy * lag).toFixed(2)}px) rotate(${(b.r * (1 + 1.4 * st.s) - st.rot * 0.35 * d).toFixed(2)}deg)`;
        });
    }

    function clearPull(p) {
        cancelAnimationFrame(p.frame);
        clearTimeout(p.guard);
        p.card.style.transform = '';
        p.layers.forEach((img) => { img.style.transform = ''; });
        p.fig.style.removeProperty('--fx-lift');
        p.fig.classList.remove('fx-pulled');
        pulls.delete(p.fig);
        lower(p.fig);
    }

    function snapPulls() { [...pulls.values()].forEach(clearPull); snapDecks(); }

    function pullFor(fig, card, x, y) {
        let p = pulls.get(fig);
        if (!p) {
            const r = card.getBoundingClientRect();
            card.style.transformOrigin = `${(x - r.left).toFixed(1)}px ${(y - r.top).toFixed(1)}px`;
            p = { fig, card, layers: [...card.querySelectorAll('.fx-layers img[data-depth]')], state: { ...ZERO }, k: 1, v: 0, frame: 0, guard: 0 };
            pulls.set(fig, p);
        }
        fig.classList.add('fx-pulled');
        return p;
    }

    // 松手：临界阻尼弹簧把整体系数 k 从 1 收到 0（不过冲、不来回晃），约 0.4 秒平稳回到原位
    function releasePull(p) {
        if (!p) return;
        cancelAnimationFrame(p.frame);
        clearTimeout(p.guard);
        if (reduced()) { clearPull(p); return; }
        p.k = 1;
        p.v = 0;
        let last = performance.now();
        const w = 0.02;
        const tick = (now) => {
            const dt = Math.min(32, now - last);
            last = now;
            p.v += (-w * w * p.k - 2 * w * p.v) * dt;
            p.k += p.v * dt;
            if (p.k < 0.002 && Math.abs(p.v) < 0.0002) { clearPull(p); return; }
            renderPull(p, scaled(p.state, p.k));
            p.frame = requestAnimationFrame(tick);
        };
        p.frame = requestAnimationFrame(tick);
        // 页面在后台时动画帧会暂停：到时直接归位，不让卡片停在半空
        p.guard = setTimeout(() => { if (pulls.get(p.fig) === p) clearPull(p); }, 1500);
    }

    // 归位途中再次按住：从卡片此刻的位置接着拉，而不是跳回原位重来
    function holdPull(p) {
        cancelAnimationFrame(p.frame);
        clearTimeout(p.guard);
        p.state = scaled(p.state, p.k);
        p.k = 1;
        renderPull(p, p.state);
        return { rx: unrubber(p.state.ox, PULL_X), ry: unrubber(p.state.oy, PULL_Y) };
    }

    /* ---------------- 多图作品：双向循环牌堆 ---------------- */
    /*
     * 每件多图作品是一叠牌。“翻到哪一页”和“牌怎么动”分开处理：
     *   · 出牌动画：无论向左还是向右，手下动的始终是按下时的顶牌。它跟手滑到牌堆外，松手过线后绕回压到牌堆最底。
     *   · 目标页：由拖动方向决定。向左拖，顶牌下面依次排后一页、再后一页（1→2→3→1）；
     *     向右拖，下面依次排前一页、再前一页（1→3→2→1）。按方向把待露出的页面摆到顶牌正下方，随顶牌离开逐渐升起。
     * 拖动途中越过原点反向，同一张顶牌跟到另一侧，下面的待露出页面随之换成另一方向的顺序。
     * 进度 u：0 为顶牌在原位，0.5 为整张牌刚好离开牌堆（此时切换层级，看不到穿插），1 为压到最底。
     * 没过线就把 u 收回 0，原顶牌归位。静止时身后露出的两张沿用上一次翻牌的方向。
     * 只轮换卡片的浏览状态，作品原始页序不变；点开看图器从当前顶牌所在页进入。
     */
    const pageOf = new Map();
    const orderOf = new Map();   // 身后的排列方向：1 依次是后面的页，-1 依次是前面的页
    const decks = new Map();
    const SWIPE_COMMIT = 0.28, SWIPE_FLICK = 0.5, BLEND_MS = 200;
    // 换牌时用悬停展开后的叠卡位置：拖动时指针就在卡上，落定后与 CSS 的悬停状态一致，不会抖一下
    const SLOTS = [{ x: 0, y: 0, r: 0 }, { x: 6, y: -7, r: 3.2 }, { x: 12, y: -13, r: 6.4 }];
    const mod = (a, n) => ((a % n) + n) % n;
    const workOfFig = (fig) => window.Archive.get(Number(fig.dataset.id));
    // 第几张（0 为顶牌）：按排列方向从当前页数过去
    const depthOf = (page, cur, dir, n) => mod((page - cur) * dir, n);

    // 静止时：正面由图片按钮显示，身后最多两张按 data-depth 摆好（位置由 CSS 给出，悬停时微微展开），其余隐藏
    function applyPage(fig, work) {
        const n = work.pages.length;
        if (n < 2) return;
        const cur = has('stack') ? mod(pageOf.get(work.id) || 0, n) : 0;
        const dir = has('stack') ? orderOf.get(work.id) || 1 : 1;
        const front = fig.querySelector('.piece-open img');
        if (front && front.getAttribute('src') !== work.pages[cur].src) front.src = work.pages[cur].src;
        const mark = fig.querySelector('.piece-marks .mark');
        if (mark) mark.textContent = has('stack') ? `${cur + 1} / ${n}` : `${n} 页`;
        fig.querySelectorAll('.fx-layers img').forEach((img) => {
            const k = depthOf(Number(img.dataset.page), cur, dir, n);
            if (k === 1 || k === 2) img.dataset.depth = String(k);
            else delete img.dataset.depth;
        });
    }

    function startPage(id) { return has('stack') ? pageOf.get(id) || 0 : 0; }

    // 叠卡位置在相邻两层之间线性过渡；第 3 层及更深的都藏在第 2 层正下方
    function slotAt(r) {
        const a = Math.min(2, Math.floor(r)), b = Math.min(2, a + 1), t = Math.min(2, r) - a;
        const p = SLOTS[a], q = SLOTS[b];
        return { x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t, rot: p.r + (q.r - p.r) * t, depth: Math.min(2, r) };
    }

    function deckFor(fig) {
        let d = decks.get(fig);
        if (d) return d;
        const work = workOfFig(fig), n = work.pages.length;
        const w = fig.querySelector('.fx-card').getBoundingClientRect().width;
        const dir = orderOf.get(work.id) || 1;
        d = {
            fig, work, n, w, A: w + 24, imgs: [...fig.querySelectorAll('.fx-layers img')], mark: fig.querySelector('.piece-marks .mark'),
            cur: mod(pageOf.get(work.id) || 0, n), dir, step: dir, side: dir > 0 ? -1 : 1, u: 0, base: 0,
            held: false, flight: null, blend: null, last: null, frame: 0, guard: 0
        };
        fig.classList.add('fx-decking');
        decks.set(fig, d);
        return d;
    }

    // 每张牌此刻应在的位置：顶牌沿出牌轨迹走，其余按 r = k - u 连续上升
    function posesOf(d) {
        const { n, A, u, side, step } = d;
        const bottom = slotAt(n - 1);
        const poses = new Map();
        d.imgs.forEach((img) => {
            const k = depthOf(Number(img.dataset.page), d.cur, step, n);
            let p;
            if (k === 0) {
                const out = u <= 0.5, t = out ? u / 0.5 : (u - 0.5) / 0.5;
                const ax = side * A, ar = (ax / d.w) * 7;
                p = out
                    ? { x: ax * t, y: 0, rot: ar * t, depth: 0, z: 60, lift: Math.sin(Math.PI * u) }
                    : { x: ax + (bottom.x - ax) * t, y: bottom.y * t, rot: ar + (bottom.rot - ar) * t, depth: bottom.depth * t, z: 0, lift: 0 };
                p.y -= 8 * Math.sin(Math.PI * u);
                p.scale = 1 + 0.03 * Math.sin(2 * Math.PI * u);   // 抽出时略微抬起放大，绕回底部时略微缩小，读出前后
            } else {
                const r = k - u;
                p = { ...slotAt(r), z: 40 - Math.round(r * 10), lift: 0, scale: 1 };
            }
            poses.set(img, p);
        });
        return poses;
    }

    function renderDeck(d, now) {
        let poses = posesOf(d);
        // 衔接：上一次翻牌被打断时，各张从当时的位置在 0.2 秒内并到新位置，而不是瞬移
        if (d.blend) {
            const t = Math.min(1, (now - d.blend.t0) / BLEND_MS), b = Math.pow(1 - t, 3);
            if (t >= 1) d.blend = null;
            else {
                const mixed = new Map();
                poses.forEach((p, img) => {
                    const f = d.blend.from.get(img) || p;
                    const mix = (key) => p[key] + (f[key] - p[key]) * b;
                    mixed.set(img, { x: mix('x'), y: mix('y'), rot: mix('rot'), depth: mix('depth'), scale: mix('scale'), lift: mix('lift'), z: p.z });
                });
                poses = mixed;
            }
        }
        poses.forEach((p, img) => {
            img.style.zIndex = String(p.z);
            img.style.transform = `translate(${p.x.toFixed(2)}px, ${p.y.toFixed(2)}px) rotate(${p.rot.toFixed(2)}deg) scale(${p.scale.toFixed(4)})`;
            img.style.filter = p.depth > 0.01 ? `brightness(${(1 - 0.03 * p.depth).toFixed(3)})` : '';
            img.style.boxShadow = p.lift > 0.01
                ? `0 ${(4 + 18 * p.lift).toFixed(1)}px ${(10 + 30 * p.lift).toFixed(1)}px -10px color-mix(in srgb, var(--fx-tone, #24211d) 75%, transparent), 0 1px 3px rgba(36, 33, 29, .2)`
                : '';
        });
        d.last = poses;
        // 角标在顶牌离开牌堆（u 过 0.5）的那一刻换成露出来的那一页
        if (d.mark) d.mark.textContent = `${mod(d.u >= 0.5 ? d.cur + d.step : d.cur, d.n) + 1} / ${d.n}`;
    }

    function settleDeck(d) {
        cancelAnimationFrame(d.frame);
        clearTimeout(d.guard);
        d.imgs.forEach((img) => { img.style.zIndex = img.style.transform = img.style.filter = img.style.boxShadow = ''; });
        d.fig.classList.remove('fx-decking');
        decks.delete(d.fig);
        lower(d.fig);
        pageOf.set(d.work.id, d.cur);
        orderOf.set(d.work.id, d.dir);
        applyPage(d.fig, d.work);
    }

    // 翻牌完成：顶牌已压到最底，露出来的那页成为新的顶牌；身后的排列沿用这次的方向
    function landFlight(d) {
        d.cur = mod(d.cur + d.step, d.n);
        d.dir = d.step;
        d.u = 0;
        d.flight = null;
    }

    function runDeck(d) {
        cancelAnimationFrame(d.frame);
        const step = (now) => {
            if (decks.get(d.fig) !== d) return;
            const f = d.flight;
            if (f) {
                const t = Math.min(1, (now - f.t0) / f.dur);
                d.u = f.from + (f.to - f.from) * (1 - Math.pow(1 - t, 3));
                if (t >= 1) { if (f.to === 1) landFlight(d); else { d.u = 0; d.flight = null; } }
            }
            renderDeck(d, now);
            if (d.flight || d.blend) d.frame = requestAnimationFrame(step);
            else if (!d.held) settleDeck(d);
        };
        d.frame = requestAnimationFrame(step);
    }

    function flyDeck(d, to) {
        d.flight = { from: d.u, to, t0: performance.now(), dur: 200 + 300 * Math.abs(to - d.u) };
        // 动画途中点开看图器，也从即将停下的那一页进入
        pageOf.set(d.work.id, to === 1 ? mod(d.cur + d.step, d.n) : d.cur);
        runDeck(d);
        // 页面在后台时动画帧会暂停：到时直接落定，不让牌停在半空
        clearTimeout(d.guard);
        d.guard = setTimeout(() => { if (decks.get(d.fig) === d && !d.held) finishDeck(d); }, d.flight.dur + 600);
    }

    // 按下：若上一张正在飞回原位，接着拖这张牌；若上一张正在翻走，就算它已经翻完，
    // 新顶牌接手，各张从此刻的位置并过去（连续快速滑动时一下一页）
    function holdDeck(fig) {
        const d = deckFor(fig);
        if (d.flight && d.flight.to === 1) {
            landFlight(d);
            d.side = d.step > 0 ? -1 : 1;
            if (d.last) d.blend = { from: d.last, t0: performance.now() };
        }
        d.flight = null;
        clearTimeout(d.guard);
        d.held = true;
        d.base = d.side * d.u * 2 * d.A;
        if (d.blend) runDeck(d);
        return d;
    }

    // 拖动：顶牌 1:1 跟手，快到顶点时加阻力，按住时不会越过顶点把牌塞回去；越过原点就换到另一侧、换一组待露出的页
    function dragDeck(d, dx) {
        const raw = d.base + dx, lim = d.A * 0.8, a = Math.abs(raw);
        const x = Math.sign(raw) * (a <= lim ? a : lim + rubber(a - lim, d.A * 0.18));
        if (x !== 0) { d.side = Math.sign(x); d.step = -d.side; }
        d.u = Math.abs(x) / (2 * d.A);
        d.x = x;
        renderDeck(d, performance.now());
    }

    // 拉过 28% 卡宽（至少 56px）或顺着方向快速甩动，就把顶牌送到最底；否则原顶牌归位
    function releaseDeck(d, samples, cancelled) {
        d.held = false;
        const x = d.side * d.u * 2 * d.A;
        let commit = false;
        if (!cancelled && d.u > 0) {
            const now = performance.now();
            const recent = samples.filter((p) => now - p.t < 80);
            const v = recent.length > 1 ? (recent[recent.length - 1].x - recent[0].x) / Math.max(1, recent[recent.length - 1].t - recent[0].t) : 0;
            const far = Math.abs(x) > Math.max(56, d.w * SWIPE_COMMIT);   // 窄卡片也至少拉 56px，避免轻轻一碰就换牌
            const flick = Math.abs(v) > SWIPE_FLICK && Math.abs(x) > 16 && Math.sign(v) === Math.sign(x);
            commit = far || flick;
        }
        if (d.u === 0 && !d.blend) { settleDeck(d); return; }
        flyDeck(d, commit ? 1 : 0);
    }

    function finishDeck(d) {
        d.held = false;
        if (d.flight && d.flight.to === 1) landFlight(d);
        d.flight = null;
        d.blend = null;
        d.u = 0;
        settleDeck(d);
    }

    function snapDecks() { [...decks.values()].forEach(finishDeck); }

    /* ---------------- 整组层级：与组内牌序分开管理 ---------------- */
    /*
     * 组内谁压着谁只由牌堆自己的 z-index 决定（顶牌归底只改它在本作品内的顺序）。
     * 整件作品相对邻图的层级单独管理，只有两档：
     *   fx-raised      压在相邻作品上方：悬停展开、松手后回弹 / 归底、收回过程中；
     *   fx-raised-top  正在手下的那一件，比其他抬起的作品再高一档（连续操作两件相邻作品时，手下这件不会被上一件盖住）。
     * 交互（拖动、换牌动画、牵引回弹）全部结束、指针也离开之后，再等悬停展开的叠卡收回（.45s）才回到普通层级；
     * 中途又有新的交互或悬停就取消这次下降。不会给单张牌不断加大 z-index。
     */
    const RAISE_LINGER = 480;
    const lowering = new Map();

    function raise(fig, top) {
        clearTimeout(lowering.get(fig));
        lowering.delete(fig);
        fig.classList.add('fx-raised');
        if (!top) return;
        document.querySelectorAll('.piece.fx-raised-top').forEach((f) => { if (f !== fig) f.classList.remove('fx-raised-top'); });
        fig.classList.add('fx-raised-top');
    }

    function busy(fig) {
        return decks.has(fig) || pulls.has(fig) || (gesture && gesture.fig === fig) || fig.matches(':hover');
    }

    function lower(fig) {
        if (!fig.classList.contains('fx-raised')) return;
        clearTimeout(lowering.get(fig));
        lowering.set(fig, setTimeout(() => {
            lowering.delete(fig);
            if (busy(fig)) return;   // 仍在交互或悬停：等它结束时会再来一次
            fig.classList.remove('fx-raised', 'fx-raised-top');
        }, reduced() ? 0 : RAISE_LINGER));
    }

    // 多图作品悬停时叠卡会向外展开，展开期间整组压在邻图上方
    wallWrap.addEventListener('pointerover', (e) => {
        const fig = e.target.closest && e.target.closest('.piece');
        if (fig && has('stack') && fig.querySelector('.fx-layers')) raise(fig, false);
    });
    wallWrap.addEventListener('pointerout', (e) => {
        const fig = e.target.closest && e.target.closest('.piece');
        if (fig && !(e.relatedTarget && fig.contains(e.relatedTarget))) lower(fig);
    });

    /* ---------------- 焦点框：按输入方式区分 ---------------- */
    // 鼠标按下后，作品按钮不再画焦点框（拖动换牌时它只包着一个空槽位）；一按键盘立刻恢复，Tab / 方向键导航照常可见
    addEventListener('pointerdown', () => root.classList.add('fx-pointing'), true);
    addEventListener('keydown', (e) => { if (!e.ctrlKey && !e.metaKey) root.classList.remove('fx-pointing'); }, true);

    function stopFling() { cancelAnimationFrame(flingFrame); flingFrame = 0; }

    wallWrap.addEventListener('pointerdown', (e) => {
        stopFling();
        if (!(has('drag') || has('tether') || has('stack')) || e.pointerType !== 'mouse' || e.button !== 0) return;
        if (e.ctrlKey || e.shiftKey || e.metaKey || e.altKey || api.state.viewer) return;
        // 选择状态下不牵引、不拖动浏览；但按住移动过的那次松手同样不算点击，避免误选
        if (api.state.selecting) {
            gesture = { id: e.pointerId, x0: e.clientX, y0: e.clientY, s0: scrollY, mode: null, passive: true, fig: null, card: null, base: { rx: 0, ry: 0 }, pivot: 0, samples: [] };
            return;
        }
        const fig = e.target.closest('.piece');
        const card = fig ? fig.querySelector('.fx-card') : null;
        const held = card && pulls.get(fig);
        const base = held ? holdPull(held) : { rx: 0, ry: 0 };
        let pivot = 0;
        if (card) {
            const r = card.getBoundingClientRect();
            pivot = Math.max(-1, Math.min(1, (0.5 - (e.clientY - r.top) / r.height) * 2));
        }
        const pages = card ? workOfFig(fig).pages.length : 0;
        gesture = { id: e.pointerId, x0: e.clientX, y0: e.clientY, s0: scrollY, mode: null, fig: card ? fig : null, card, pages, base, pivot, samples: [] };
    });

    function beginGesture(g, e) {
        document.body.classList.add('fx-dragging');
        try { wallWrap.setPointerCapture(e.pointerId); } catch (err) { /* 指针已释放时无法捕获，继续用 window 事件 */ }
        const sel = getSelection();
        if (sel) sel.removeAllRanges();
        if (g.fig && (g.mode === 'tether' || g.mode === 'swipe')) raise(g.fig, true);
        if (g.mode === 'tether') pullFor(g.fig, g.card, g.x0, g.y0);
        else if (g.mode === 'swipe' && !reduced()) g.deck = holdDeck(g.fig);
        else if (g.fig && pulls.get(g.fig)) releasePull(pulls.get(g.fig));
    }

    addEventListener('pointermove', (e) => {
        const g = gesture;
        if (!g || e.pointerId !== g.id || g.mode === 'cancelled') return;
        const dx = e.clientX - g.x0, dy = e.clientY - g.y0;
        if (!g.mode) {
            if (Math.hypot(dx, dy) < SLOP) return;
            const sideways = Math.abs(dx) > Math.abs(dy) * 1.15;
            if (g.passive) g.mode = 'idle';
            else if (sideways && g.card && g.pages > 1 && has('stack')) g.mode = 'swipe';
            else if (sideways && g.card && has('tether')) g.mode = reduced() ? 'idle' : 'tether';
            else g.mode = has('drag') ? 'scroll' : 'idle';
            beginGesture(g, e);
        }
        if (g.mode === 'scroll') {
            scrollTo(scrollX, g.s0 - dy);
            g.samples.push({ t: performance.now(), y: e.clientY });
            if (g.samples.length > 8) g.samples.shift();
        } else if (g.mode === 'swipe') {
            g.samples.push({ t: performance.now(), x: e.clientX });
            if (g.samples.length > 8) g.samples.shift();
            g.dx = dx;
            if (g.deck) dragDeck(g.deck, dx);
        } else if (g.mode === 'tether') {
            const p = pulls.get(g.fig);
            p.state = pullState(g.base.rx + dx, g.base.ry + dy, g.pivot);
            renderPull(p, p.state);
        }
    });

    function endGesture(e, cancelled) {
        const g = gesture;
        if (!g || e.pointerId !== g.id) return;
        gesture = null;
        if (!g.mode) {
            // 普通单击：若按下时打断了某张卡片的归位，让它继续归位
            if (g.fig && pulls.get(g.fig)) releasePull(pulls.get(g.fig));
            return;
        }
        document.body.classList.remove('fx-dragging');
        swallowClick = true;
        setTimeout(() => { swallowClick = false; }, 0);
        if (g.mode === 'tether') releasePull(pulls.get(g.fig));
        else if (g.mode === 'swipe') {
            if (g.deck) releaseDeck(g.deck, g.samples, cancelled);
            else if (!cancelled && Math.abs(g.dx || 0) > 40) {
                // 减少动效：不跟手，松手直接循环换到下一张 / 上一张
                const w = workOfFig(g.fig), n = w.pages.length;
                const dir = g.dx < 0 ? 1 : -1;
                pageOf.set(w.id, mod((pageOf.get(w.id) || 0) + dir, n));
                orderOf.set(w.id, dir);
                applyPage(g.fig, w);
            }
        } else if (g.mode === 'scroll' && !cancelled && !reduced()) fling(g.samples);
        if (g.fig) lower(g.fig);   // 动画还在跑时 lower 会让步，等落定时再降
    }

    // 撤销：卡片归位，拖动浏览回到按下前的位置；保留手势记录直到松手，以便吞掉那次 click
    function cancelGesture() {
        const g = gesture;
        if (!g) return;
        if (g.mode === 'scroll') scrollTo(scrollX, g.s0);
        if (g.fig && pulls.get(g.fig)) releasePull(pulls.get(g.fig));
        if (g.deck) { releaseDeck(g.deck, [], true); g.deck = null; }
        document.body.classList.remove('fx-dragging');
        if (g.mode) g.mode = 'cancelled';
        else gesture = null;
    }

    addEventListener('pointerup', (e) => endGesture(e, false));
    addEventListener('pointercancel', (e) => endGesture(e, true));
    addEventListener('blur', () => { cancelGesture(); gesture = null; });
    addEventListener('click', (e) => {
        if (!swallowClick) return;
        swallowClick = false;
        e.preventDefault();
        e.stopPropagation();
    }, true);
    document.addEventListener('dragstart', (e) => { if (gesture) e.preventDefault(); }, true);
    document.addEventListener('selectstart', (e) => { if (gesture) e.preventDefault(); });
    addEventListener('wheel', stopFling, { passive: true });
    addEventListener('keydown', (e) => {
        stopFling();
        if (e.key === 'Escape' && gesture && gesture.mode && gesture.mode !== 'cancelled') {
            e.preventDefault();
            e.stopPropagation();
            cancelGesture();
        }
    }, true);

    function fling(points) {
        const now = performance.now();
        const recent = points.filter((p) => now - p.t < 80);
        if (recent.length < 2) return;
        const a = recent[0], b = recent[recent.length - 1];
        let v = -(b.y - a.y) / Math.max(1, b.t - a.t);
        if (Math.abs(v) < 0.25) return;
        let last = now;
        const tick = (t) => {
            const dt = Math.min(32, t - last);
            last = t;
            const before = scrollY;
            scrollBy(0, v * dt);
            v *= Math.pow(0.995, dt);
            flingFrame = Math.abs(v) > 0.03 && scrollY !== before ? requestAnimationFrame(tick) : 0;
        };
        flingFrame = requestAnimationFrame(tick);
    }

    window.GalleryFx = {
        captureWall, playWall, viewerOpened, viewerClosing,
        settle: () => { settleOpening(); stopInertia(); },
        navigate, pageShown, panStart, panMove, panEnd, startPage
    };

    /* ---------------- 实验面板 ---------------- */

    const lab = document.createElement('div');
    lab.className = 'fxlab';
    lab.innerHTML = `
        <button class="fxlab-pill" type="button" aria-expanded="false" aria-controls="fxlab-panel" title="动效与材质实验（Alt + X 快速切换原版 / 新版）">
            <span class="fxlab-dot" aria-hidden="true"></span><span>效果</span><b data-role="state"></b>
        </button>
        <section class="fxlab-panel" id="fxlab-panel" aria-label="动效与材质实验" hidden>
            <header class="fxlab-head"><strong>动效与材质实验</strong><button class="fxlab-x" type="button" aria-label="收起">×</button></header>
            <div class="fxlab-seg" role="group" aria-label="版本">
                <button type="button" data-ver="off">原版</button><button type="button" data-ver="on">新版</button>
            </div>
            <p class="fxlab-hint">Alt + X 随时切换对照；选择只保存在本机浏览器。</p>
            <div class="fxlab-group">分项</div>
            ${AREAS.map(([k, t, d]) => `<label class="fxlab-row"><input type="checkbox" data-area="${k}"><span><b>${t}</b><small>${d}</small></span></label>`).join('')}
            <div class="fxlab-group">浮动控件材质（批量操作条、看图器工具栏）</div>
            <div class="fxlab-seg fxlab-mat" role="group" aria-label="材质">
                <button type="button" data-mat="solid">实底</button><button type="button" data-mat="acrylic">亚克力</button><button type="button" data-mat="glass">液态玻璃</button>
            </div>
            ${EXTRAS.map(([k, t, d]) => `<label class="fxlab-row"><input type="checkbox" data-extra="${k}"><span><b>${t}</b><small>${d}</small></span></label>`).join('')}
            <div class="fxlab-group">无障碍</div>
            <label class="fxlab-row"><input type="checkbox" data-extra="reduce"><span><b>模拟“减少动效”</b><small data-role="system"></small></span></label>
        </section>`;
    document.body.appendChild(lab);
    const pill = lab.querySelector('.fxlab-pill');
    const panel = lab.querySelector('.fxlab-panel');

    function syncPanel() {
        const on = cfg.version === 'on';
        lab.classList.toggle('is-on', on);
        lab.querySelector('[data-role="state"]').textContent = on ? '新版' : '原版';
        panel.classList.toggle('is-off', !on);
        lab.querySelectorAll('[data-ver]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.ver === cfg.version)));
        lab.querySelectorAll('[data-mat]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.mat === cfg.material)));
        lab.querySelectorAll('[data-area]').forEach((i) => { i.checked = !!cfg.areas[i.dataset.area]; });
        lab.querySelectorAll('[data-extra]').forEach((i) => { i.checked = !!cfg[i.dataset.extra]; });
        lab.querySelector('[data-role="system"]').textContent = '系统设置当前：' + (systemReduce.matches ? '已开启减少动效' : '未开启') + '。开启后位移和回弹全部取消，只保留短淡入淡出';
    }

    function setPanel(open) {
        panel.hidden = !open;
        pill.setAttribute('aria-expanded', String(open));
    }

    pill.addEventListener('click', () => setPanel(panel.hidden));
    lab.querySelector('.fxlab-x').addEventListener('click', () => setPanel(false));
    lab.addEventListener('click', (e) => {
        const ver = e.target.closest('[data-ver]');
        const mat = e.target.closest('[data-mat]');
        if (ver) { cfg.version = ver.dataset.ver; apply(); }
        if (mat) { cfg.material = mat.dataset.mat; apply(); }
    });
    lab.addEventListener('change', (e) => {
        const i = e.target;
        if (i.dataset.area) cfg.areas[i.dataset.area] = i.checked;
        if (i.dataset.extra) cfg[i.dataset.extra] = i.checked;
        apply();
    });
    document.addEventListener('mousedown', (e) => { if (!panel.hidden && !lab.contains(e.target)) setPanel(false); }, true);
    document.addEventListener('keydown', (e) => {
        if (e.altKey && !e.ctrlKey && e.key.toLowerCase() === 'x') {
            e.preventDefault();
            e.stopPropagation();
            cfg.version = cfg.version === 'on' ? 'off' : 'on';
            apply();
            pill.classList.remove('fxlab-flash');
            void pill.offsetWidth;
            pill.classList.add('fxlab-flash');
        } else if (e.key === 'Escape' && !panel.hidden) {
            e.stopPropagation();
            setPanel(false);
            pill.focus();
        }
    }, true);
    systemReduce.addEventListener('change', syncPanel);

    apply();
})();
