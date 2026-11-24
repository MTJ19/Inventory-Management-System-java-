'use strict';
// Progressive enhancement: Java renders the views and permissions; this only switches panels.
const views = [...document.querySelectorAll('[data-view]')];
const navigation = [...document.querySelectorAll('[data-nav]')];
const label = document.querySelector('#current-view-label');
history.scrollRestoration = 'manual';

const sidebar = document.querySelector('#workspace-sidebar');
const sidebarToggle = document.querySelector('#sidebar-toggle');
const sidebarPreference = 'voltedge.sidebar.hidden';
function setSidebarHidden(hidden) {
    if (!sidebar || !sidebarToggle) return;
    sidebar.hidden = hidden;
    document.body.classList.toggle('sidebar-hidden', hidden);
    sidebarToggle.setAttribute('aria-expanded', String(!hidden));
    const action = hidden ? 'Show workspace sidebar' : 'Hide workspace sidebar';
    sidebarToggle.setAttribute('aria-label', action);
    sidebarToggle.title = action;
}
try { setSidebarHidden(localStorage.getItem(sidebarPreference) === 'true'); }
catch { setSidebarHidden(false); }
sidebarToggle?.addEventListener('click', () => {
    setSidebarHidden(!sidebar.hidden);
    try { localStorage.setItem(sidebarPreference, String(sidebar.hidden)); }
    catch { /* Navigation still toggles when browser storage is unavailable. */ }
});

