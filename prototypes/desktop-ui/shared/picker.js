/*
 * 两套原型共用的选择弹层（作者单选、标签多选、可新建）。
 * 只负责行为；外观由各原型的 .pk-* 样式决定。
 */
(function () {
    'use strict';

    let current = null;

    function close() {
        if (!current) return;
        const { el, onOutside, anchor, onClose } = current;
        document.removeEventListener('mousedown', onOutside, true);
        el.remove();
        anchor.setAttribute('aria-expanded', 'false');
        current = null;
        if (onClose) onClose();
    }

    function position(el, anchor) {
        const r = anchor.getBoundingClientRect();
        const w = el.offsetWidth, h = el.offsetHeight;
        let left = Math.min(r.left, window.innerWidth - w - 8);
        let top = r.bottom + 6;
        if (top + h > window.innerHeight - 8) top = Math.max(8, r.top - h - 6);
        el.style.left = Math.max(8, left) + 'px';
        el.style.top = top + 'px';
    }

    function open(anchor, options) {
        if (current && current.anchor === anchor) { close(); return; }
        close();
        const { placeholder = '搜索', items, selected, multi = false, onSelect, onCreate, createText, clearLabel, emptyText = '没有匹配项' } = options;
        const el = document.createElement('div');
        el.className = 'pk';
        el.setAttribute('role', 'dialog');
        el.innerHTML = '<input class="pk-input" type="text" autocomplete="off"><ul class="pk-list" role="listbox"></ul><p class="pk-error" hidden></p>';
        const input = el.querySelector('.pk-input');
        const list = el.querySelector('.pk-list');
        const error = el.querySelector('.pk-error');
        input.placeholder = placeholder;
        if (multi) list.setAttribute('aria-multiselectable', 'true');

        let rows = [];
        let active = 0;

        function render() {
            const q = input.value.trim().toLowerCase();
            const sel = selected();
            rows = [];
            if (clearLabel && !q) rows.push({ id: null, label: clearLabel, kind: 'clear' });
            items().forEach((item) => {
                const text = (item.label + ' ' + (item.meta || '')).toLowerCase();
                if (!q || text.includes(q.replace(/^@/, ''))) rows.push(item);
            });
            const exact = items().some((i) => i.label.toLowerCase() === q);
            if (onCreate && q && !exact) rows.push({ id: '__create', label: createText(input.value.trim()), kind: 'create' });
            active = Math.min(active, Math.max(0, rows.length - 1));
            list.innerHTML = '';
            if (!rows.length) {
                const li = document.createElement('li');
                li.className = 'pk-empty';
                li.textContent = emptyText;
                list.appendChild(li);
            }
            rows.forEach((row, i) => {
                const li = document.createElement('li');
                li.className = 'pk-item' + (row.kind ? ' pk-' + row.kind : '');
                li.setAttribute('role', 'option');
                const isSel = row.kind ? false : sel.has(row.id);
                li.setAttribute('aria-selected', String(isSel));
                if (isSel) li.classList.add('is-selected');
                if (i === active) li.classList.add('is-active');
                li.innerHTML = '<span class="pk-check" aria-hidden="true"></span><span class="pk-label"></span><span class="pk-meta"></span>';
                li.querySelector('.pk-label').textContent = row.label;
                li.querySelector('.pk-meta').textContent = row.meta || '';
                li.addEventListener('mousedown', (e) => e.preventDefault());
                li.addEventListener('click', () => choose(i));
                li.addEventListener('mousemove', () => {
                    if (active !== i) { active = i; markActive(); }
                });
                list.appendChild(li);
            });
        }

        function markActive() {
            [...list.children].forEach((li, i) => li.classList.toggle('is-active', i === active));
            const li = list.children[active];
            if (li) li.scrollIntoView({ block: 'nearest' });
        }

        function choose(i) {
            const row = rows[i];
            if (!row) return;
            error.hidden = true;
            if (row.kind === 'create') {
                try {
                    const id = onCreate(input.value.trim());
                    onSelect(id);
                    input.value = '';
                } catch (e) {
                    error.textContent = e.message;
                    error.hidden = false;
                    if (e.existing) {
                        input.value = e.existing.name;
                    }
                    render();
                    return;
                }
            } else {
                onSelect(row.id);
            }
            if (!multi) { close(); return; }
            render();
            input.focus();
        }

        input.addEventListener('input', () => { active = 0; error.hidden = true; render(); position(el, anchor); });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); active = Math.min(rows.length - 1, active + 1); markActive(); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(0, active - 1); markActive(); }
            else if (e.key === 'Enter') { e.preventDefault(); choose(active); }
            else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); anchor.focus(); }
            else if (e.key === 'Tab') { close(); }
            e.stopPropagation();
        });

        const onOutside = (e) => {
            if (!el.contains(e.target) && !anchor.contains(e.target)) close();
        };
        document.addEventListener('mousedown', onOutside, true);

        document.body.appendChild(el);
        anchor.setAttribute('aria-expanded', 'true');
        current = { el, anchor, onOutside, onClose: options.onClose };
        render();
        position(el, anchor);
        input.focus();
    }

    window.Picker = { open, close, isOpen: () => !!current };
})();
