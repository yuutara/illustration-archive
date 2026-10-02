const assert = require("node:assert/strict");
const test = require("node:test");
const {layout} = require("../../main/resources/static/masonry-layout");

test("desktop and narrow breakpoints keep readable columns and finite reserved height", () => {
    for (const [width, viewport, expected] of [[1233,1280,6],[1873,1920,8],[359,390,2],[289,320,1]]) {
        const result = layout(width,[.5,2,.1,1],viewport);
        assert.equal(result.columns, expected);
        assert.ok(result.height > 0);
        for (const box of result.boxes) assert.ok(box.left + box.width <= width + .001);
    }
    assert.equal(layout(1200,[],1280).height, 0);
});

test("portrait, landscape and long images preserve aspect without overlap; ties choose left", () => {
    const ratios = [.5,2,.1,1,.6,1.5,1,1,.7,2];
    const {boxes, height} = layout(500,ratios,390);
    assert.equal(boxes[0].left, 0);
    assert.ok(boxes[1].left > 0);
    assert.equal(boxes[2].left, boxes[1].left);
    boxes.forEach((box,i) => {
        assert.ok(Math.abs(box.width / box.height - ratios[i]) < 1e-10);
        assert.ok(box.top + box.height <= height + .001);
        boxes.slice(i+1).filter(other=>other.left===box.left).forEach(other=>assert.ok(other.top >= box.top + box.height + 6 - .001));
    });
});

test("invalid ratios reserve fallback positions and very wide viewports cap at eight columns", () => {
    const result = layout(1888,[NaN,0,-1,.05],2560);
    assert.equal(result.columns,8);
    for (const box of result.boxes) assert.ok(Number.isFinite(box.height) && box.height > 0);
    assert.equal(result.boxes[0].height,result.boxes[0].width);
    assert.ok(result.boxes[3].height > result.boxes[0].height * 10);
});
