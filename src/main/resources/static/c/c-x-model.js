/*
 * X 收藏 · 不依赖页面的部分：把同步报告、归档结果翻译成给人看的话，以及逐件归档的流程。
 * 服务端按 Post 处理：一次一件地请求，进度真实，某一件失败不影响其他件。
 */
(function (root) {
    'use strict';

    // 官方价目：授权用户同时是应用所有者时，读取自己的点赞 US$0.001 / 条（2026-11 核对）。以开发者控制台为准
    const OWNED_READ_USD = 0.001;

    const usd = (amount) => 'US$' + (amount < 0.01 ? amount.toFixed(3) : amount.toFixed(2));

    function costEstimate(items) {
        return usd(items * OWNED_READ_USD);
    }

    // 官方没有写明 /users/me 的单价，也会调价，所以这里只说上限和以谁为准，不说一定是多少
    function syncCost(scanBudget) {
        return `每次同步最多读取 ${scanBudget} 条点赞。授权账号就是应用所有者时约 ${costEstimate(scanBudget)}，否则单价可能更高；确认账号身份的读取另计，官方没有写明单价。价格可能调整，实际费用以 X 开发者控制台为准。`;
    }

    // 同步按钮旁一直显示的上限和估价：点之前就知道最多花多少
    function syncEstimate(scanBudget) {
        return `每次同步最多读 ${scanBudget} 条，约 ${costEstimate(scanBudget)}`;
    }

    // 设置页下拉框里的一项
    function readLimitLabel(reads) {
        return `${reads} 条（约 ${costEstimate(reads)}）`;
    }

    /*
     * “归档选中，跳过其余”之前先说件数。其余只指这一页上没选的，跳过的 10 秒内能撤销，之后也能在“最近跳过”里放回；
     * 归档要下载原图，不能撤销，所以只说跳过可以找回。
     */
    function archiveRestConfirm(keep, rest) {
        return {
            title: `归档 ${keep} 件，跳过本页其余 ${rest} 件？`,
            text: `选中的 ${keep} 件会下载原图存入图库；本页没选的 ${rest} 件不归档，移出待整理。跳过的 10 秒内可以撤销，之后也能在“最近跳过”里放回。`,
            ok: '归档并跳过'
        };
    }

    function upstreamProblem(status) {
        if (status === 401) return 'X 不再接受这次登录，请到设置中重新连接。';
        if (status === 402) return 'X 开发者账号的额度不足，请到开发者控制台充值后再试。';
        if (status === 403) return 'X 拒绝了请求：可能是额度不足，或应用没有读取点赞的权限。请到开发者控制台查看。';
        if (status === 429) return 'X 限制了请求频率，请过一会儿再试。进度已保存。';
        if (status >= 500) return 'X 暂时不可用，请稍后再试。进度已保存。';
        return '无法连接 X，请检查网络或设置中的代理。进度已保存。';
    }

    // 同步报告 → { headline, detail, problem }。计数都来自服务端，这里只负责说人话
    function syncSummary(report) {
        const recent = report.recent, history = report.history;
        const found = recent.newCount + history.newCount;
        const existing = recent.existingCount + history.existingCount;
        const unsupported = recent.unsupportedCount + history.unsupportedCount;
        const scanned = recent.scannedCount + history.scannedCount;
        const parts = [];
        if (existing) parts.push(`${existing} 件已经见过`);
        if (unsupported) parts.push(`${unsupported} 件没有可归档的图片`);
        if (scanned) parts.push(`读取 ${scanned} 条，约 ${costEstimate(scanned)}`);

        let problem = null;
        if (report.partial) {
            const reason = [recent.stopReason, history.stopReason];
            if (reason.includes('ACCOUNT_MISMATCH')) {
                return { headline: '这次没有读取收藏', detail: '', problem: '当前登录的 X 账号不能继续这份同步进度。已保存的收藏不受影响。' };
            }
            if (reason.includes('INVALID_TOKEN') || reason.includes('INVALID_CHECKPOINT')) problem = '同步进度已失效，需要重置后重新衔接。已保存的收藏不受影响。';
            else if (reason.includes('API_FAILURE')) problem = upstreamProblem(recent.upstreamStatus ?? history.upstreamStatus);
            else if (reason.includes('API_PARTIAL')) problem = 'X 这次只返回了部分数据，进度保留，下次会重试。';
            else problem = '这次同步没有全部完成，进度已保存，可以再试一次。';
        }

        const more = report.historyStatus === 'MORE' || report.recentStatus === 'ACTIVE' || report.recentStatus === 'VERIFY';
        return {
            headline: found ? `新发现 ${found} 件收藏` : '没有发现新的收藏',
            detail: parts.join('，') + (parts.length ? '。' : '') + (!problem && more ? '更早的收藏还没取完，再同步一次会接着往下。' : ''),
            problem
        };
    }

    /*
     * 同步进度需要用户处理时的说明和可选操作，账号问题优先：弄清进度属于谁之前，失效的游标也无从谈起。
     * 这些操作都不读取 X，也不会自动同步。重新扫描要等用户点“同步”，读到的内容同样计费。
     * 重置历史进度时后端会把近期进度一起从头安排，所以两者都失效时只需要这一个操作。
     */
    function recovery(progress, scanBudget = 100) {
        if (!progress) return null;
        const who = progress.currentAccount;
        // 用户名可能改过，要拍板的地方连 ID 一起给出；只有 ID 时 who 本身就是它
        const exact = progress.currentAccountId && !String(who).startsWith('ID ') ? `${who}（ID ${progress.currentAccountId}）` : who;
        // 这个版本在画出按钮时就定下来：确认的是用户此刻看到的内容，之后状态变了服务端会拒绝
        const seen = { confirmation: progress.accountConfirmation };
        const limit = `每次同步最多读取 ${scanBudget} 条，约 ${costEstimate(scanBudget)}。这次操作本身不读取 X。`;
        const restart = (label) => ({
            url: '/api/x-import/sync/account/restart',
            body: seen,
            label,
            confirmTitle: '清除旧进度，从头开始？',
            confirmText: `会清除保存的同步进度，下次同步从 ${exact} 最新的收藏开始，一路核对到最早的一条。Inbox、作品和媒体都会保留，但重新读取的内容同样计费，可能要同步多次才能读完。${limit}`,
            confirmLabel: '从头开始',
            doneHead: '同步进度已清除',
            doneDetail: `这次没有读取 X。准备好后点“同步”，从 ${who} 最新的收藏开始。`
        });
        if (progress.accountStatus === 'CHANGED') {
            return {
                title: `当前登录的 ${who} 不是这份进度的账号`,
                detail: '所以这次没有读取收藏，保存的进度也没有变化。如果是登录错了，到设置里重新连接原来的账号即可接着同步。确实要换账号时，才需要清除旧进度。Inbox、作品和媒体都会保留。',
                actions: [restart('确认更换账号，从头开始')]
            };
        }
        if (progress.accountStatus === 'UNATTRIBUTED') {
            return {
                title: `这份同步进度是 ${who} 的吗？`,
                detail: `这份进度保存时还没有记录账号，所以这次没有读取收藏。如果 ${who} 就是以前同步用的账号，确认后接着用旧进度，不用重新扫描。不确认就什么都不会变。`,
                actions: [{
                    url: '/api/x-import/sync/account/adopt',
                    body: seen,
                    label: `是 ${who}，继续用旧进度`,
                    confirmTitle: `确认 ${who} 是以前同步用的账号？`,
                    confirmText: `确认后，${exact} 会接着这份旧进度读取。如果其实是别的账号，保存的位置就和它的收藏对不上，所以请确认后再继续。这次操作不读取 X。`,
                    confirmLabel: '确认，继续用',
                    doneHead: '已确认账号',
                    doneDetail: `旧进度已归到 ${who}。这次没有读取 X，准备好后点“同步”继续。`
                }, restart('不是，从头开始')]
            };
        }
        const reset = (url, title, detail) => ({
            title,
            detail,
            actions: [{
                url,
                label: '重置进度',
                confirmTitle: '重置同步进度？',
                confirmText: `${detail}${limit}`,
                confirmLabel: '重置进度',
                doneHead: '同步进度已重置',
                doneDetail: '这次没有读取 X。准备好后点“同步”重新核对。'
            }]
        });
        if (progress.historyStatus === 'INVALID') {
            return reset('/api/x-import/sync/continuation/reset', '更早收藏的同步进度已失效',
                '保存的位置已经不能继续。重置后，下次同步会从最新的收藏开始，一路核对到最早的一条。已保存的收藏和作品都会保留，但重新读取的内容同样计费，可能要同步多次才能读完。');
        }
        if (progress.recentStatus === 'INVALID') {
            return reset('/api/x-import/sync/recent/reset', '最新收藏的同步进度已失效',
                '保存的位置已经不能继续。重置后，下次同步会从最新的收藏开始，读到上次确认过的位置为止。已保存的收藏和作品都会保留，但重新读取的内容同样计费。');
        }
        return null;
    }

    /*
     * 发出操作，再以服务端读回的进度为准：回执丢了但已经生效，或别的窗口先处理过，都不算失败。
     * 账号确认例外：别的窗口也能让问题消失，所以没收到成功回执就不能说成是这次确认的结果。
     * send() 返回错误说明（成功时为空，确认过期时为 { expired: true }），reload() 读回进度，读不到时返回 null。
     */
    async function runRecovery(action, send, reload) {
        let failure;
        try {
            failure = await send();
        } catch (e) {
            failure = e.message;
        }
        const progress = await reload();
        if (failure && failure.expired) {
            return { head: '账号状态已经变化', detail: '刚才确认的内容已过期，这次没有执行。请按最新的状态重新确认。已保存的收藏不受影响。', problem: true };
        }
        const unknown = { head: '无法确认操作结果', detail: '重新打开这个页面可以看到实际状态。已保存的收藏不受影响。', problem: true };
        if (!progress) return unknown;
        const remaining = recovery(progress);
        if (remaining && remaining.actions.some((other) => other.url === action.url)) {
            return { head: '这次操作没有完成', detail: `${failure || '进度没有变化'}。可以再试一次，已保存的收藏不受影响。`, problem: true };
        }
        if (action.body && failure) return unknown;
        return { head: action.doneHead, detail: action.doneDetail, problem: false };
    }

    function archiveProblem(result) {
        if (result.status === 'DUPLICATE') return '图库里已有这张图，可以跳过';
        const reason = String(result.reason || '');
        if (/dimension|resolution/i.test(reason)) return 'X 上的图片尺寸与记录不一致，请稍后重试';
        // CDN 只说明这次取不到，作品是否还在要到 X 上才知道
        if (/CDN request failed with HTTP 404/i.test(reason)) return 'X 的图片服务器找不到这张图，可以到 X 上确认';
        if (/CDN request failed with HTTP 403/i.test(reason)) return 'X 的图片服务器拒绝访问，可能是受保护的内容';
        if (/download|network|interrupted/i.test(reason)) return '图片下载失败，请检查网络或代理';
        return '归档失败，可以重试';
    }

    // X 官方的图片尺寸：<地址>?format=<扩展名>&name=small（680）/ medium（1200）/ large（2048）。其他形状的地址原样用
    function photoVariant(url, size) {
        const match = /^(https:\/\/pbs\.twimg\.com\/media\/[^/?#]+)\.(jpg|jpeg|png)$/i.exec(String(url));
        if (!match) return url;
        return `${match[1]}?format=${match[2].toLowerCase().replace('jpeg', 'jpg')}&name=${size}`;
    }

    /*
     * 逐件归档。post(id) 返回服务端的 Summary；抛错表示结果未知（断线等），此时停止，
     * 后面的不再发送，这一件标为“无法确认”：它可能已经入库，重新载入列表后就知道了。
     */
    async function archiveAll(ids, post, onProgress = () => {}) {
        const outcome = { archived: [], problems: new Map(), unconfirmed: null, notSent: [] };
        for (let i = 0; i < ids.length; i++) {
            const id = ids[i];
            onProgress(i, ids.length);
            let summary;
            try {
                summary = await post(id);
            } catch (e) {
                outcome.unconfirmed = id;
                outcome.notSent = ids.slice(i + 1);
                break;
            }
            const result = summary.items && summary.items[0];
            if (result && result.status === 'SUCCESS') outcome.archived.push(id);
            else outcome.problems.set(id, archiveProblem(result || {}));
        }
        return outcome;
    }

    function archiveSummary(outcome) {
        const parts = [];
        if (outcome.archived.length) parts.push(`已归档 ${outcome.archived.length} 件`);
        if (outcome.problems.size) parts.push(`${outcome.problems.size} 件没有归档`);
        if (outcome.unconfirmed != null) parts.push('连接中断，有 1 件无法确认，请看刷新后的列表');
        if (outcome.notSent.length) parts.push(`${outcome.notSent.length} 件未发送`);
        return parts.join('，');
    }

    function connectionProblem(code) {
        return {
            CALLBACK_PORT_BUSY: '回调端口被其他程序占用，请关闭后重试。',
            TIMED_OUT: '等待授权超时，请重新开始。',
            DENIED: '你在 X 上取消了授权。',
            REJECTED: 'X 没有完成授权，请重试。',
            EXCHANGE_FAILED: 'X 没有接受这次授权。请确认 Client ID 正确、应用类型是 Native App（Web App 和 Automated App 不能用），回调地址也完全一致。',
            NETWORK: '无法连接 X，请检查网络或下方的代理设置。',
            SAVE_FAILED: 'X 已同意授权，但本机没能保存，这次授权已作废。请检查磁盘空间和数据文件夹的写入权限后重试。'
        }[code] || '授权没有完成，请重试。';
    }

    function localTime(iso) {
        const at = new Date(iso);
        const pad = (value) => String(value).padStart(2, '0');
        return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())} ${pad(at.getHours())}:${pad(at.getMinutes())}`;
    }

    /*
     * 设置页的三行状态，各说各的：配置存没存、授权连没连、真实读取成没成。
     * 账号名只来自一次成功的同步。授权完成时还没有读过 X，这时不写任何账号。
     */
    function connectionSteps(connection) {
        const { state, savedLogin } = connection.status;
        const connected = savedLogin === 'USABLE';
        let login;
        if (savedLogin === 'UNUSABLE') login = { value: '本机的登录不能使用，需要重新授权', tone: 'bad' };
        else if (connected && state === 'WAITING') login = { value: '已连接，正在重新授权', tone: 'ok' };
        else if (connected && state === 'FAILED') login = { value: '已连接，这次重新授权没有完成', tone: 'ok' };
        else if (connected) login = { value: '已连接', tone: 'ok' };
        else if (state === 'WAITING') login = { value: '正在等待浏览器授权', tone: 'wait' };
        else if (state === 'FAILED') login = { value: '这次授权没有完成', tone: 'bad' };
        else login = { value: '未连接', tone: 'off' };
        let check;
        if (!connected) check = { value: '连接后，第一次同步时确认', tone: 'off' };
        else if (connection.verifiedAt) {
            const who = connection.verifiedAccount ? `@${connection.verifiedAccount}，` : '';
            check = { value: `已验证：${who}${localTime(connection.verifiedAt)} 同步成功`, tone: 'ok' };
        } else check = { value: '尚未验证：这次授权还没有同步过，账号也还没有读取', tone: 'wait' };
        return [
            { label: '开发者应用', ...(connection.clientId ? { value: 'Client ID 已保存在本机', tone: 'ok' } : { value: '未填写', tone: 'off' }) },
            { label: '浏览器授权', ...login },
            { label: '同步验证', ...check }
        ];
    }

    function aiSteps({ saved, verifiedAt }) {
        let check;
        if (!saved) check = { value: '保存 Key 后，第一次分析时确认', tone: 'off' };
        else if (verifiedAt) check = { value: `已验证：${localTime(verifiedAt)} 分析成功`, tone: 'ok' };
        else check = { value: '尚未验证：保存不代表 Google 会接受，第一次分析时才知道', tone: 'wait' };
        return [
            { label: 'API Key', ...(saved ? { value: '已保存在本机', tone: 'ok' } : { value: '未保存', tone: 'off' }) },
            { label: '分析验证', ...check }
        ];
    }

    // X 收藏页顶部的提示；已连接时不需要。connection 为空表示状态读不到
    function connectionCard(connection) {
        if (!connection) return { title: '暂时读不到 X 的连接状态', text: '已保存的收藏不受影响。可以到设置查看。', action: '打开设置' };
        const { state, failure, savedLogin } = connection.status;
        if (savedLogin === 'USABLE') return null;
        if (savedLogin === 'UNUSABLE') {
            return { title: '本机保存的 X 登录不能使用', text: '它可能来自开发版，或文件已经损坏。重新授权后才能同步，已保存的收藏不受影响。', action: '去重新授权' };
        }
        if (state === 'WAITING') return { title: '正在等待浏览器授权', text: '在浏览器里完成后，这里会自动更新。', action: '查看进度' };
        if (state === 'FAILED') return { title: '上次授权没有完成', text: connectionProblem(failure), action: '去处理' };
        return {
            title: '连接 X 账号后才能同步收藏',
            text: 'X 收藏是可选功能，本地图库不需要它。连接时使用你自己的 X 开发者应用，读取按量计费，费用记在你的开发者账号上。',
            action: connection.clientId ? '去授权' : '去设置'
        };
    }

    root.XInboxModel = { costEstimate, syncCost, syncEstimate, readLimitLabel, archiveRestConfirm, syncSummary, recovery, runRecovery, archiveAll, archiveSummary, archiveProblem, photoVariant, connectionProblem, connectionSteps, aiSteps, connectionCard };
})(typeof window !== 'undefined' ? window : globalThis);
