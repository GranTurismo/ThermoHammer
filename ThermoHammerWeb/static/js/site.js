/* ThermoHammer site. Everything here is an extra: the pages read fine without it. */
(function () {
  'use strict';
  var doc = document;
  var $ = function (sel, root) { return (root || doc).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || doc).querySelectorAll(sel)); };
  doc.documentElement.classList.add('js');

  /* ── charts draw themselves when they come into view ─────────────────────────────────────────── */
  var drawn = $$('[data-draw]');
  if ('IntersectionObserver' in window && drawn.length) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { e.target.classList.add('reveal'); io.unobserve(e.target); }
      });
    }, { threshold: 0.25 });
    drawn.forEach(function (el) { io.observe(el); });
    // never leave a chart undrawn, whatever the browser makes of the observer
    setTimeout(function () { drawn.forEach(function (el) { el.classList.add('reveal'); }); }, 6000);
  } else {
    drawn.forEach(function (el) { el.classList.add('reveal'); });
  }

  /* ── home: switch between the real runs in the hero ──────────────────────────────────────────── */
  var hero = $('[data-hero]');
  if (hero) {
    var runs = $$('[data-hero-run]', hero), tabs = $$('[data-hero-tab]', hero), timer = null, current = 0;
    var show = function (i) {
      current = i;
      runs.forEach(function (r, n) { r.classList.toggle('is-on', n === i); });
      tabs.forEach(function (t, n) { t.setAttribute('aria-pressed', n === i ? 'true' : 'false'); });
    };
    var stop = function () { if (timer) { clearInterval(timer); timer = null; } };
    tabs.forEach(function (t, n) {
      t.addEventListener('click', function () { stop(); show(n); });
    });
    var calm = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!calm && runs.length > 1) {
      timer = setInterval(function () {
        if (doc.hidden) return;
        var box = hero.getBoundingClientRect();
        if (box.bottom < 0 || box.top > window.innerHeight) return;
        show((current + 1) % runs.length);
      }, 6500);
      hero.addEventListener('pointerdown', stop);
      hero.addEventListener('focusin', stop);
    }
  }

  /* ── boards: search, filters, ranking ────────────────────────────────────────────────────────── */
  var pressed = function (group) {
    var on = $('[aria-pressed="true"]', group);
    return on ? on.getAttribute('data-value') : '';
  };
  var press = function (group, value) {
    $$('button', group).forEach(function (b) {
      b.setAttribute('aria-pressed', b.getAttribute('data-value') === value ? 'true' : 'false');
    });
  };
  var words = function (input) {
    return (input && input.value || '').toLowerCase().split(/\s+/).filter(Boolean);
  };
  var matches = function (row, terms) {
    var name = row.getAttribute('data-name') || '';
    return terms.every(function (t) { return name.indexOf(t) !== -1; });
  };

  var board = $('[data-board]');
  if (board) {
    var kind = board.getAttribute('data-board');
    var list = $('[data-rows]', board);
    var rows = $$('[data-row]', board);
    var search = $('[data-search]', board);
    var empty = $('[data-empty]', board);
    var filters = {};
    $$('[data-filter]', board).forEach(function (g) { filters[g.getAttribute('data-filter')] = g; });
    var sort = $('[data-sort]', board);
    var best = $('[data-best]', board);
    var count = $('[data-count]', board);

    var apply = function () {
      var terms = words(search), shown = 0, seen = {};
      var key = sort ? pressed(sort) : null;
      var ordered = rows.slice();
      if (kind === 'bench' && key) {
        ordered.sort(function (a, b) { return (+b.getAttribute('data-' + key)) - (+a.getAttribute('data-' + key)); });
      }
      ordered.forEach(function (row) {
        var ok = matches(row, terms);
        Object.keys(filters).forEach(function (name) {
          var want = pressed(filters[name]);
          if (want && row.getAttribute('data-' + name) !== want) ok = false;
        });
        if (ok && best && best.getAttribute('aria-pressed') === 'true') {
          var device = row.getAttribute('data-device');
          if (seen[device]) ok = false; else seen[device] = true;
        }
        row.hidden = !ok;
        row.removeAttribute('data-first');
        if (ok) {
          shown += 1;
          var rank = $('[data-rank]', row);
          if (rank) rank.textContent = '#' + shown;
          if (shown === 1) row.setAttribute('data-first', '');
          if (kind === 'bench' && key) {
            var score = $('[data-score]', row), label = $('[data-score-label]', row);
            var on = $('[aria-pressed="true"]', sort);
            if (score) score.textContent = (+row.getAttribute('data-' + key)).toLocaleString('en-US');
            if (label && on) label.textContent = on.getAttribute('data-label');
          }
        }
        if (list && kind === 'bench') list.appendChild(row);
      });
      if (kind === 'devices') {
        $$('[data-group]', board).forEach(function (g) {
          g.hidden = !$$('[data-row]', g).some(function (r) { return !r.hidden; });
        });
      }
      if (empty) empty.hidden = shown !== 0;
      if (count) count.textContent = shown;
    };

    Object.keys(filters).forEach(function (name) {
      var g = filters[name];
      g.addEventListener('click', function (e) {
        var b = e.target.closest('button');
        if (!b) return;
        press(g, b.getAttribute('data-value'));
        apply();
      });
      // the stress board opens on the standard test; without JavaScript every run is listed
      var first = g.getAttribute('data-default');
      if (first && $('[data-value="' + first + '"]', g)) press(g, first);
    });
    if (sort) sort.addEventListener('click', function (e) {
      var b = e.target.closest('button');
      if (!b) return;
      press(sort, b.getAttribute('data-value'));
      apply();
    });
    if (best) {
      best.setAttribute('aria-pressed', 'true');
      best.addEventListener('click', function () {
        best.setAttribute('aria-pressed', best.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
        apply();
      });
    }
    if (search) {
      var q = new URLSearchParams(location.search).get('q');
      if (q) search.value = q;
      search.addEventListener('input', apply);
    }
    apply();
  }

  /* ── compare: two devices → their page ───────────────────────────────────────────────────────── */
  var picker = $('[data-picker]');
  if (picker) {
    var data = {}, msg = $('[data-picker-msg]', picker);
    try {
      JSON.parse($('[data-picker-data]', picker).textContent).forEach(function (d) { data[d.s] = d; });
    } catch (err) { /* the links below the form still work */ }
    var a = $('[data-pick="a"]', picker), b = $('[data-pick="b"]', picker);
    var params = new URLSearchParams(location.search);
    if (params.get('a') && data[params.get('a')]) a.value = params.get('a');
    if (params.get('b') && data[params.get('b')]) b.value = params.get('b');
    var check = function () {
      var x = data[a.value], y = data[b.value];
      if (!x || !y) return 'Choose two devices.';
      if (x.s === y.s) return 'Choose two different devices.';
      if (!((x.b && y.b) || (x.t && y.t))) {
        var onlyBench = x.b && !x.t ? x : y.b && !y.t ? y : null;
        return onlyBench
          ? onlyBench.n + ' has a benchmark run but no stress run yet, and the other device has only stress runs.'
          : 'These two have no test in common yet.';
      }
      return '';
    };
    picker.addEventListener('submit', function (e) {
      e.preventDefault();
      var problem = check();
      msg.textContent = problem;
      if (problem) return;
      var pair = [a.value, b.value].sort();
      location.href = picker.getAttribute('data-base') + pair[0] + '-vs-' + pair[1] + '/';
    });
    [a, b].forEach(function (el) { el.addEventListener('change', function () { msg.textContent = a.value && b.value ? check() : ''; }); });
  }

  /* ── share ───────────────────────────────────────────────────────────────────────────────────── */
  $$('[data-share]').forEach(function (box) {
    var title = box.getAttribute('data-title'), text = box.getAttribute('data-text');
    var native = $('[data-share-native]', box), copy = $('[data-copy]', box);
    if (native && navigator.share) {
      native.hidden = false;
      native.addEventListener('click', function () {
        navigator.share({ title: title, text: text, url: location.href }).catch(function () {});
      });
    }
    if (copy) copy.addEventListener('click', function () {
      var done = function () {
        var was = copy.textContent;
        copy.textContent = 'Copied';
        setTimeout(function () { copy.textContent = was; }, 1600);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(location.href).then(done, function () {});
      } else {
        var t = doc.createElement('textarea');
        t.value = location.href; doc.body.appendChild(t); t.select();
        try { doc.execCommand('copy'); done(); } catch (err) { /* nothing to do */ }
        doc.body.removeChild(t);
      }
    });
  });

  /* ── "3 hours ago" ───────────────────────────────────────────────────────────────────────────── */
  $$('time[data-ago]').forEach(function (el) {
    var then = Date.parse(el.getAttribute('datetime'));
    if (!then) return;
    var mins = Math.round((Date.now() - then) / 60000);
    if (mins < 0) return;
    var text = mins < 2 ? 'just now' : mins < 60 ? mins + ' minutes ago' : mins < 120 ? 'an hour ago'
      : mins < 1440 ? Math.round(mins / 60) + ' hours ago' : mins < 2880 ? 'yesterday' : mins < 43200 ? Math.round(mins / 1440) + ' days ago' : null;
    if (text) { el.title = el.textContent; el.textContent = text; }
  });

  /* ── the store this visitor can use comes first ──────────────────────────────────────────────── */
  var ua = navigator.userAgent || '';
  var mine = /Android/i.test(ua) ? 'play' : /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1) ? 'appstore' : null;
  if (mine) $$('[data-store="' + mine + '"]').forEach(function (el) { el.classList.add('is-yours'); });
})();
