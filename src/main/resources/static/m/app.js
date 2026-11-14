/* 手机版图库：两列封面 + 搜索 + 载入更多。只读接口；没有配对时服务端会 403。 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const grid = $('m-grid');
    const PAGE_SIZE = 24;

    let page = 0;
    let totalPages = 0;
    let query = '';
    let requestId = 0;
    let loadingMore = false;
    let searchTimer = 0;

    function toPairing() {
        location.replace('/m/pair.html?e=3');
    }

    function placeholder(text) {
        const box = document.createElement('span');
        box.className = 'm-video-placeholder';
        const mark = document.createElement('b');
        mark.textContent = text === '动图' ? '▶' : '·';
        box.appendChild(mark);
        box.appendChild(document.createTextNode(text));
        return box;
    }

    function cardFor(item) {
        const title = item.title || '未命名作品';
        const card = document.createElement('a');
        card.className = 'm-card';
        card.href = '/m/item.html?id=' + item.id;
        card.setAttribute('aria-label', title + (item.assetCount > 1 ? '，' + item.assetCount + ' 页' : ''));

        const media = document.createElement('span');
        media.className = 'm-card-media';
        const mime = item.coverMimeType || '';
        if (item.coverAssetId == null || mime.startsWith('video/')) {
            media.appendChild(placeholder(mime.startsWith('video/') ? '动图' : '图片读不到'));
        } else {
            const img = document.createElement('img');
            img.alt = '';
            img.loading = 'lazy';
            img.decoding = 'async';
            img.src = '/api/assets/' + item.coverAssetId + '/thumbnail';
            img.addEventListener('load', () => img.classList.add('is-loaded'));
            if (img.complete) img.classList.add('is-loaded');
            img.addEventListener('error', () => {
                if (img.dataset.fallback === '1') {
                    media.replaceChildren(placeholder('图片读不到'));
                    return;
                }
                img.dataset.fallback = '1';
                img.src = '/api/assets/' + item.coverAssetId + '/content';
            });
            media.appendChild(img);
        }
        if (item.assetCount > 1) {
            const badge = document.createElement('span');
            badge.className = 'm-badge';
            badge.textContent = item.assetCount + ' 页';
            media.appendChild(badge);
        }
        card.appendChild(media);

        const meta = document.createElement('span');
        meta.className = 'm-card-meta';
        const name = document.createElement('span');
        name.className = 'm-card-title';
        name.textContent = title;
        meta.appendChild(name);
        if (item.author && item.author.displayName) {
            const author = document.createElement('span');
            author.className = 'm-card-author';
            author.textContent = item.author.displayName;
            meta.appendChild(author);
        }
        card.appendChild(meta);
        return card;
    }

    function showEmpty(total) {
        const empty = $('m-empty');
        empty.hidden = false;
        $('m-empty-title').textContent = query ? '没有找到符合条件的作品' : '图库里还没有作品';
        $('m-empty-text').textContent = query ? '换个关键词试试，或者清除搜索。' : '在电脑上收几张作品，再回到这里刷新。';
        $('m-empty-clear').hidden = !query;
        $('m-count').textContent = total > 0 ? '共 ' + total + ' 件' : '';
    }

    async function load(reset) {
        if (!reset && (loadingMore || page >= totalPages)) return;
        if (!reset) loadingMore = true;
        const id = ++requestId;
        if (reset) {
            page = 0;
            totalPages = 0;
            loadingMore = false;
            grid.replaceChildren();
            $('m-empty').hidden = true;
            $('m-status').hidden = true;
            $('m-more').hidden = true;
        }

        const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
        if (query) params.set('q', query);
        try {
            const response = await fetch('/api/illustrations?' + params.toString(), {
                headers: { Accept: 'application/json' }
            });
            if (id !== requestId) return;
            if (response.status === 403) {
                toPairing();
                return;
            }
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const data = await response.json();
            totalPages = data.totalPages;
            $('m-count').textContent = data.totalElements > 0 ? '共 ' + data.totalElements + ' 件' : '';
            if (reset && data.items.length === 0) showEmpty(data.totalElements);
            const fragment = document.createDocumentFragment();
            data.items.forEach((item) => fragment.appendChild(cardFor(item)));
            grid.appendChild(fragment);
            page += 1;
            $('m-empty').hidden = grid.children.length > 0;
            $('m-more').hidden = page >= totalPages;
            $('m-more').disabled = false;
        } catch (error) {
            if (id !== requestId) return;
            $('m-status').hidden = false;
            $('m-status-text').textContent = '读取失败：' + error.message + '。请确认电脑上的应用还开着。';
        } finally {
            // A reset may have invalidated this request; the flag must not stay set and block the next "more".
            loadingMore = false;
            if (id === requestId) $('m-more').disabled = false;
        }
    }

    function searchNow() {
        query = $('m-q').value.trim();
        $('m-clear').hidden = !$('m-q').value;
        load(true);
    }

    $('m-q').addEventListener('input', () => {
        $('m-clear').hidden = !$('m-q').value;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(searchNow, 220);
    });
    $('m-q').addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
            event.preventDefault();
            clearTimeout(searchTimer);
            searchNow();
        }
    });
    $('m-clear').addEventListener('click', () => {
        $('m-q').value = '';
        $('m-clear').hidden = true;
        query = '';
        load(true);
        $('m-q').focus();
    });
    $('m-empty-clear').addEventListener('click', () => {
        $('m-q').value = '';
        $('m-clear').hidden = true;
        query = '';
        load(true);
    });
    $('m-retry').addEventListener('click', () => load(true));
    $('m-more').addEventListener('click', () => {
        $('m-more').disabled = true;
        load(false);
    });

    if ('IntersectionObserver' in window) {
        new IntersectionObserver((entries) => {
            if (entries[0].isIntersecting && !loadingMore && page < totalPages) load(false);
        }, { rootMargin: '600px' }).observe($('m-more'));
    }

    async function loadCapabilities() {
        const nav = $('m-nav');
        if (!nav) return;
        try {
            const response = await fetch('/api/m/capabilities', {
                headers: { Accept: 'application/json' },
                cache: 'no-store'
            });
            if (response.status === 403) {
                toPairing();
                return;
            }
            if (!response.ok) return;
            const caps = await response.json();
            nav.hidden = !(caps && caps.xInboxEnabled);
        } catch (ignored) {
            // Keep navigation hidden if capabilities cannot be reached.
        }
    }

    if (typeof window !== 'undefined' && window.addEventListener) {
        window.addEventListener('pageshow', () => loadCapabilities());
    }
    if (document.addEventListener) {
        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) loadCapabilities();
        });
    }

    loadCapabilities();
    load(true);
})();
