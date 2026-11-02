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

test("a checkpoint that needs resetting is not reported as something another try would fix", () => {
    const summary = model().syncSummary(report(phase({ stopReason: "INVALID_CHECKPOINT" }), phase(), { partial: true }));
    assert.match(summary.problem, /进度已失效/);
});

test("an account the progress does not belong to reads nothing, and the result says so", () => {
    const summary = model().syncSummary(report(phase({ stopReason: "ACCOUNT_MISMATCH" }), phase({ stopReason: "SKIPPED_AFTER_FAILURE" }), { partial: true }));
    assert.equal(summary.headline, "这次没有读取收藏");
    assert.match(summary.problem, /不能继续这份同步进度/);
});

test("the recovery offered follows what actually failed", () => {
    const M = model();
    const progress = (recentStatus, historyStatus) => ({ recentStatus, historyStatus, unscannedIntervals: true, accountStatus: "OK" });
    assert.equal(M.recovery(null), null);
    assert.equal(M.recovery(progress("ACTIVE", "MORE")), null);
    assert.equal(M.recovery(progress("CAUGHT_UP", "COMPLETE")), null);
    assert.equal(M.recovery(progress("UNKNOWN", "UNKNOWN")), null);
    const only = (found) => { assert.equal(found.actions.length, 1); return found.actions[0].url; };
    assert.equal(only(M.recovery(progress("INVALID", "COMPLETE"))), "/api/x-import/sync/recent/reset");
    assert.equal(only(M.recovery(progress("ACTIVE", "INVALID"))), "/api/x-import/sync/continuation/reset");
    assert.equal(only(M.recovery(progress("INVALID", "INVALID"))), "/api/x-import/sync/continuation/reset", "this reset restarts Recent as well");
});

test("every reset says the collection is kept and that scanning again is billed", () => {
    const M = model();
    for (const found of [M.recovery({ historyStatus: "INVALID" }, 40), M.recovery({ recentStatus: "INVALID" }, 40)]) {
        assert.match(found.detail, /已保存的收藏和作品都会保留/);
        assert.match(found.detail, /同样计费/);
        assert.match(found.actions[0].confirmText, /同样计费/);
        assert.match(found.actions[0].confirmText, /40 条，约 US\$0\.04/);
    }
});

test("another signed-in account gets one way forward, named, and the account question comes first", () => {
    const found = model().recovery({ accountStatus: "CHANGED", currentAccount: "@other", recentStatus: "INVALID", historyStatus: "INVALID" });
    assert.match(found.title, /@other/);
    assert.match(found.detail, /没有读取收藏/);
    assert.match(found.detail, /重新连接原来的账号/, "signing in to the wrong account is not a reason to drop anything");
    assert.deepEqual([...found.actions.map((action) => action.url)], ["/api/x-import/sync/account/restart"]);
    assert.match(found.actions[0].confirmText, /Inbox、作品和媒体都会保留/);
    assert.match(found.actions[0].confirmText, /同样计费/);
});

test("progress with no owner asks about the named account and offers both answers", () => {
    const found = model().recovery({ accountStatus: "UNATTRIBUTED", currentAccount: "@me" });
    assert.match(found.title, /@me/);
    assert.deepEqual([...found.actions.map((action) => action.url)],
        ["/api/x-import/sync/account/adopt", "/api/x-import/sync/account/restart"]);
    assert.match(found.actions[0].label, /@me/);
    assert.match(found.actions[0].doneDetail, /没有读取 X/);
});

test("each account answer carries what the user was shown, and the question names the exact account", () => {
    const shown = { accountStatus: "UNATTRIBUTED", currentAccount: "@me", currentAccountId: "42", accountConfirmation: "null/42/t1" };
    for (const action of model().recovery(shown).actions) {
        assert.equal(action.body.confirmation, "null/42/t1");
        assert.match(action.confirmText, /@me（ID 42）/);
    }
    const nameless = model().recovery({ ...shown, currentAccount: "ID 42" }).actions[0];
    assert.match(nameless.confirmText, /ID 42 会接着/, "an id alone is not repeated");
    assert.equal(model().recovery({ recentStatus: "INVALID" }).actions[0].body, undefined, "resets have no account to bind");
});

