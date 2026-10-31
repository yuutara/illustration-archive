/*
 * 多图牌堆的几何：每张牌的样子只取决于一个连续的位置 t，不依赖拖动方向或任何过程状态。
 *
 * t 是“看到第几页”，可以是小数：t = 2 是第 3 页正对着你，t = 2.3 是正在往第 4 页翻。
 * 一张牌的姿态只由它和 t 差几页（r）决定，所以同一个 t 永远是同一幅画面：
 * 往回拖就是把这段动画倒着放，方向变化时没有什么需要切换，也就没有东西会跳。
 *
 *   r = 0        顶牌，正对着你
 *   r = ±1       下一页静止时从右边露出一点，上一页从左边露出一点。两边各放一张，
 *                所以换方向不用换底牌：向左拖揭开右边那张，向右拖揭开左边那张
 *   |r| = 0.5    两张牌分到最开。它们这时已经不重叠，前后关系就在这一刻交换，看不到穿插
 *   |r| > 1      其余的页收在中间最底下，循环时从这里转到另一侧
 *
 * 两张图时上一页和下一页是同一张，只有一个露出位置，向两边翻都从这里出发。
 */
(function (root) {
    'use strict';

    // 与 fx.css 悬停时的 [data-depth] 位置一致：拖动时指针在卡上，落定后不会抖一下
    const NEXT = { x: 3, y: -3, rot: 1.8 };
    const PREV = { x: -3, y: -3, rot: -1.8 };
    const BACK = { x: 0, y: -1, rot: 0 };
    const REACH = 0.58;      // 分到最开时，一张牌走出多少卡宽：要大于一半，两张牌交换前后时才不重叠
    const FOLLOW = 0.8;      // 走到这个比例之前 1:1 跟手，之后柔和减速到顶点
    const PEAK = FOLLOW + (1 - FOLLOW) / 2;
    const TILT = 12;         // 走出一个卡宽时转多少度
    const RISE = 8;          // 走到顶点时抬起多少像素

    const mod = (a, n) => ((a % n) + n) % n;
    const lerp = (a, b, k) => a + (b - a) * k;
    const smooth = (k) => k * k * (3 - 2 * k);
    const clamp = (k) => Math.min(1, Math.max(0, k));

    // 这一页和 t 差多少，沿牌堆走最近的方向：结果落在 -n/2 到 n/2
    function offset(page, t, n) {
        return mod(page - t + n / 2, n) - n / 2;
    }

    const pageAt = (t, n) => mod(Math.round(t), n);

    const slotOf = (r, n) => (n === 2 || r > 0 ? NEXT : PREV);

    // 走到顶点前是直线，所以顶牌和手完全同步；最后一段把速度减到 0，和收回去的那一段接上
    function outward(u) {
        if (u <= FOLLOW) return u;
        const past = u - FOLLOW;
        return FOLLOW + past - past * past / (2 * (1 - FOLLOW));
    }

    function poseOf(r, n, width) {
        const a = Math.abs(r), side = r < 0 ? -1 : 1;
        const slot = slotOf(r, n);
        const reach = REACH * width;
        const out = outward(clamp(a / 0.5));
        const top = { x: side * reach * out, y: -RISE * out };
        top.rot = top.x / width * TILT;
        let pose;
        if (a <= 0.5) {
            pose = { ...top, depth: 0, lift: out };
        } else if (a <= 1) {
            const back = smooth((a - 0.5) / 0.5);
            pose = {
                x: lerp(top.x, slot.x, back), y: lerp(top.y, slot.y, back), rot: lerp(top.rot, slot.rot, back),
                depth: back, lift: out * (1 - back)
            };
        } else {
            const away = n > 2 ? smooth(clamp((a - 1) / Math.max(n / 2 - 1, 0.5))) : 0;
            pose = {
                x: lerp(slot.x, BACK.x, away), y: lerp(slot.y, BACK.y, away), rot: lerp(slot.rot, BACK.rot, away),
                depth: 1 + away, lift: 0
            };
        }
        pose.scale = a < 1 ? 1 + 0.03 * Math.sin(2 * Math.PI * a) : 1;
        // 离得越近越靠前；一样近时右边的在上，顺序不会因为小数误差来回变
        pose.z = Math.round((n - a) * 100000) * 2 + (r > 0 ? 1 : 0);
        return pose;
    }

    // 静止时身后露出哪一张：1 是右边的下一页（两张图时就是另一张），2 是左边的上一页
    function restingDepth(r, n) {
        if (Math.abs(r) !== 1) return 0;
        return n === 2 || r > 0 ? 1 : 2;
    }

    // 一页对应手要走多少像素：顶牌走到顶点、下一张接上，手走过的正好是一页
    const pixelsPerPage = (width) => 2 * REACH * width;

    /*
     * 松手停在哪一页。按下到松手之间怎么来回都不算数，只看此刻的位置和速度：
     *   甩动：顺着速度方向停到下一个整页，反向甩就是撤销
     *   连着甩：上一张还在路上时，这一甩从它的终点算起，一下一页
     *   否则：离开 aim 过了 commit 才算翻页，没过线就回到 aim
     * aim 是按下时要去的页：静止时是当前页，动画中抓住时是动画的终点。
     */
    function restingPage({ t, t0, aim, velocity, commit, flick, flickDistance }) {
        if (Math.abs(velocity) > flick && Math.abs(t - t0) > flickDistance) {
            if (aim !== t0) return velocity > 0 ? Math.max(aim + 1, Math.round(t)) : Math.min(aim - 1, Math.round(t));
            return velocity > 0 ? Math.ceil(t) : Math.floor(t);
        }
        if (Math.abs(t - t0) <= flickDistance || Math.abs(t - aim) <= commit) return aim;
        return t > aim ? Math.ceil(t - commit) : Math.floor(t + commit);
    }

    const model = { offset, pageAt, poseOf, restingDepth, pixelsPerPage, restingPage, mod };
    if (typeof module !== 'undefined') module.exports = model;
    root.DeckModel = model;
})(typeof window !== 'undefined' ? window : globalThis);
