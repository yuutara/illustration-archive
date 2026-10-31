const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const Deck = require("../../main/resources/static/c/c-deck-model.js");

const read = (name) => readFileSync(path.resolve(__dirname, "../../main/resources/static/c", name), "utf8");
const WIDTH = 240;
const pagesOf = (n) => Array.from({ length: n }, (_, page) => page);
const posesAt = (t, n) => pagesOf(n).map((page) => ({ page, ...Deck.poseOf(Deck.offset(page, t, n), n, WIDTH) }));
const frontOf = (poses) => poses.reduce((best, pose) => (pose.z > best.z ? pose : best));

test("the same position always draws the same picture, whichever way the hand got there", () => {
    for (const n of [2, 3, 4, 7]) {
        for (const t of [0.2, 0.5, 0.75, 1.3, 2.9]) {
            const direct = posesAt(t, n);
            const afterLaps = posesAt(t + n * 3, n);
            const fromBelow = posesAt(t - n, n);
            for (const page of pagesOf(n)) {
                for (const key of ["x", "y", "rot", "scale", "z"]) {
                    assert.ok(Math.abs(direct[page][key] - afterLaps[page][key]) < 1e-6, `n=${n} t=${t} ${key}`);
                    assert.ok(Math.abs(direct[page][key] - fromBelow[page][key]) < 1e-6, `n=${n} t=${t} ${key}`);
                }
            }
        }
    }
});

test("no card ever jumps: a tiny step of the hand moves every card a tiny amount, across the start and every wrap", () => {
    const step = 0.002;
    for (const n of [2, 3, 4, 5, 8]) {
        let previous = posesAt(-3, n);
        for (let t = -3 + step; t < 3; t += step) {
            const poses = posesAt(t, n);
            for (const page of pagesOf(n)) {
                const jump = Math.hypot(poses[page].x - previous[page].x, poses[page].y - previous[page].y);
                const turn = Math.abs(poses[page].rot - previous[page].rot);
                assert.ok(jump < 2, `n=${n} t=${t.toFixed(3)} page ${page} moved ${jump.toFixed(2)}px`);
                assert.ok(turn < 0.5, `n=${n} t=${t.toFixed(3)} page ${page} turned ${turn.toFixed(2)}deg`);
            }
            previous = poses;
        }
    }
});

test("the card under the hand moves 1:1 with it, on both sides of the start", () => {
    const span = Deck.pixelsPerPage(WIDTH);
    for (const dx of [-110, -60, -8, 8, 60, 110]) {
        const pose = posesAt(-dx / span, 3)[0];
        assert.ok(Math.abs(pose.x - dx) < 0.01, `hand ${dx}, card ${pose.x}`);
    }
});

test("cards under the top one stay put when the direction changes", () => {
    const span = Deck.pixelsPerPage(WIDTH);
    for (const n of [3, 4, 6]) {
        const left = posesAt(2 / span, n), right = posesAt(-2 / span, n);
        const next = 1, prev = n - 1;
        assert.ok(Math.abs(left[next].x - right[next].x) < 2, "the next page is at the same place on both sides of the start");
        assert.ok(Math.abs(left[prev].x - right[prev].x) < 2, "and so is the previous one");
        assert.ok(left[next].x > 0 && left[prev].x < 0, "next peeks out on the right, previous on the left");
        assert.equal(frontOf(left).page, 0);
        assert.equal(frontOf(right).page, 0);
    }
});

test("dragging left uncovers the card on the right, dragging right the one on the left", () => {
    const leftward = posesAt(0.3, 4), rightward = posesAt(-0.3, 4);
    assert.ok(leftward[0].x < 0 && leftward[1].x > 0);
    assert.ok(rightward[0].x > 0 && rightward[3].x < 0);
});

test("the two cards trade places only once they no longer overlap", () => {
    for (const n of [2, 3, 5]) {
        const [a, b] = [posesAt(0.499, n), posesAt(0.501, n)];
        assert.ok(a[1].x - a[0].x >= WIDTH, "fully apart just before the swap");
        assert.ok(b[1].x - b[0].x >= WIDTH, "fully apart just after it");
        assert.equal(frontOf(a).page, 0);
        assert.equal(frontOf(b).page, 1);
    }
});

test("the badge and the card in front are always the same page", () => {
    for (const n of [2, 3, 4, 7]) {
        for (let t = -4.03; t < 4; t += 0.07) {
            // 正好在交换点上时两张牌完全分开，谁算在前没有区别
            if (Math.abs(Deck.mod(t, 1) - 0.5) < 0.002) continue;
            assert.equal(frontOf(posesAt(t, n)).page, Deck.pageAt(t, n), `n=${n} t=${t.toFixed(2)}`);
        }
    }
});