test("an expired confirmation says nothing was done, whatever the page reads next", async () => {
    const M = model();
    const adopt = M.recovery({ accountStatus: "UNATTRIBUTED", currentAccount: "@me", accountConfirmation: "old" }).actions[0];
    const expired = () => ({ expired: true });
    const samePage = await M.runRecovery(adopt, async () => expired(), async () => ({ accountStatus: "UNATTRIBUTED", currentAccount: "@other", accountConfirmation: "new" }));
    const settled = await M.runRecovery(adopt, async () => expired(), async () => ({ accountStatus: "OK" }));
    for (const outcome of [samePage, settled]) {
        assert.equal(outcome.head, "账号状态已经变化");
        assert.equal(outcome.problem, true);
        assert.match(outcome.detail, /没有执行/);
    }
    assert.notEqual(settled.head, adopt.doneHead, "another window settling it is not this answer succeeding");
});

test("a lost receipt on an account answer is not reported as that answer succeeding", async () => {
    const M = model();
    const adopt = M.recovery({ accountStatus: "UNATTRIBUTED", currentAccount: "@me", accountConfirmation: "old" }).actions[0];
    const lost = await M.runRecovery(adopt, async () => { throw new Error("connection reset"); }, async () => ({ accountStatus: "OK" }));
    assert.equal(lost.head, "无法确认操作结果");
    assert.equal(lost.problem, true);
});

test("an account that is fine adds nothing to the page", () => {
    assert.equal(model().recovery({ accountStatus: "OK", currentAccount: null, recentStatus: "ACTIVE", historyStatus: "MORE" }), null);
});

test("each answer reports its own outcome, and a leftover other problem does not count against it", async () => {
    const M = model();
    const adopt = M.recovery({ accountStatus: "UNATTRIBUTED", currentAccount: "@me" }).actions[0];
    const outcome = await M.runRecovery(adopt, async () => null, async () => ({ accountStatus: "OK", recentStatus: "INVALID", historyStatus: "MORE" }));
    assert.equal(outcome.problem, false);
    assert.equal(outcome.head, "已确认账号");
});

test("a reset is judged by the progress the server reports afterwards", async () => {
    const M = model();
    const invalid = { recentStatus: "INVALID", historyStatus: "MORE" };
    const fixed = { recentStatus: "ACTIVE", historyStatus: "MORE" };
    const action = M.recovery(invalid).actions[0];

    assert.equal((await M.runRecovery(action, async () => null, async () => fixed)).head, "同步进度已重置");
    const lostReceipt = await M.runRecovery(action, async () => { throw new Error("connection reset"); }, async () => fixed);
    assert.equal(lostReceipt.problem, false, "the reset happened; only its receipt was lost");
    const otherWindow = await M.runRecovery(action, async () => "Recent progress is not invalid.", async () => fixed);
    assert.equal(otherWindow.problem, false, "already done is not a failure");

    const failed = await M.runRecovery(action, async () => "HTTP 500", async () => invalid);
    assert.equal(failed.problem, true);
    assert.match(failed.detail, /HTTP 500/);
    assert.match(failed.detail, /已保存的收藏不受影响/);

    const unknown = await M.runRecovery(action, async () => { throw new Error("offline"); }, async () => null);
    assert.equal(unknown.head, "无法确认操作结果");
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
    for (const code of ["CALLBACK_PORT_BUSY", "TIMED_OUT", "DENIED", "REJECTED", "EXCHANGE_FAILED", "NETWORK", "SAVE_FAILED"]) {
        assert.notEqual(M.connectionProblem(code), M.connectionProblem("UNKNOWN"), code);
    }
});

const connection = (state, savedLogin, rest = {}) => ({ status: { state, savedLogin }, clientId: "", verifiedAt: null, verifiedAccount: null, ...rest });
const values = (steps) => steps.map((step) => step.value);

