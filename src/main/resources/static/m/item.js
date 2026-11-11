/* 手机版详情：图片按原始比例竖排，长按拿到的是 /api/assets/<id>/content 原图。 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const id = new URLSearchParams(location.search).get('id');

    function showError(text) {
        $('m-loading').hidden = true;
        $('m-error').hidden = false;
        $('m-error').textContent = text;
    }

    function formatDate(value) {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        return date.toLocaleDateString('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric' });
    }

    function renderPages(detail) {
        const pages = $('m-pages');
        const assets = Array.isArray(detail.assets) ? detail.assets : [];
        if (assets.length === 0) {
            showError('这件作品没有可看的图片。');
            return false;
        }
        const fragment = document.createDocumentFragment();
        assets.forEach((asset, index) => {
            const figure = document.createElement('figure');
            figure.className = 'm-page';
            const url = '/api/assets/' + asset.id + '/content';
            if ((asset.mimeType || '').startsWith('video/')) {
                const video = document.createElement('video');
                video.src = url;
                video.controls = true;
                video.loop = true;
                video.muted = true;
                video.playsInline = true;
                video.preload = 'metadata';
                figure.appendChild(video);
            } else {
                const img = document.createElement('img');
                img.src = url;
                img.loading = index === 0 ? 'eager' : 'lazy';
                img.decoding = 'async';
                img.alt = (detail.title || '作品') + (assets.length > 1 ? '，第 ' + (index + 1) + ' 页' : '');
                figure.appendChild(img);
            }
            if (assets.length > 1) {
                const caption = document.createElement('figcaption');
                caption.textContent = (index + 1) + ' / ' + assets.length;
                figure.appendChild(caption);
            }
            fragment.appendChild(figure);
        });
        pages.replaceChildren(fragment);
        $('m-pos').textContent = assets.length > 1 ? '共 ' + assets.length + ' 页' : '';
        return assets.some((asset) => !(asset.mimeType || '').startsWith('video/'));
    }

    function renderInfo(detail) {
        const title = detail.title || '未命名作品';
        document.title = title + ' · 插画档案';
        $('m-title').textContent = title;

        const by = $('m-by');
        by.replaceChildren();
        const author = detail.author;
        let hasBy = false;
        if (author && author.displayName) {
            const name = document.createElement('span');
            name.className = 'm-author';
            name.textContent = author.displayName;
            by.appendChild(name);
            hasBy = true;
        }
        if (author && author.xUsername) {
            by.appendChild(document.createTextNode((hasBy ? ' ' : '') + '@' + author.xUsername));
            hasBy = true;
        }
        const collected = formatDate(detail.createdAt);
        if (collected) {
            by.appendChild(document.createTextNode((hasBy ? ' · ' : '') + '收于 ' + collected));
            hasBy = true;
        }
        by.hidden = !hasBy;

        const tags = $('m-tags');
        tags.replaceChildren(...(Array.isArray(detail.tags) ? detail.tags : []).map((tag) => {
            const chip = document.createElement('span');
            chip.className = 'm-tag';
            chip.textContent = tag.name;
            return chip;
        }));
        tags.hidden = tags.children.length === 0;

        const note = detail.note || '';
        $('m-note').textContent = note;
        $('m-note').hidden = !note;

        const source = typeof detail.sourceUrl === 'string' && /^https?:\/\//i.test(detail.sourceUrl) ? detail.sourceUrl : null;
        $('m-source').hidden = !source;
        if (source) $('m-source-link').href = source;

        $('m-info').hidden = false;
    }

    function showHint(hasImage) {
        if (!hasImage) return;
        let first = false;
        try {
            first = !sessionStorage.getItem('ia-m-save-hint');
            if (first) sessionStorage.setItem('ia-m-save-hint', '1');
        } catch (unavailable) {
            first = true;
        }
        if (!first) return;
        $('m-save-hint').hidden = false;
        $('m-hint-ok').addEventListener('click', () => { $('m-save-hint').hidden = true; });
    }

    async function load() {
        if (!id || !/^\d+$/.test(id)) {
            showError('没有找到这件作品。');
            return;
        }
        try {
            const response = await fetch('/api/illustrations/' + id, { headers: { Accept: 'application/json' } });
            if (response.status === 403) {
                location.replace('/m/pair.html?e=3');
                return;
            }
            if (response.status === 404) {
                showError('这件作品不存在，或者已经从图库里删除了。');
                return;
            }
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const detail = await response.json();
            $('m-loading').hidden = true;
            const hasImage = renderPages(detail);
            renderInfo(detail);
            showHint(hasImage);
        } catch (error) {
            showError('读取失败：' + error.message + '。请确认电脑上的应用还开着。');
        }
    }

    $('m-back').addEventListener('click', (event) => {
        const referrer = document.referrer;
        if (referrer && referrer.startsWith(location.origin + '/m/')) {
            event.preventDefault();
            history.back();
        }
    });

    load();
})();
