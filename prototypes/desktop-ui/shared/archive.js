/*
 * 两套原型共用的测试图库：同一批作品、作者、标签和同一套查询 / 编辑 / 导入模拟。
 * 只在内存中运行，刷新即恢复；不连接后端，也不读写真实图库。
 * 作品图片用确定性的 SVG 生成，两套原型看到的是完全相同的素材。
 */
(function () {
    'use strict';

    function rng(seed) {
        let a = seed >>> 0;
        return function () {
            a = (a + 0x6d2b79f5) >>> 0;
            let t = a;
            t = Math.imul(t ^ (t >>> 15), t | 1);
            t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
        };
    }

    // 每套配色：天空、远景、中景、近景、光源、点缀
    const PALETTES = {
        dawn: ['#f2dccb', '#dcae9c', '#b07a78', '#5e4256', '#fff4e4', '#e9836b'],
        mist: ['#e3e8e4', '#bccbc3', '#8fa59a', '#4f6559', '#f7f6ee', '#c9a46a'],
        dusk: ['#2c2f4a', '#4b4568', '#7a5d7e', '#1a1a2c', '#f6d7a7', '#e58c6f'],
        night: ['#141b2d', '#263552', '#3c4f73', '#0b0f1a', '#f3efd8', '#8fb4d9'],
        sea: ['#dce9ec', '#9cc3cc', '#5c95a3', '#24505e', '#fbf3df', '#e4a75b'],
        moss: ['#ece7d6', '#c3c49a', '#86915f', '#3d4a2f', '#fbf5e3', '#c7643f'],
        ember: ['#f4e3cf', '#e7b07a', '#c46a45', '#5a2d24', '#fff0d9', '#2e4a5a'],
        lilac: ['#ebe4ef', '#c9b9d8', '#9a82b5', '#4a3a63', '#fbf6f0', '#e2a6a0'],
        ink: ['#efece6', '#c9c4ba', '#8d877d', '#2f2c28', '#f8f5ef', '#b5442f'],
        sky: ['#d6e6f5', '#a9c8e8', '#6f9fcf', '#2f4d73', '#fffaf0', '#f0b45a']
    };

    function smoothPath(points, w, h) {
        let d = `M0 ${h} L${points[0][0].toFixed(1)} ${points[0][1].toFixed(1)}`;
        for (let i = 1; i < points.length; i++) {
            const [x0, y0] = points[i - 1];
            const [x1, y1] = points[i];
            const mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
            d += ` Q${x0.toFixed(1)} ${y0.toFixed(1)} ${mx.toFixed(1)} ${my.toFixed(1)}`;
        }
        const last = points[points.length - 1];
        return d + ` L${last[0].toFixed(1)} ${last[1].toFixed(1)} L${w} ${h} Z`;
    }

    function ridge(r, w, h, base, amp, jag) {
        const n = jag ? 18 : 10;
        const f1 = 1 + r() * 2, f2 = 3 + r() * 4, p1 = r() * 6, p2 = r() * 6;
        const points = [];
        for (let i = 0; i <= n; i++) {
            const x = (w * i) / n;
            const t = i / n;
            let y = base + amp * (Math.sin(t * f1 * Math.PI + p1) * 0.65 + Math.sin(t * f2 * Math.PI + p2) * 0.35);
            if (jag) y += (r() - 0.5) * amp * 0.9;
            points.push([x, y]);
        }
        return smoothPath(points, w, h);
    }

    const STYLES = {
        hills(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            s += `<circle cx="${w * (0.25 + r() * 0.5)}" cy="${h * (0.2 + r() * 0.15)}" r="${Math.min(w, h) * (0.09 + r() * 0.06)}" fill="${c[4]}"/>`;
            [[0.48, c[1]], [0.6, c[2]], [0.76, c[3]]].forEach(([b, col], i) => {
                s += `<path d="${ridge(r, w, h, h * b, h * (0.06 + i * 0.015), false)}" fill="${col}"/>`;
            });
            for (let i = 0; i < 3; i++) {
                const x = w * (0.2 + r() * 0.6), y = h * (0.28 + r() * 0.12), k = Math.min(w, h) * 0.018;
                s += `<path d="M${x - k} ${y} q${k / 2} ${-k / 2} ${k} 0 q${k / 2} ${-k / 2} ${k} 0" fill="none" stroke="${c[3]}" stroke-width="${k / 5}" stroke-linecap="round"/>`;
            }
            return s;
        },
        night(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            s += `<rect y="${h * 0.42}" width="${w}" height="${h * 0.58}" fill="${c[1]}" opacity=".55"/>`;
            for (let i = 0; i < 70; i++) {
                s += `<circle cx="${(r() * w).toFixed(1)}" cy="${(r() * h * 0.6).toFixed(1)}" r="${(0.6 + r() * 2.2).toFixed(1)}" fill="${c[4]}" opacity="${(0.35 + r() * 0.65).toFixed(2)}"/>`;
            }
            const mx = w * (0.2 + r() * 0.6), my = h * (0.18 + r() * 0.1), mr = Math.min(w, h) * 0.08;
            s += `<circle cx="${mx}" cy="${my}" r="${mr}" fill="${c[4]}"/><circle cx="${mx + mr * 0.42}" cy="${my - mr * 0.18}" r="${mr * 0.86}" fill="${c[0]}"/>`;
            s += `<path d="${ridge(r, w, h, h * 0.62, h * 0.09, true)}" fill="${c[2]}"/>`;
            s += `<path d="${ridge(r, w, h, h * 0.78, h * 0.05, true)}" fill="${c[3]}"/>`;
            s += `<rect x="${mx - mr * 0.12}" y="${h * 0.84}" width="${mr * 0.24}" height="${h * 0.12}" fill="${c[4]}" opacity=".22"/>`;
            return s;
        },
        sea(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            const horizon = h * (0.45 + r() * 0.12);
            s += `<circle cx="${w * (0.3 + r() * 0.4)}" cy="${horizon}" r="${Math.min(w, h) * 0.12}" fill="${c[5]}" opacity=".85"/>`;
            s += `<rect y="${horizon}" width="${w}" height="${h - horizon}" fill="${c[1]}"/>`;
            for (let i = 0; i < 7; i++) {
                const y = horizon + (h - horizon) * (0.1 + i * 0.14);
                const amp = 4 + i * 3, len = w / (5 + r() * 3);
                let d = `M0 ${y}`;
                for (let x = 0; x <= w + len; x += len) d += ` q${len / 4} ${-amp} ${len / 2} 0 t${len / 2} 0`;
                s += `<path d="${d}" fill="none" stroke="${i % 2 ? c[2] : c[4]}" stroke-width="${2 + i * 0.8}" opacity=".8"/>`;
            }
            const bx = w * (0.15 + r() * 0.6), by = horizon + (h - horizon) * 0.25, bs = Math.min(w, h) * 0.05;
            s += `<path d="M${bx} ${by} l${bs * 2} 0 l${-bs * 0.4} ${bs * 0.5} l${-bs * 1.2} 0 z M${bx + bs} ${by} l0 ${-bs * 1.8} l${bs * 0.9} ${bs * 1.6} z" fill="${c[3]}"/>`;
            return s;
        },
        bauhaus(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            const u = Math.min(w, h);
            s += `<rect x="${w * 0.08}" y="${h * (0.5 + r() * 0.15)}" width="${w * 0.84}" height="${h * 0.3}" fill="${c[2]}"/>`;
            s += `<circle cx="${w * (0.3 + r() * 0.4)}" cy="${h * (0.32 + r() * 0.1)}" r="${u * (0.2 + r() * 0.08)}" fill="${c[5]}"/>`;
            const ax = w * (0.2 + r() * 0.6), ay = h * 0.8, ar = u * 0.22;
            s += `<path d="M${ax - ar} ${ay} a${ar} ${ar} 0 0 1 ${ar * 2} 0 z" fill="${c[3]}"/>`;
            s += `<rect x="${w * (0.6 + r() * 0.2)}" y="${h * 0.1}" width="${u * 0.06}" height="${h * 0.45}" fill="${c[3]}"/>`;
            for (let i = 0; i < 5; i++) {
                s += `<circle cx="${w * 0.12 + i * u * 0.06}" cy="${h * 0.12}" r="${u * 0.014}" fill="${c[3]}"/>`;
            }
            s += `<path d="M${w * 0.08} ${h * 0.92} L${w * 0.92} ${h * 0.92}" stroke="${c[3]}" stroke-width="${u * 0.008}"/>`;
            return s;
        },
        bloom(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            const u = Math.min(w, h);
            for (let i = 0; i < 4; i++) {
                const x = w * r(), y = h * (0.55 + r() * 0.45);
                s += `<path d="M${x} ${h} Q${x + (r() - 0.5) * u * 0.3} ${(y + h) / 2} ${x + (r() - 0.5) * u * 0.1} ${y - h * 0.3}" stroke="${c[3]}" stroke-width="${u * 0.008}" fill="none"/>`;
            }
            for (let i = 0; i < 9; i++) {
                const x = w * (0.08 + r() * 0.84), y = h * (0.12 + r() * 0.7), pr = u * (0.04 + r() * 0.07);
                const col = [c[1], c[2], c[5]][i % 3];
                const petals = 5 + Math.floor(r() * 3);
                const rot = r() * 360;
                s += `<g transform="translate(${x.toFixed(1)} ${y.toFixed(1)}) rotate(${rot.toFixed(0)})">`;
                for (let p = 0; p < petals; p++) {
                    s += `<ellipse cx="0" cy="${-pr}" rx="${pr * 0.48}" ry="${pr}" fill="${col}" transform="rotate(${(360 / petals) * p})"/>`;
                }
                s += `<circle r="${pr * 0.36}" fill="${c[4]}"/></g>`;
            }
            return s;
        },
        city(r, w, h, c) {
            let s = `<rect width="${w}" height="${h}" fill="${c[0]}"/>`;
            s += `<circle cx="${w * (0.6 + r() * 0.3)}" cy="${h * 0.25}" r="${Math.min(w, h) * 0.1}" fill="${c[5]}" opacity=".9"/>`;
            let x = -10;
            const layers = [[0.42, c[1]], [0.55, c[2]], [0.68, c[3]]];
            layers.forEach(([top, col], li) => {
                x = -10;
                while (x < w) {
                    const bw = w * (0.06 + r() * 0.1), bh = h * (1 - top) * (0.6 + r() * 0.5);
                    s += `<rect x="${x.toFixed(1)}" y="${(h - bh).toFixed(1)}" width="${bw.toFixed(1)}" height="${bh.toFixed(1)}" fill="${col}"/>`;
                    if (li === 2) {
                        for (let wy = h - bh + 12; wy < h - 12; wy += 18) {
                            for (let wx = x + 8; wx < x + bw - 8; wx += 14) {
                                if (r() > 0.6) s += `<rect x="${wx.toFixed(1)}" y="${wy.toFixed(1)}" width="5" height="7" fill="${c[4]}" opacity=".75"/>`;
                            }
                        }
                    }
                    x += bw + 2;
                }
            });
            return s;
        }
    };

    const artCache = new Map();
    function art(style, paletteName, seed, w, h) {
        const key = `${style}/${paletteName}/${seed}/${w}x${h}`;
        if (!artCache.has(key)) {
            const body = STYLES[style](rng(seed), w, h, PALETTES[paletteName]);
            const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
            artCache.set(key, 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg));
        }
        return artCache.get(key);
    }

    const SIZES = {
        '3:4': [900, 1200], '2:3': [800, 1200], '4:5': [1200, 1500], '1:1': [1000, 1000],
        '16:9': [1600, 900], '4:3': [1200, 900], '9:16': [720, 1280], '3:1': [1500, 500]
    };

    const authors = [
        { id: 1, name: '雨宫ゆき', handle: 'amamiya_yuki' },
        { id: 2, name: 'Lumen Kiln', handle: 'lumenkiln' },
        { id: 3, name: '青柳', handle: 'aoyagi_art' },
        { id: 4, name: 'Mori Studio', handle: null },
        { id: 5, name: '夜航船', handle: 'yehangchuan' },
        { id: 6, name: 'Petra Vale', handle: 'petravale' },
        { id: 7, name: '千秋', handle: 'chiaki_draws' },
        { id: 8, name: 'alice', handle: 'alice_art' }
    ];

    const tags = [
        '风景', '夜空', '海', '花', '城市', '几何', '暖色', '冷色', '系列', '壁纸', '参考', '草稿', 'Blue Sky 天空', '构图'
    ].map((name, i) => ({ id: i + 1, name }));
    const tagId = (name) => tags.find((t) => t.name === name).id;

    // [标题, 风格, 配色, 作者 id, 标签, 页数, 比例, 格式, 备注, 来源]
    const SPEC = [
        ['晨雾里的山脊', 'hills', 'mist', 3, ['风景', '冷色', '壁纸'], 1, '16:9', 'png', '远景三层的明度控制很干净，适合做桌面壁纸。', 'https://x.com/aoyagi_art/status/1849021133'],
        ['海边的第七个夏天', 'sea', 'sea', 1, ['海', '暖色', '系列'], 4, '3:4', 'jpg', '四页连作，第三页的小船构图最好。', 'https://x.com/amamiya_yuki/status/1848812201'],
        ['Moon over Kiln', 'night', 'night', 2, ['夜空', '冷色'], 1, '2:3', 'png', '', 'https://x.com/lumenkiln/status/1848700412'],
        ['IMG_20261021_2241', 'bloom', 'lilac', null, [], 1, '4:5', 'jpg', '', ''],
        ['旧城区黄昏', 'city', 'dusk', 5, ['城市', '暖色'], 1, '16:9', 'png', '窗户的点光源分布可以参考。', 'https://x.com/yehangchuan/status/1848511980'],
        ['Composition No. 12', 'bauhaus', 'ember', 6, ['几何', '构图', '参考'], 1, '3:4', 'png', 'Bauhaus 风格的海报练习，留白比例 1:3。', 'https://x.com/petravale/status/1848430011'],
        ['山茶', 'bloom', 'ember', 7, ['花', '暖色'], 2, '4:5', 'jpg', '', 'https://x.com/chiaki_draws/status/1848329950'],
        ['Blue hour study', 'hills', 'sky', 8, ['Blue Sky 天空', '冷色', '草稿'], 1, '4:3', 'png', '色彩练习，天空颜色可以再饱和一点。', 'https://x.com/alice_art/status/1848220147'],
        ['星图', 'night', 'dusk', 5, ['夜空', '系列'], 3, '9:16', 'png', '竖版三页，手机壁纸尺寸。', 'https://x.com/yehangchuan/status/1848110093'],
        ['无题（苔）', 'hills', 'moss', 4, ['风景'], 1, '1:1', 'jpg', '', ''],
        ['Harbor Lights', 'city', 'night', 2, ['城市', '夜空', '冷色'], 1, '3:1', 'png', '超宽幅，适合横幅。', 'https://x.com/lumenkiln/status/1847930021'],
        ['紫阳花之庭', 'bloom', 'lilac', 1, ['花', '冷色'], 1, '3:4', 'png', '', 'https://x.com/amamiya_yuki/status/1847811288'],
        ['Form & Void', 'bauhaus', 'ink', 6, ['几何', '构图'], 1, '1:1', 'png', '', 'https://x.com/petravale/status/1847701932'],
        ['退潮', 'sea', 'dawn', 3, ['海', '暖色', '壁纸'], 1, '16:9', 'jpg', '暖色海面，光源低。', 'https://x.com/aoyagi_art/status/1847600017'],
        ['download (3)', 'city', 'ember', null, ['城市'], 1, '4:3', 'jpg', '', ''],
        ['第一场雪之前', 'hills', 'ink', 7, ['风景', '冷色'], 2, '2:3', 'png', '', 'https://x.com/chiaki_draws/status/1847410320'],
        ['Night Swim', 'sea', 'night', 8, ['海', '夜空'], 1, '4:5', 'png', '', 'https://x.com/alice_art/status/1847300881'],
        ['花信', 'bloom', 'dawn', 7, ['花', '暖色', '系列'], 4, '3:4', 'jpg', '花信风连作，四季各一页。', 'https://x.com/chiaki_draws/status/1847200556'],
        ['塔与圆', 'bauhaus', 'sky', 4, ['几何', 'Blue Sky 天空'], 1, '2:3', 'png', '', ''],
        ['Dusk Avenue', 'city', 'dawn', 2, ['城市', '暖色', '壁纸'], 1, '16:9', 'png', '', 'https://x.com/lumenkiln/status/1846911223'],
        ['夜航', 'night', 'sea', 5, ['夜空', '海'], 1, '9:16', 'jpg', '', 'https://x.com/yehangchuan/status/1846800147'],
        ['构图速写 #4', 'bauhaus', 'moss', 6, ['几何', '草稿', '参考'], 1, '4:3', 'png', '三分法速写。', 'https://x.com/petravale/status/1846700005'],
        ['IMG_7781', 'sea', 'mist', null, [], 1, '1:1', 'jpg', '', ''],
        ['远山如黛', 'hills', 'lilac', 3, ['风景', '冷色'], 1, '3:1', 'png', '', 'https://x.com/aoyagi_art/status/1846400291'],
        ['Field Notes', 'bloom', 'moss', 8, ['花', '参考'], 3, '4:5', 'png', '野花写生集，三页。', 'https://x.com/alice_art/status/1846300188'],
        ['不夜城', 'city', 'lilac', 5, ['城市', '夜空'], 1, '2:3', 'png', '', 'https://x.com/yehangchuan/status/1846200044'],
        ['日出前', 'hills', 'dawn', 1, ['风景', '暖色', '壁纸'], 1, '16:9', 'jpg', '', 'https://x.com/amamiya_yuki/status/1846111872'],
        ['Orbit', 'bauhaus', 'dusk', 6, ['几何'], 1, '1:1', 'gif', 'GIF 原图，无缩略图。', 'https://x.com/petravale/status/1846009315'],
        ['月下海', 'sea', 'lilac', 1, ['海', '夜空', '冷色'], 2, '3:4', 'png', '', 'https://x.com/amamiya_yuki/status/1845900712'],
        ['苔庭', 'bloom', 'mist', 4, ['花', '风景'], 1, '4:3', 'jpg', '', ''],
        ['Rooftops', 'city', 'sky', 2, ['城市', 'Blue Sky 天空'], 1, '4:5', 'png', '', 'https://x.com/lumenkiln/status/1845700408'],
        ['寒星', 'night', 'ink', 7, ['夜空', '冷色'], 1, '2:3', 'png', '', 'https://x.com/chiaki_draws/status/1845600221'],
        ['Screenshot_2026-09-30', 'hills', 'ember', null, ['参考'], 1, '9:16', 'png', '', ''],
        ['暖流', 'sea', 'ember', 3, ['海', '暖色'], 1, '4:3', 'jpg', '', 'https://x.com/aoyagi_art/status/1845300066'],
        ['Still Life with Arc', 'bauhaus', 'lilac', 8, ['几何', '构图'], 1, '4:5', 'png', '', 'https://x.com/alice_art/status/1845200540'],
        ['灯火阑珊', 'city', 'dusk', 5, ['城市', '暖色', '系列'], 2, '3:4', 'png', '', 'https://x.com/yehangchuan/status/1845100312'],
        ['草原', 'hills', 'moss', 4, ['风景', '暖色'], 1, '16:9', 'jpg', '', ''],
        ['Starfall', 'night', 'lilac', 2, ['夜空'], 1, '1:1', 'png', '', 'https://x.com/lumenkiln/status/1844900078'],
        ['小苍兰', 'bloom', 'sky', 1, ['花', 'Blue Sky 天空'], 1, '2:3', 'jpg', '', 'https://x.com/amamiya_yuki/status/1844800190'],
        ['Tidal Grid', 'bauhaus', 'sea', 6, ['几何', '海'], 1, '4:3', 'png', '', 'https://x.com/petravale/status/1844700411']
    ];

    // 按批次分配导入时间：同一天常常导入一批，和真实使用相近
    const DAY = 86400000;
    const NOW = Date.parse('2026-10-27T10:30:00+08:00');
    const batchGaps = [0, 0, 0, 1, 1, 3, 3, 3, 3, 5, 6, 6, 9, 9, 9, 12, 14, 14, 17, 21, 21, 21, 24, 26, 30, 30, 33, 38, 38, 41, 45, 45, 49, 52, 52, 58, 63, 63, 70, 77];

    function makeWork(spec, i) {
        const [title, style, palette, authorId, tagNames, pageCount, ratio, format, note, source] = spec;
        const [w, h] = SIZES[ratio];
        const id = 1000 + SPEC.length - i;
        const pages = [];
        for (let p = 0; p < pageCount; p++) {
            const seed = id * 31 + p * 7;
            const bytesPerPixel = format === 'jpg' ? 0.32 : format === 'gif' ? 0.9 : 1.15;
            pages.push({
                width: w, height: h, format,
                size: Math.round(w * h * bytesPerPixel * (0.8 + rng(seed)() * 0.5)),
                src: art(style, palette, seed, w, h),
                tone: PALETTES[palette][3]
            });
        }
        return {
            id,
            title,
            authorId,
            tagIds: tagNames.map(tagId),
            note,
            source,
            importedAt: NOW - batchGaps[i] * DAY - i * 3600000 * 0.7,
            pages
        };
    }

    const works = SPEC.map(makeWork);
    const listeners = new Set();
    const emit = (detail) => listeners.forEach((fn) => fn(detail));

    const RECENT_DAYS = 7;

    function authorOf(work) {
        return authors.find((a) => a.id === work.authorId) || null;
    }

    function tagsOf(work) {
        return work.tagIds.map((id) => tags.find((t) => t.id === id)).filter(Boolean);
    }

    function isUntidy(work) {
        return work.authorId == null || work.tagIds.length === 0;
    }

    function matchesText(work, text) {
        if (!text) return true;
        const needle = text.trim().toLowerCase();
        const handleNeedle = needle.startsWith('@') ? needle.slice(1) : null;
        const author = authorOf(work);
        if (handleNeedle != null) return !!(author && author.handle && author.handle.toLowerCase().includes(handleNeedle));
        const haystack = [
            work.title, work.note,
            author && author.name, author && author.handle,
            ...tagsOf(work).map((t) => t.name)
        ].filter(Boolean).join('\n').toLowerCase();
        return haystack.includes(needle);
    }

    /*
     * 与现有后端语义一致：关键词匹配标题 / 备注 / 作者名 / handle / 标签；
     * 作者、标签各自多选为 OR，各类之间为 AND。
     * scope（最近导入 / 待整理 / 多页）和排序是原型新增的浏览方式，需后端补充查询参数。
     */
    function query({ text = '', authorIds = [], tagIds = [], scope = 'all', sort = 'newest' } = {}) {
        const aSet = new Set(authorIds), tSet = new Set(tagIds);
        const result = works.filter((w) => {
            if (scope === 'recent' && NOW - w.importedAt > RECENT_DAYS * DAY) return false;
            if (scope === 'untidy' && !isUntidy(w)) return false;
            if (scope === 'multipage' && w.pages.length < 2) return false;
            if (aSet.size && !aSet.has(w.authorId)) return false;
            if (tSet.size && !w.tagIds.some((id) => tSet.has(id))) return false;
            return matchesText(w, text);
        });
        const byTitle = (a, b) => (a.title || '').localeCompare(b.title || '', 'zh-CN');
        result.sort(sort === 'oldest' ? (a, b) => a.importedAt - b.importedAt
            : sort === 'title' ? byTitle
                : (a, b) => b.importedAt - a.importedAt);
        return result;
    }

    function counts() {
        const byAuthor = new Map(), byTag = new Map();
        let recent = 0, untidy = 0, multipage = 0;
        works.forEach((w) => {
            if (w.authorId != null) byAuthor.set(w.authorId, (byAuthor.get(w.authorId) || 0) + 1);
            w.tagIds.forEach((id) => byTag.set(id, (byTag.get(id) || 0) + 1));
            if (NOW - w.importedAt <= RECENT_DAYS * DAY) recent++;
            if (isUntidy(w)) untidy++;
            if (w.pages.length > 1) multipage++;
        });
        return { all: works.length, recent, untidy, multipage, byAuthor, byTag };
    }

    function get(id) {
        return works.find((w) => w.id === id) || null;
    }

    function update(id, patch) {
        const work = get(id);
        if (!work) throw new Error('作品不存在');
        ['title', 'note', 'source', 'authorId', 'tagIds'].forEach((key) => {
            if (key in patch) work[key] = key === 'tagIds' ? [...new Set(patch[key])] : patch[key];
        });
        emit({ type: 'update', ids: [id] });
        return work;
    }

    function updateMany(ids, { addTagIds = [], removeTagIds = [], authorId } = {}) {
        ids.forEach((id) => {
            const work = get(id);
            if (!work) return;
            const set = new Set(work.tagIds);
            addTagIds.forEach((t) => set.add(t));
            removeTagIds.forEach((t) => set.delete(t));
            work.tagIds = [...set];
            if (authorId !== undefined) work.authorId = authorId;
        });
        emit({ type: 'update', ids });
    }

    function remove(ids) {
        const set = new Set([].concat(ids));
        for (let i = works.length - 1; i >= 0; i--) {
            if (set.has(works[i].id)) works.splice(i, 1);
        }
        emit({ type: 'remove', ids: [...set] });
    }

    function createTag(name) {
        const clean = name.trim();
        if (!clean) throw new Error('标签名称不能为空');
        const existing = tags.find((t) => t.name.toLowerCase() === clean.toLowerCase());
        if (existing) {
            const err = new Error('已存在同名标签（不区分大小写）');
            err.existing = existing;
            throw err;
        }
        const tag = { id: Math.max(...tags.map((t) => t.id)) + 1, name: clean };
        tags.push(tag);
        emit({ type: 'tags' });
        return tag;
    }

    function createAuthor(name, handle) {
        const clean = name.trim();
        if (!clean) throw new Error('作者名称不能为空');
        const author = { id: Math.max(...authors.map((a) => a.id)) + 1, name: clean, handle: (handle || '').replace(/^@/, '').trim() || null };
        authors.push(author);
        emit({ type: 'authors' });
        return author;
    }

    // 导入模拟：读取真实选择的图片做预览，内容指纹（名称 + 大小）重复时按 DUPLICATE_IMAGE 处理
    const fingerprints = new Set();
    let nextId = 2000;
    const ACCEPTED = /\.(jpe?g|png|gif)$/i;

    function readImage(file) {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => resolve({ url, width: img.naturalWidth, height: img.naturalHeight });
            img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('无法解码图片')); };
            img.src = url;
        });
    }

    async function importFiles(fileList, onItem) {
        const files = [...fileList];
        const results = [];
        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const base = { name: file.name, size: file.size };
            await new Promise((r) => setTimeout(r, 260 + Math.random() * 380));
            let result;
            if (!ACCEPTED.test(file.name)) {
                result = { ...base, status: 'failed', message: '只支持 JPEG / PNG / GIF' };
            } else if (fingerprints.has(file.name + ':' + file.size)) {
                result = { ...base, status: 'duplicate', message: '图库中已有相同图片' };
            } else {
                try {
                    const img = await readImage(file);
                    fingerprints.add(file.name + ':' + file.size);
                    const work = {
                        id: nextId++,
                        title: file.name.replace(/\.[^.]+$/, ''),
                        authorId: null, tagIds: [], note: '', source: '',
                        importedAt: NOW + (Date.now() % DAY),
                        pages: [{
                            width: img.width, height: img.height,
                            format: (file.name.match(/\.(\w+)$/) || [, 'img'])[1].toLowerCase().replace('jpeg', 'jpg'),
                            size: file.size, src: img.url, tone: '#444'
                        }],
                        fresh: true
                    };
                    works.unshift(work);
                    result = { ...base, status: 'success', work };
                } catch (e) {
                    result = { ...base, status: 'failed', message: e.message };
                }
            }
            results.push(result);
            if (onItem) onItem(result, i, files.length);
        }
        emit({ type: 'import', ids: results.filter((r) => r.work).map((r) => r.work.id) });
        return results;
    }

    function formatSize(bytes) {
        if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
        return Math.max(1, Math.round(bytes / 1024)) + ' KB';
    }

    function formatDate(ms, withTime) {
        const d = new Date(ms);
        const pad = (n) => String(n).padStart(2, '0');
        const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
        return withTime ? `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}` : date;
    }

    function relativeDay(ms) {
        const days = Math.floor((NOW - ms) / DAY);
        if (days <= 0) return '今天';
        if (days === 1) return '昨天';
        if (days < 7) return days + ' 天前';
        return formatDate(ms);
    }

    window.Archive = {
        works, authors, tags,
        query, counts, get, update, updateMany, remove, createTag, createAuthor, importFiles,
        authorOf, tagsOf, isUntidy,
        displayTitle: (w) => (w.title && w.title.trim()) || '未命名作品',
        formatSize, formatDate, relativeDay,
        subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
    };
})();