function showView(hash, focus = false) {
    let targetId;
    try { targetId = decodeURIComponent(hash.replace(/^#/, '')); }
    catch { targetId = ''; }
    if (targetId === 'home') targetId = document.body.dataset.initialView;
    const target = document.getElementById(targetId);
    const view = target?.closest('[data-view]')
        || views.find(item => item.dataset.view === document.body.dataset.initialView)
        || views[0];
    if (!view) return;
    for (const item of views) {
        item.hidden = item !== view;
        item.classList.toggle('is-active', item === view);
    }
    const selectedLink = navigation.find(link => link.dataset.nav === targetId)
        || navigation.find(link => link.dataset.nav === view.dataset.view);
    for (const link of navigation) {
        if (link === selectedLink) {
            link.setAttribute('aria-current', 'page');
            const group = link.closest('.nav-group');
            if (group) group.open = true;
        }
        else link.removeAttribute('aria-current');
    }
    label.textContent = selectedLink?.textContent.trim() || 'Dashboard';
    if (target && target !== view) {
        for (let node = target.parentElement; node && node !== view; node = node.parentElement) {
            if (node.tagName === 'DETAILS') node.open = true;
        }
    }
    requestAnimationFrame(() => {
        if (target && target !== view) target.scrollIntoView({ block: 'start' });
        else window.scrollTo({ top: 0, behavior: 'instant' });
        if (focus) {
            const heading = view.querySelector('h1, h2');
            if (heading) {
                heading.setAttribute('tabindex', '-1');
                heading.focus({ preventScroll: true });
            }
        }
    });
}

document.addEventListener('click', event => {
    const link = event.target.closest('a[href^="#"]');
    if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    if (!document.getElementById(link.hash.slice(1))?.closest('[data-view]')) return;
    event.preventDefault();
    history.pushState(null, '', link.hash);
    showView(link.hash, true);
});
window.addEventListener('popstate', () => showView(location.hash));
window.addEventListener('hashchange', () => showView(location.hash));
window.addEventListener('pageshow', () => showView(location.hash));
// Retain the current view when Java refreshes a filtered report or catalogue.
for (const form of document.querySelectorAll('form[method="get"]')) {
    form.addEventListener('submit', () => {
        const view = form.closest('[data-view]');
        const action = new URL(form.action, location.href);
        if (view && !action.hash) {
            action.hash = view.id;
            form.action = action.href;
        }
    });
}
showView(location.hash);

const saleLines = document.querySelector('#sale-lines');
const money = value => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(value / 100);
if (saleLines) {
    const form = saleLines.closest('form');
    const template = saleLines.querySelector('.sale-line').cloneNode(true);
    const picker = document.querySelector('#product-picker');
    const status = document.querySelector('#cart-status');
    const rows = () => [...saleLines.querySelectorAll('.sale-line')];
    const announce = text => { status.textContent = text; };
    function enhance(row) {
        const select = row.querySelector('select');
        const quantity = row.querySelector('[name^="qty"]');
        const option = select.selectedOptions[0];
        const sku = select.value;
        const name = option.textContent.replace(/ \(\d+ available\)$/, '');
        select.hidden = true;
        select.parentElement.classList.add('cart-product-label');
        const title = document.createElement('strong');
        title.textContent = name;
        select.parentElement.append(title);
        const price = document.createElement('small');
        price.textContent = `${money(Math.round(Number(option.dataset.price) * 100))} each · ${option.dataset.stock} available`;
        select.parentElement.append(price);
        quantity.max = option.dataset.stock;
        quantity.setAttribute('aria-label', `Quantity for ${name}`);
        quantity.parentElement.querySelector('.field-hint')?.remove();
        const controls = document.createElement('span');
        controls.className = 'cart-quantity';
        quantity.before(controls);
        for (const [symbol, delta, label] of [['−', -1, 'Decrease'], ['+', 1, 'Increase']]) {
            const button = document.createElement('button');
            button.type = 'button'; button.textContent = symbol;
            button.className = 'secondary quantity-step';
            button.dataset.delta = delta;
            button.setAttribute('aria-label', `${label} quantity for ${name}`);
            controls.append(button);
            if (delta === -1) controls.append(quantity);
        }
        row.querySelector('.remove-line').setAttribute('aria-label', `Remove ${name} from cart`);
        row.dataset.sku = sku;
        const amount = document.createElement('strong');
        amount.className = 'cart-line-total'; row.append(amount);
    }
    if (saleLines.dataset.restored !== 'true') saleLines.replaceChildren();
    else rows().forEach(enhance);
    picker.hidden = false;
    document.querySelector('#add-sale-line').hidden = true;
    function update() {
        let subtotal = 0;
        rows().forEach((row, i) => {
            const select = row.querySelector('select');
            const qty = row.querySelector('input');
            select.name = `sku${i}`; qty.name = `qty${i}`;
            const amount = Math.round(Number(select.selectedOptions[0].dataset.price) * 100) * (Number(qty.value) || 0);
            subtotal += amount;
            row.querySelector('.cart-line-total').textContent = money(amount);
            row.querySelector('[data-delta="-1"]').disabled = Number(qty.value) <= 1;
            row.querySelector('[data-delta="1"]').disabled = Number(qty.value) >= Number(qty.max);
        });
        const discount = form.querySelector('[name="discount"]');
        discount.max = (subtotal / 100).toFixed(2);
        const discountCents = Math.round(Number(discount.value || 0) * 100);
        document.querySelector('#sale-preview').textContent = `Subtotal ${money(subtotal)} · Discount ${money(discountCents)} · Total ${money(subtotal - discountCents)}`;
        document.querySelector('#empty-cart').hidden = rows().length > 0;
        document.querySelector('#save-order').disabled = rows().length === 0;
        document.querySelectorAll('.add-to-cart').forEach(button => {
            const option = [...template.querySelector('select').options].find(item => item.value === button.dataset.sku);
            const row = rows().find(item => item.dataset.sku === button.dataset.sku);
            button.disabled = Number(option?.dataset.stock || 0) <= Number(row?.querySelector('input').value || 0);
        });
    }
    picker.addEventListener('click', event => {
        const button = event.target.closest('.add-to-cart');
        if (!button) return;
        let row = rows().find(item => item.dataset.sku === button.dataset.sku);
        if (row) {
            const qty = row.querySelector('input');
            if (Number(qty.value) >= Number(qty.max)) return;
            qty.value = Math.min(Number(qty.max), Math.max(1, Number(qty.value) || 0) + 1);
            qty.setCustomValidity('');
        } else {
            if (rows().length >= 20) { announce('A cart can hold up to 20 different products. Save this order before starting another.'); return; }
            row = template.cloneNode(true);
            row.querySelector('select').value = button.dataset.sku;
            row.querySelector('input').value = '1';
            enhance(row); saleLines.append(row);
        }
        announce('Added to cart. Review the quantity on the right.'); update();
    });
    saleLines.addEventListener('click', event => {
        const row = event.target.closest('.sale-line');
        if (!row) return;
        if (event.target.closest('.remove-line')) { row.remove(); announce('Item removed from cart.'); }
        const step = event.target.closest('.quantity-step');
        if (step) {
            const qty = row.querySelector('input');
            qty.value = Math.max(1, Math.min(Number(qty.max), (Number(qty.value) || 1) + Number(step.dataset.delta)));
            qty.setCustomValidity('');
        }
        update();
    });
    form.addEventListener('input', update);
    form.addEventListener('submit', event => {
        if (!rows().length) { event.preventDefault(); announce('Add a product to the cart first.'); }
    });
    document.querySelector('#cart-search').addEventListener('input', event => {
        const search = event.target.value.trim().toLowerCase();
        let visible = 0;
        picker.querySelectorAll('.checkout-product').forEach(card => {
            card.hidden = !card.dataset.search.toLowerCase().includes(search);
            if (!card.hidden) visible++;
        });
        document.querySelector('#no-product-match').hidden = visible > 0;
    });
    update();
}
document.querySelector('#print-invoice')?.addEventListener('click', () => window.print());

// Block incompatible characters while typing or pasting; Java validates every submission too.
function validateNumericField(field) {
    const kind = field.dataset.valueType;
    if (!kind) return;
    field.setCustomValidity('');
    if (!field.value) return;
    const label = field.dataset.fieldLabel || 'This field';
    const pattern = kind === 'integer' ? /^[0-9]+$/ : /^[0-9]+(?:\.[0-9]{0,2})?$/;
    if (!pattern.test(field.value)) {
        field.setCustomValidity(kind === 'integer' ? `${label}: enter whole numbers only.` : `${label}: use no more than 2 decimal places.`);
    } else if (field.min && Number(field.value) < Number(field.min)) {
        field.setCustomValidity(`${label}: minimum ${field.min}.`);
    } else if (field.max && Number(field.value) > Number(field.max)) {
        field.setCustomValidity(`${label}: maximum ${Number(field.max).toLocaleString('en-IN')}.`);
    }
}
document.addEventListener('keydown', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field || event.ctrlKey || event.metaKey || event.altKey || event.key.length !== 1) return;
    if (field.dataset.valueType === 'integer' ? !/[0-9]/.test(event.key) : !/[0-9.]/.test(event.key)) event.preventDefault();
});
document.addEventListener('beforeinput', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field || !event.data || event.isComposing) return;
    if (field.dataset.valueType === 'integer' ? /[^0-9]/.test(event.data) : /[^0-9.]/.test(event.data)) event.preventDefault();
});
document.addEventListener('paste', event => {
    const field = event.target.closest('input[data-value-type]');
    if (!field) return;
    const pasted = event.clipboardData?.getData('text') || '';
    const pattern = field.dataset.valueType === 'integer' ? /^[0-9]+$/ : /^[0-9]+(?:\.[0-9]{0,2})?$/;
    if (!pattern.test(pasted.trim())) {
        event.preventDefault();
        field.setCustomValidity(field.dataset.valueType === 'integer' ? 'Paste a whole number without letters or decimal points.' : 'Paste an amount with up to 2 decimal places.');
        field.reportValidity();
    }
});
document.addEventListener('input', event => {
    if (event.target.matches('input[data-value-type]')) validateNumericField(event.target);
});
document.addEventListener('submit', event => {
    const fields = [...event.target.querySelectorAll('input[data-value-type]')];
    fields.forEach(validateNumericField);
    const invalid = fields.find(field => !field.checkValidity());
    if (invalid) { event.preventDefault(); invalid.reportValidity(); }
});
document.querySelectorAll('input[data-value-type]').forEach(validateNumericField);

