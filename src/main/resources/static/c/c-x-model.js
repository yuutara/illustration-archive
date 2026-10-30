/*
 * X 收藏 · 不依赖页面的部分：把同步报告、归档结果翻译成给人看的话，以及逐件归档的流程。
 * 服务端按 Post 处理：一次一件地请求，进度真实，某一件失败不影响其他件。
 */
(function (root) {
    'use strict';

    // 官方价目：应用属于授权账号本人时，读取自己的点赞 US$0.001 / 条（2026-10）。以开发者控制台为准
    const OWNED_READ_USD = 0.001;

    const usd = (amount) => 'US$' + (amount < 0.01 ? amount.toFixed(3) : amount.toFixed(2));

    function costEstimate(items) {
        return usd(items * OWNED_READ_USD);
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
            if (reason.includes('INVALID_TOKEN')) problem = '同步进度已失效，需要重置后重新衔接。已保存的收藏不受影响。';
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
            EXCHANGE_FAILED: 'X 没有接受这次授权。请确认 Client ID、回调地址和应用类型（Native App）都正确。',
            NETWORK: '无法连接 X，请检查网络或下方的代理设置。'
        }[code] || '授权没有完成，请重试。';
    }

    root.XInboxModel = { costEstimate, syncSummary, archiveAll, archiveSummary, archiveProblem, photoVariant, connectionProblem };
})(typeof window !== 'undefined' ? window : globalThis);