test("saved, connected and verified are three separate lines that never stand in for each other", () => {
    const M = model();
    const [app, login, check] = values(M.connectionSteps(connection("DISCONNECTED", "NONE", { clientId: "client-id-123" })));
    assert.match(app, /已保存/);
    assert.equal(login, "未连接");
    assert.doesNotMatch(check, /已验证/);

    const authorized = values(M.connectionSteps(connection("CONNECTED", "USABLE", { clientId: "client-id-123" })));
    assert.equal(authorized[1], "已连接");
    assert.match(authorized[2], /^尚未验证/);
    assert.doesNotMatch(authorized.join(), /@/, "no account until X has been read");
});

test("only a finished sync gives an account, and it is shown as X returned it", () => {
    const M = model();
    const verified = connection("CONNECTED", "USABLE", { verifiedAt: "2026-11-02T06:03:00Z", verifiedAccount: "someone" });
    assert.match(M.connectionSteps(verified)[2].value, /^已验证：@someone，\d{4}-11-0\d \d\d:\d\d 同步成功$/);
    const nameless = connection("CONNECTED", "USABLE", { verifiedAt: "2026-11-02T06:03:00Z" });
    assert.match(M.connectionSteps(nameless)[2].value, /^已验证：\d{4}/);
    assert.doesNotMatch(M.connectionSteps(connection("DISCONNECTED", "NONE", { verifiedAccount: "someone" })).map((s) => s.value).join(), /someone/);
});

test("each authorization state reads as itself", () => {
    const M = model();
    const login = (state, saved) => M.connectionSteps(connection(state, saved))[1];
    assert.equal(login("WAITING", "NONE").value, "正在等待浏览器授权");
    assert.equal(login("WAITING", "USABLE").value, "已连接，正在重新授权");
    assert.equal(login("FAILED", "NONE").value, "这次授权没有完成");
    assert.equal(login("FAILED", "NONE").tone, "bad");
    assert.equal(login("FAILED", "USABLE").value, "已连接，这次重新授权没有完成");
    assert.equal(login("DISCONNECTED", "UNUSABLE").tone, "bad");
    assert.doesNotMatch(login("DISCONNECTED", "UNUSABLE").value, /已连接/);
});

test("a saved Gemini key is not a working one until an analysis says so", () => {
    const M = model();
    assert.equal(values(M.aiSteps({ saved: false, verifiedAt: null }))[0], "未保存");
    const saved = values(M.aiSteps({ saved: true, verifiedAt: null }));
    assert.equal(saved[0], "已保存在本机");
    assert.match(saved[1], /^尚未验证/);
    assert.match(values(M.aiSteps({ saved: true, verifiedAt: "2026-11-02T06:03:00Z" }))[1], /^已验证：\d{4}.* 分析成功$/);
});

test("X 收藏 needs no card when a login is usable, and otherwise says what to do next", () => {
    const M = model();
    assert.equal(M.connectionCard(connection("WAITING", "USABLE")), null);
    assert.equal(M.connectionCard(connection("FAILED", "USABLE")), null);
    assert.equal(M.connectionCard(connection("DISCONNECTED", "NONE")).action, "去设置");
    assert.equal(M.connectionCard(connection("DISCONNECTED", "NONE", { clientId: "client-id-123" })).action, "去授权");
    assert.match(M.connectionCard(connection("WAITING", "NONE")).title, /等待/);
    assert.match(M.connectionCard({ status: { state: "FAILED", savedLogin: "NONE", failure: "TIMED_OUT" } }).text, /超时/);
    assert.match(M.connectionCard(connection("DISCONNECTED", "UNUSABLE")).title, /不能使用/);
    assert.match(M.connectionCard(null).title, /读不到/);
});

test("the sync cost gives a ceiling and who decides it, not a promise", () => {
    const text = model().syncCost(100);
    assert.match(text, /最多读取 100 条/);
    assert.match(text, /US\$0\.10/);
    assert.match(text, /应用所有者/);
    assert.match(text, /开发者控制台为准/);
    assert.doesNotMatch(text, /免费/);
});