const issueType = document.querySelector('#issue-type');
function updateIssueType() {
    const other = document.querySelector('#other-issue');
    if (!issueType || !other) return;
    const manual = issueType.value === 'Other';
    document.querySelector('#other-issue-label').hidden = !manual;
    other.required = manual;
    other.disabled = !manual;
}
issueType?.addEventListener('change', updateIssueType);
updateIssueType();

// Validate contact values while typing as well as on submission. Optional fields
// stay optional; a supplied phone/mobile must be exactly ten ASCII digits.
for (const field of document.querySelectorAll('input[data-contact-kind]')) {
    const error = document.createElement('small');
    error.id = `${field.id}-error`;
    error.className = 'contact-error';
    error.setAttribute('aria-live', 'polite');
    error.hidden = true;
    field.parentElement.append(error);
    field.setAttribute('aria-describedby', error.id);
    function validateContact() {
        let message = '';
        if (field.dataset.contactKind === 'phone') {
            field.value = field.value.replace(/[^0-9]/g, '').slice(0, 10);
            if (field.value && !/^[0-9]{10}$/.test(field.value)) message = 'Enter exactly 10 digits.';
        } else if (field.value && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(field.value)) {
            message = 'Enter a valid email address, such as name@example.com.';
        }
        field.setCustomValidity(message);
        field.setAttribute('aria-invalid', String(Boolean(message)));
        error.textContent = message;
        error.hidden = !message;
    }
    field.addEventListener('input', validateContact);
    field.addEventListener('change', validateContact);
    field.form?.addEventListener('submit', event => {
        validateContact();
        if (!field.checkValidity()) { event.preventDefault(); field.reportValidity(); }
    });
}
