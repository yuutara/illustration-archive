const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/* X 收藏的文字与逐件归档流程：不依赖页面。 */
const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-x-model.js"), "utf8");

function model() {
    const context = { window: {} };
    vm.createContext(context);
    vm.runInContext(source, context);
    return context.window.XInboxModel;
}

const phase = (overrides = {}) => ({ newCount: 0, existingCount: 0, unsupportedCount: 0, scannedCount: 0, stopReason: "NATURAL_END", upstreamStatus: null, ...overrides });
const report = (recent, history = phase(), extra = {}) => ({ recent, history, partial: false, recentStatus: "CAUGHT_UP", historyStatus: "COMPLETE", ...extra });

test("a clean sync says what was found and what it cost, from the server's own counts", () => {
    const summary = model().syncSummary(report(phase({ newCount: 12, existingCount: 3, unsupportedCount: 2, scannedCount: 17 })));
    assert.equal(summary.headline, "新发现 12 件收藏");
    assert.equal(summary.detail, "3 件已经见过，2 件没有可归档的图片，读取 17 条，约 US$0.02。");
    assert.equal(summary.problem, null);
});

test("unfinished ranges invite another sync instead of claiming everything arrived", () => {
    const summary = model().syncSummary(report(phase({ newCount: 1, scannedCount: 25 }), phase(), { historyStatus: "MORE" }));
    assert.match(summary.detail, /更早的收藏还没取完/);
});

test("upstream failures become an action the user can take", () => {
    const M = model();
    const failed = (status) => M.syncSummary(report(phase({ stopReason: "API_FAILURE", upstreamStatus: status }), phase({ stopReason: "SKIPPED_AFTER_FAILURE" }), { partial: true })).problem;
    assert.match(failed(401), /重新连接/);
    assert.match(failed(402), /额度不足/);
    assert.match(failed(403), /权限/);
    assert.match(failed(429), /过一会儿/);
    assert.match(failed(503), /暂时不可用/);
    assert.match(failed(null), /网络或设置中的代理/);
});

test("an invalid cursor is named instead of being reported as a network problem", () => {
    const summary = model().syncSummary(report(phase({ stopReason: "INVALID_TOKEN" }), phase(), { partial: true }));
    assert.match(summary.problem, /进度已失效/);
});

test("archiving goes one post at a time and keeps going past a post that fails", async () => {
    const M = model();
    const sent = [];
    const progress = [];
    const replies = {
        1: { status: "SUCCESS" },
        2: { status: "DUPLICATE" },
        3: { status: "FAILED", reason: "X photo CDN request failed with HTTP 404." },
        4: { status: "SUCCESS" }
    };
    const outcome = await M.archiveAll([1, 2, 3, 4], async (id) => { sent.push(id); return { items: [replies[id]] }; }, (done, total) => progress.push(`${done}/${total}`));
    assert.deepEqual(sent, [1, 2, 3, 4]);
    assert.deepEqual(progress, ["0/4", "1/4", "2/4", "3/4"]);
    assert.deepEqual([...outcome.archived], [1, 4]);
    assert.equal(outcome.problems.get(2), "图库里已有这张图，可以跳过");
    assert.equal(outcome.problems.get(3), "X 的图片服务器找不到这张图，可以到 X 上确认");
    assert.equal(M.archiveSummary(outcome), "已归档 2 件，2 件没有归档");
});

test("a CDN refusal never claims the work was deleted", async () => {
    const M = model();
    const notFound = M.archiveProblem({ status: "FAILED", reason: "X photo CDN request failed with HTTP 404." });
    const refused = M.archiveProblem({ status: "FAILED", reason: "X photo CDN request failed with HTTP 403." });
    assert.doesNotMatch(notFound + refused, /删除/);
    assert.match(refused, /拒绝访问/);
});

test("photo sizes use X's documented form, and any other address stays as it is", () => {
    const M = model();
    assert.equal(M.photoVariant("https://pbs.twimg.com/media/AbC_d-1.jpg", "small"), "https://pbs.twimg.com/media/AbC_d-1?format=jpg&name=small");
    assert.equal(M.photoVariant("https://pbs.twimg.com/media/AbC.JPEG", "large"), "https://pbs.twimg.com/media/AbC?format=jpg&name=large");
    assert.equal(M.photoVariant("https://pbs.twimg.com/media/AbC.png", "small"), "https://pbs.twimg.com/media/AbC?format=png&name=small");
    for (const other of ["/api/assets/7/content", "https://pbs.twimg.com/media/AbC?format=jpg&name=small", "https://example.com/media/AbC.jpg"]) {
        assert.equal(M.photoVariant(other, "small"), other);
    }
});

test("both inbox pages hide the local Referer, which X's CDN answers with 351 Forbidden", () => {
    for (const page of ["c/x.html", "x-import.html"]) {
        const html = readFileSync(path.resolve(__dirname, "../../main/resources/static", page), "utf8");
        assert.match(html, /<meta name="referrer" content="no-referrer">/, page);
    }
});

test("a lost connection stops the run: that post is unknown, and the rest are never sent", async () => {
    const M = model();
    const sent = [];
    const outcome = await M.archiveAll([1, 2, 3], async (id) => {
        sent.push(id);
        if (id === 2) throw new Error("connection reset");
        return { items: [{ status: "SUCCESS" }] };
    });
    assert.deepEqual(sent, [1, 2]);
    assert.equal(outcome.unconfirmed, 2);
    assert.deepEqual([...outcome.notSent], [3]);
    assert.match(M.archiveSummary(outcome), /无法确认.*1 件未发送/);
});

test("every authorization failure code has its own explanation", () => {
    const M = model();
    for (const code of ["CALLBACK_PORT_BUSY", "TIMED_OUT", "DENIED", "REJECTED", "EXCHANGE_FAILED", "NETWORK"]) {
        assert.notEqual(M.connectionProblem(code), M.connectionProblem("UNKNOWN"), code);
    }
});