test("stacking order has no ties, so a card never flickers between layers", () => {
    for (const n of [2, 3, 4, 7]) {
        for (let t = 0.013; t < n; t += 0.031) {
            const order = posesAt(t, n).map((pose) => pose.z);
            assert.equal(new Set(order).size, order.length, `n=${n} t=${t.toFixed(3)}: ${order}`);
        }
    }
});

test("at rest the pose matches the page's hover position, so settling does not nudge the cards", () => {
    const near = (pose, x, y, rot) => {
        assert.ok(Math.abs(pose.x - x) < 1e-9 && Math.abs(pose.y - y) < 1e-9 && Math.abs(pose.rot - rot) < 1e-9, JSON.stringify(pose));
    };
    const three = posesAt(1, 3);
    near(three[1], 0, 0, 0);
    near(three[2], 3, -3, 1.8);
    near(three[0], -3, -3, -1.8);
    near(posesAt(0, 2)[1], 3, -3, 1.8);
    near(posesAt(1, 2)[0], 3, -3, 1.8);
    const hover = read("fx.css");
    assert.match(hover, /\[data-depth="1"\] \{ transform: translate\(3px, -3px\) rotate\(1\.8deg\); \}/);
    assert.match(hover, /\[data-depth="2"\] \{ transform: translate\(-3px, -3px\) rotate\(-1\.8deg\); \}/);
});

test("while dragging no CSS transition smooths the cards, so they stay under the hand", () => {
    const css = read("fx.css");
    const hover = css.match(/html:not\(\.fx-reduce\)\.fx-stack \.fx-layers img \{ transition: transform/);
    const dragging = css.match(/html:not\(\.fx-reduce\)\.fx-stack \.piece\.fx-decking \.fx-layers img \{[^}]*transition: none;/);
    assert.ok(hover, "the hover transition this has to beat");
    assert.ok(dragging, "same root selector plus two more classes, so it always wins");
});

test("resting cards show which neighbor they are; the rest stay hidden", () => {
    const depths = (n, cur) => pagesOf(n).map((page) => Deck.restingDepth(Deck.offset(page, cur, n), n));
    assert.deepEqual(depths(2, 0), [0, 1]);
    assert.deepEqual(depths(2, 1), [1, 0]);
    assert.deepEqual(depths(3, 0), [0, 1, 2]);
    assert.deepEqual(depths(5, 2), [0, 2, 0, 1, 0]);
});

const release = (over) => Deck.restingPage({ t: 0, t0: 0, aim: 0, velocity: 0, commit: 0.25, flick: 0.003, flickDistance: 0.03, ...over });

test("letting go decides from position alone when the hand has stopped", () => {
    assert.equal(release({ t: 0.2 }), 0, "short of the line: back");
    assert.equal(release({ t: 0.3 }), 1, "past it: forward");
    assert.equal(release({ t: -0.2 }), 0);
    assert.equal(release({ t: -0.3 }), -1);
});

test("changing your mind before letting go counts for nothing but where the card is now", () => {
    assert.equal(release({ t: 0.05 }), 0, "out and back to the start");
    assert.equal(release({ t: -0.4 }), -1, "left first, then far right");
});

test("a flick follows its speed, including one that undoes the drag", () => {
    assert.equal(release({ t: 0.1, velocity: 0.01 }), 1, "quick and short still turns the page");
    assert.equal(release({ t: -0.1, velocity: -0.01 }), -1);
    assert.equal(release({ t: 0.4, t0: 0, velocity: -0.01 }), 0, "flicked back: stays on this page");
    assert.equal(release({ t: 0.01, velocity: 0.01 }), 0, "a twitch is not a flick");
});

test("a long drag can turn several pages and stops on the nearest one past the line", () => {
    assert.equal(release({ t: 1.1 }), 1);
    assert.equal(release({ t: 1.4 }), 2);
    assert.equal(release({ t: 2.7 }), 3);
    assert.equal(release({ t: -1.4 }), -2);
});

test("flicks in a row count one page each, even when the last card has not landed yet", () => {
    assert.equal(release({ t: 0.46, t0: 0.2, aim: 1, velocity: 0.01 }), 2, "flicked on while heading to page 1");
    assert.equal(release({ t: 1.2, t0: 0.95, aim: 1, velocity: 0.01 }), 2, "just before landing");
    assert.equal(release({ t: -0.46, t0: -0.2, aim: -1, velocity: -0.01 }), -2);
    assert.equal(release({ t: -0.06, t0: 0.2, aim: 1, velocity: -0.01 }) + 0, 0, "a flick back cancels the one before it");
});

test("grabbing a moving card and letting go carries on to where it was heading", () => {
    assert.equal(release({ t: 0.7, t0: 0.7, aim: 1 }), 1);
    assert.equal(release({ t: 0.2, t0: 0.2, aim: 0 }), 0);
    assert.equal(release({ t: 0.72, t0: 0.7, aim: 1 }), 1, "the hand barely moved");
    assert.equal(release({ t: 0.5, t0: 0.7, aim: 1 }), 0, "dragged back past the line");
});
