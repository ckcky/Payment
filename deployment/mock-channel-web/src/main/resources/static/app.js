/*!
 * PaymentArch 演示界面共享行为层（Feature 024）
 * 规范：docs/design/DESIGN.md —— 与 design.css 配套，四页共用，消除重复实现。
 * 职责：顶栏渲染与高亮 / 日志抽屉（自动展开·pin·localStorage）/ log·esc·fmtMoney·api /
 *      toast / disclosure / 步骤条 / 栈探活。
 * 零外部依赖：纯原生 JS（NFR-001）。
 */
(function (global) {
  'use strict';

  var NAV = [
    { label: '门户', href: '/' },
    { label: '演示', href: '/demo' },
    { label: '收银台', href: null, title: '需从演示控制台下单后进入（携带 paymentNo）' },
    { label: '对账', href: '/audit.html' }
  ];
  var PIN_KEY = 'pa.drawer.pinned';

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function fmtMoney(minor, cur) {
    return '¥' + ((Number(minor) || 0) / 100).toFixed(2) + (cur ? ' ' + cur : '');
  }
  function now() {
    var d = new Date();
    return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) + ':' + ('0' + d.getSeconds()).slice(-2);
  }

  /* ---------------- 顶栏 ---------------- */
  function renderNav() {
    var path = location.pathname;
    var nav = el('nav', 'pa-nav');
    nav.appendChild(el('span', 'pa-nav__brand', 'PaymentArch'));
    var tabs = el('div', 'pa-nav__tabs');
    NAV.forEach(function (t) {
      if (t.href) {
        var a = el('a', 'pa-nav__tab', t.label);
        a.href = t.href;
        // /audit.html 与 /audit 均视为对账；/demo 为演示
        var on = (t.href === '/' && (path === '/' || path === '/index.html'))
          || (t.href.indexOf('audit') >= 0 && path.indexOf('audit') >= 0)
          || (t.href === '/demo' && path.indexOf('demo') >= 0)
          || (t.href.indexOf('cashier') >= 0 && path.indexOf('cashier') >= 0);
        if (on) a.className += ' is-on';
        tabs.appendChild(a);
      } else {
        var s = el('span', 'pa-nav__tab', t.label);
        s.style.color = 'var(--pa-ink-3)';
        s.style.cursor = 'default';
        if (t.title) s.title = t.title;
        tabs.appendChild(s);
      }
    });
    nav.appendChild(tabs);
    nav.appendChild(el('div', 'pa-nav__spacer'));
    var h = el('div', 'pa-nav__health');
    var dot = el('i', 'pa-dot');
    var txt = el('span', null, '探活中…');
    h.appendChild(dot); h.appendChild(txt);
    nav.appendChild(h);
    document.body.insertBefore(nav, document.body.firstChild);
    probe(dot, txt);
  }

  function probe(dot, txt) {
    fetch('/actuator/health', { cache: 'no-store' })
      .then(function (r) {
        var up = r.ok;
        dot.className = 'pa-dot ' + (up ? 'is-up' : 'is-down');
        txt.textContent = up ? '演示栈正常' : '演示栈未响应';
      })
      .catch(function () {
        dot.className = 'pa-dot is-down';
        txt.textContent = '演示栈未响应';
      });
  }

  /* ---------------- 日志抽屉 ---------------- */
  var drawer = null, logEl = null, lastEl = null, idleTimer = null;

  function buildDrawer() {
    logEl = document.getElementById('log');
    if (!logEl) return;
    drawer = el('aside', 'pa-drawer');
    var bar = el('div', 'pa-drawer__bar');
    var caret = el('span', 'caret', '▲');
    var title = el('b', null, '日志');
    lastEl = el('span', 'pa-drawer__last', '（暂无输出）');
    bar.appendChild(caret); bar.appendChild(title); bar.appendChild(lastEl);
    var pin = el('button', 'pa-drawer__pin', '固定');
    pin.type = 'button';
    bar.appendChild(pin);
    var body = el('div', 'pa-drawer__body');
    drawer.appendChild(bar); drawer.appendChild(body);
    document.body.appendChild(drawer);

    if (logEl.parentNode) logEl.parentNode.insertBefore(drawer, logEl);
    body.appendChild(logEl);
    logEl.className = (logEl.className ? logEl.className + ' ' : '') + 'pa-log';

    bar.addEventListener('click', function (e) {
      if (e.target === pin) return;
      drawer.classList.toggle('is-open');
    });
    pin.addEventListener('click', function (e) {
      e.stopPropagation();
      var pinned = drawer.classList.toggle('is-pinned');
      try { localStorage.setItem(PIN_KEY, pinned ? '1' : ''); } catch (x) { /* 隐私模式静默 */ }
      pin.textContent = pinned ? '已固定' : '固定';
      if (pinned) openDrawer(); else scheduleIdle();
    });
    try {
      if (localStorage.getItem(PIN_KEY) === '1') {
        drawer.classList.add('is-pinned'); pin.textContent = '已固定';
      }
    } catch (x) { /* 静默 */ }
  }

  function openDrawer() {
    if (!drawer) return;
    drawer.classList.add('is-open');
    scheduleIdle();
  }
  function scheduleIdle() {
    if (idleTimer) clearTimeout(idleTimer);
    if (!drawer || drawer.classList.contains('is-pinned')) return;
    idleTimer = setTimeout(function () { drawer.classList.remove('is-open'); }, 3000);
  }

  /** 写一行日志（自动展开抽屉）。cls: ok / err / warn / t */
  function log(msg, cls) {
    if (!logEl) return;
    var line = el('div', 'line' + (cls ? ' ' + cls : ''));
    line.innerHTML = '<span class="t">[' + now() + ']</span> ' + esc(msg);
    logEl.appendChild(line);
    logEl.scrollTop = logEl.scrollHeight;
    if (lastEl) lastEl.textContent = String(msg).split('\n').pop();
    openDrawer();
  }
  function clearLog() { if (logEl) logEl.innerHTML = ''; if (lastEl) lastEl.textContent = '（暂无输出）'; }

  /* ---------------- toast ---------------- */
  var toastBox = null;
  function toast(msg, kind) {
    if (!toastBox) {
      toastBox = el('div', 'pa-toasts');
      document.body.appendChild(toastBox);
    }
    var t = el('div', 'pa-toast' + (kind ? ' is-' + kind : ''), msg);
    toastBox.appendChild(t);
    // 错误类常驻，需人工确认（DESIGN.md §5.2）
    if (kind !== 'bad') {
      setTimeout(function () {
        t.classList.add('is-out');
        setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 220);
      }, 2500);
    }
    return t;
  }

  /* ---------------- 通用 API ---------------- */
  var apiBase = '';
  function api(method, path, body, headers) {
    var hd = Object.assign({}, headers || {});
    var opt = { method: method, headers: hd, cache: 'no-store' };
    if (body !== undefined) { hd['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch(apiBase + path, opt).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        return { ok: res.ok, status: res.status, data: data, text: text };
      });
    });
  }

  /* ---------------- 交互小工具 ---------------- */
  /** disclosure：点击 .pa-disc__head 切换 .is-open */
  function bindDisclosures(root) {
    (root || document).querySelectorAll('.pa-disc__head').forEach(function (h) {
      h.addEventListener('click', function () {
        h.closest('.pa-disc').classList.toggle('is-open');
      });
    });
  }
  function toggle(node, cls) {
    if (!node) return false;
    var on = node.classList.toggle(cls || 'is-open');
    return on;
  }
  /** 步骤条：setStep(n) 把第 n 步（1 基）置为当前，之前的置为已完成 */
  function setStep(n) {
    var steps = document.querySelectorAll('.pa-step');
    steps.forEach(function (s, i) {
      s.classList.toggle('is-done', i + 1 < n);
      s.classList.toggle('is-on', i + 1 === n);
    });
  }
  /** 数字滚动（rAF 插值，400ms） */
  function rollTo(node, to, fmt) {
    if (!node) return;
    var from = Number(node.getAttribute('data-val') || 0) || 0;
    var t0 = 0, dur = 400;
    node.setAttribute('data-val', String(to));
    function step(ts) {
      if (!t0) t0 = ts;
      var p = Math.min(1, (ts - t0) / dur);
      var v = from + (to - from) * p;
      node.textContent = (fmt || function (x) { return String(Math.round(x)); })(v);
      if (p < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  global.PA = {
    esc: esc, fmtMoney: fmtMoney, log: log, clearLog: clearLog, toast: toast,
    api: api, setApiBase: function (b) { apiBase = b || ''; },
    bindDisclosures: bindDisclosures, toggle: toggle, setStep: setStep, rollTo: rollTo,
    openDrawer: openDrawer, el: el, now: now
  };

  /* ---------------- 初始化 ---------------- */
  function init() {
    renderNav();
    buildDrawer();
    bindDisclosures(document);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
