/*
 * mdchart - charts from a Markdown-shaped fence.
 *
 * Compiles a ```chart fence into inline SVG. No dependencies, no network, no canvas:
 * SVG scales, prints, and can be themed from CSS custom properties, which is what lets a
 * chart follow the editor's light/dark switch with no re-render. Mermaid and PlantUML
 * cannot do that here - they bake their colours into the SVG they emit, which is why the
 * diagram plate in this app is pinned to a white background. A chart drawn here is not.
 *
 * The syntax is meant to survive being read as plain text. A reader looking at the raw
 * Markdown should see the numbers, not markup:
 *
 *     ```chart
 *     type: column
 *     title: Requests handled
 *     x: Mon, Tue, Wed
 *     ---
 *     auth    | 120, 140, 131
 *     gateway | 340, 352, 377
 *     ```
 *
 * Rules that are enforced rather than documented, because a chart that breaks them is
 * misleading rather than ugly:
 *
 *   - Categorical hues are assigned in a fixed order and never cycled. A ninth series is
 *     refused with a message, not given a generated colour.
 *   - No dual axis, ever. Two measures of different scale are two charts.
 *   - One series gets one colour; bar length is never double-encoded as hue.
 *   - Every chart carries a table view, so no value is reachable only by hovering.
 *
 * The palette is the validated default from the house data-viz reference, checked with
 * its own validator against this app's plate surfaces (#EFF3F7 light, #131C26 dark)
 * rather than the reference surfaces - contrast is only meaningful against the surface
 * the chart actually renders on. Both modes pass; four light-mode hues sit below 3:1,
 * which is why direct labels and the table view are not optional here.
 *
 * Colours are read from CSS custom properties (--mdc-series-1 ...), declared in the
 * preview stylesheet for both themes. This file never hard-codes a hue.
 */
(function (global) {
  "use strict";

  /* ---------------------------------------------------------------- constants */

  var SERIES_SLOTS = 8;

  /* Cap the mark, never fill the slot: the leftover band is the air that keeps a chart
     from reading as a solid block. */
  var MAX_BAR = 24;
  var BAR_RADIUS = 4;
  var LINE_WIDTH = 2;
  var MARKER_R = 4;
  var SURFACE_GAP = 2;

  var PAD = { top: 18, right: 18, bottom: 34, left: 48 };

  var TYPES = ["bar", "column", "line", "area", "pie", "donut", "stat"];

  /* ------------------------------------------------------------------ parsing */

  /**
   * Splits a fence into its settings block and its data rows.
   *
   * A line of three or more dashes separates the two. Without one, every line that looks
   * like "key: value" before the first data row is taken as a setting - so the simplest
   * useful chart is a bare list of "label | value" with no header at all.
   */
  function parse(source) {
    var lines = String(source == null ? "" : source).split(/\r?\n/);
    var settings = {};
    var rows = [];
    var inData = false;

    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var trimmed = line.trim();
      if (!trimmed || trimmed.charAt(0) === "#") continue;

      if (!inData && /^-{3,}$/.test(trimmed)) { inData = true; continue; }

      if (!inData && trimmed.indexOf("|") < 0) {
        var colon = trimmed.indexOf(":");
        if (colon > 0) {
          settings[trimmed.slice(0, colon).trim().toLowerCase()] =
            trimmed.slice(colon + 1).trim();
          continue;
        }
      }
      inData = true;
      rows.push(trimmed);
    }
    return { settings: settings, rows: rows };
  }

  function splitList(text) {
    if (!text) return [];
    return String(text).split(",").map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
  }

  /** A number, or NaN. Accepts thousands separators and a trailing % so units can be typed. */
  function num(text) {
    if (text == null) return NaN;
    var cleaned = String(text).replace(/,/g, "").replace(/%$/, "").trim();
    if (cleaned === "") return NaN;
    return Number(cleaned);
  }

  /**
   * Turns rows into series.
   *
   * One value per row is a single series over categories - the row label is the category.
   * Several values per row is one series per row, plotted against the x: setting.
   */
  function readData(parsed) {
    var rows = parsed.rows;
    var xs = splitList(parsed.settings.x);
    var series = [];
    var categories = [];
    var multi = false;

    for (var i = 0; i < rows.length; i++) {
      var bar = rows[i].indexOf("|");
      var name = bar >= 0 ? rows[i].slice(0, bar).trim() : "";
      var rest = bar >= 0 ? rows[i].slice(bar + 1) : rows[i];
      var values = splitList(rest).map(num);
      if (!values.length) continue;
      if (values.length > 1) multi = true;
      series.push({ name: name, values: values });
    }

    if (!multi) {
      /* Every row is one category of a single series. The row labels become the axis,
         and the chart gets exactly one colour - length already encodes magnitude, so
         colouring each bar differently would spend the only free channel saying it twice. */
      categories = series.map(function (s) { return s.name; });
      var flat = series.map(function (s) { return s.values[0]; });
      series = [{ name: parsed.settings.series || "", values: flat }];
    } else {
      var longest = 0;
      series.forEach(function (s) { longest = Math.max(longest, s.values.length); });
      categories = xs.length ? xs : countUp(longest);
    }
    return { series: series, categories: categories, singleSeries: !multi };
  }

  function countUp(n) {
    var out = [];
    for (var i = 1; i <= n; i++) out.push(String(i));
    return out;
  }

  /* ------------------------------------------------------------------- format */

  function fmt(value) {
    if (!isFinite(value)) return "";
    var abs = Math.abs(value);
    if (abs >= 1e9) return trimZero(value / 1e9) + "B";
    if (abs >= 1e6) return trimZero(value / 1e6) + "M";
    if (abs >= 1e4) return trimZero(value / 1e3) + "K";
    return String(Math.round(value * 100) / 100).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }

  function trimZero(n) {
    return String(Math.round(n * 10) / 10);
  }

  /** Axis ticks on clean numbers, so the reader is not decoding 1387.4. */
  function ticks(min, max, count) {
    if (min === max) { max = min + 1; }
    var span = max - min;
    var raw = span / Math.max(1, count);
    var mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
    var step = mag;
    [1, 2, 2.5, 5, 10].some(function (m) {
      if (mag * m >= raw) { step = mag * m; return true; }
      return false;
    });
    var start = Math.floor(min / step) * step;
    var out = [];
    for (var v = start; v <= max + step * 0.001; v += step) {
      out.push(Math.round(v * 1e6) / 1e6);
    }
    return out;
  }

  function esc(text) {
    return String(text == null ? "" : text)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  /** Rough text width, for deciding whether a label fits before drawing it. */
  function textWidth(text, size) {
    return String(text).length * size * 0.56;
  }

  function color(i) { return "var(--mdc-series-" + ((i % SERIES_SLOTS) + 1) + ")"; }

  /* -------------------------------------------------------------------- build */

  /**
   * The SVG is drawn at the width it will occupy, not scaled into it.
   *
   * <p>A fixed viewBox stretched to 100% scales the text with the geometry: an 11px label
   * in a 720-wide viewBox rendered into a 430px pane comes out near 7px, which is the
   * difference between a chart and a picture of one. Laying out at the measured width
   * keeps every label at the size it was specified in.
   */
  function svgOpen(w, h, title) {
    return '<svg class="mdc-svg" viewBox="0 0 ' + w + " " + h + '" width="' + w + '" '
      + 'height="' + h + '" role="img" '
      + 'aria-label="' + esc(title || "chart") + '">';
  }

  /**
   * A legend for two or more series, never for one.
   *
   * One series has one colour, and the title already says what is plotted; a box with a
   * single swatch restates it and costs space. For two or more the legend is the
   * dependable identity channel - direct labels supplement it, they do not replace it.
   */
  function legend(series) {
    if (series.length < 2) return "";
    var items = series.map(function (s, i) {
      return '<span class="mdc-key"><span class="mdc-swatch" style="background:'
        + color(i) + '"></span>' + esc(s.name || "series " + (i + 1)) + "</span>";
    }).join("");
    return '<div class="mdc-legend">' + items + "</div>";
  }

  /**
   * The table twin.
   *
   * Every value stays reachable without hovering, which is what keeps the tooltip an
   * enhancement rather than the only way to read the chart - and it is the relief for the
   * light-mode hues that sit below 3:1 against the plate.
   */
  function table(data, unit) {
    var head = "<tr><th>" + (data.singleSeries ? "Item" : "Series") + "</th>"
      + data.categories.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("")
      + "</tr>";
    var body = data.series.map(function (s, i) {
      var cells = data.categories.map(function (_, j) {
        return "<td>" + esc(fmt(s.values[j])) + "</td>";
      }).join("");
      return "<tr><th>" + esc(s.name || "series " + (i + 1)) + "</th>" + cells + "</tr>";
    }).join("");

    if (data.singleSeries) {
      var rows = data.categories.map(function (c, j) {
        return "<tr><th>" + esc(c) + "</th><td>"
          + esc(fmt(data.series[0].values[j])) + "</td></tr>";
      }).join("");
      return '<details class="mdc-table"><summary>Table' + (unit ? " (" + esc(unit) + ")" : "")
        + "</summary><table><tr><th>Item</th><th>Value</th></tr>" + rows + "</table></details>";
    }
    return '<details class="mdc-table"><summary>Table' + (unit ? " (" + esc(unit) + ")" : "")
      + "</summary><table>" + head + body + "</table></details>";
  }

  /* ----------------------------------------------------------- bar and column */

  function drawBars(data, opts, vertical, width) {
    var cats = data.categories;
    var series = data.series;
    var groups = cats.length;
    var perGroup = series.length;

    var all = [];
    series.forEach(function (s) {
      s.values.forEach(function (v) { if (isFinite(v)) all.push(v); });
    });
    var max = Math.max.apply(null, all.concat([0]));
    var min = Math.min.apply(null, all.concat([0]));
    var scaleTicks = ticks(min, max, 4);
    var lo = scaleTicks[0];
    var hi = scaleTicks[scaleTicks.length - 1];

    var labelRoom = vertical ? PAD.left : Math.min(190, longestLabel(cats, 12) + 14);
    var pad = {
      top: PAD.top,
      right: vertical ? PAD.right : 54,
      bottom: vertical ? PAD.bottom : 26,
      left: vertical ? labelRoom : labelRoom
    };

    var height = vertical
      ? Math.max(180, Math.min(340, 40 + groups * 34))
      : Math.max(120, pad.top + pad.bottom + groups * (perGroup * 22 + 16));

    var plotW = width - pad.left - pad.right;
    var plotH = height - pad.top - pad.bottom;
    var svg = [svgOpen(width, height, opts.title)];

    var pos = function (v) {
      var t = (v - lo) / (hi - lo || 1);
      return vertical ? pad.top + plotH - t * plotH : pad.left + t * plotW;
    };
    var zero = pos(Math.max(lo, Math.min(0, hi)));

    /* Gridlines: solid hairlines one step off the surface. Dashes read as a threshold. */
    scaleTicks.forEach(function (t) {
      var p = pos(t);
      svg.push(vertical
        ? '<line class="mdc-grid" x1="' + pad.left + '" y1="' + p + '" x2="'
            + (width - pad.right) + '" y2="' + p + '"/>'
        : '<line class="mdc-grid" x1="' + p + '" y1="' + pad.top + '" x2="' + p
            + '" y2="' + (pad.top + plotH) + '"/>');
      svg.push(vertical
        ? '<text class="mdc-tick" x="' + (pad.left - 8) + '" y="' + (p + 4)
            + '" text-anchor="end">' + esc(fmt(t)) + "</text>"
        : '<text class="mdc-tick" x="' + p + '" y="' + (pad.top + plotH + 16)
            + '" text-anchor="middle">' + esc(fmt(t)) + "</text>");
    });

    var band = (vertical ? plotW : plotH) / Math.max(1, groups);
    var thick = Math.min(MAX_BAR, Math.max(6, (band - SURFACE_GAP * (perGroup + 1)) / perGroup));

    for (var g = 0; g < groups; g++) {
      var bandStart = (vertical ? pad.left : pad.top) + g * band;
      var used = perGroup * thick + (perGroup - 1) * SURFACE_GAP;
      var start = bandStart + (band - used) / 2;

      for (var s = 0; s < perGroup; s++) {
        var value = series[s].values[g];
        if (!isFinite(value)) continue;
        var at = start + s * (thick + SURFACE_GAP);
        var end = pos(value);
        var label = fmt(value);
        var name = series[s].name || cats[g];

        if (vertical) {
          var top = Math.min(end, zero);
          var h = Math.abs(end - zero);
          svg.push(roundedBar(at, top, thick, h, true, value >= 0, color(s))
            + "<title>" + esc(name + " — " + label) + "</title></path>");
          svg.push('<text class="mdc-value" x="' + (at + thick / 2) + '" y="'
            + (top - 6) + '" text-anchor="middle">' + esc(label) + "</text>");
        } else {
          var left = Math.min(end, zero);
          var w = Math.abs(end - zero);
          svg.push(roundedBar(left, at, w, thick, false, value >= 0, color(s))
            + "<title>" + esc(name + " — " + label) + "</title></path>");
          svg.push('<text class="mdc-value" x="' + (Math.max(end, zero) + 6) + '" y="'
            + (at + thick / 2 + 4) + '">' + esc(label) + "</text>");
        }
      }

      var catLabel = cats[g];
      if (vertical) {
        svg.push('<text class="mdc-cat" x="' + (bandStart + band / 2) + '" y="'
          + (pad.top + plotH + 18) + '" text-anchor="middle">' + esc(catLabel) + "</text>");
      } else {
        svg.push('<text class="mdc-cat" x="' + (pad.left - 10) + '" y="'
          + (bandStart + band / 2 + 4) + '" text-anchor="end">' + esc(catLabel) + "</text>");
      }
    }

    svg.push("</svg>");
    return svg.join("");
  }

  function longestLabel(list, size) {
    var w = 0;
    list.forEach(function (t) { w = Math.max(w, textWidth(t, size)); });
    return w;
  }

  /**
   * A bar with its data-end rounded and its baseline end square.
   *
   * Rounding both ends detaches the bar from the axis and makes short bars read as pills;
   * the square end is what keeps it anchored to the baseline it grows from.
   */
  function roundedBar(x, y, w, h, vertical, positive, fill) {
    var r = Math.min(BAR_RADIUS, vertical ? w / 2 : h / 2, vertical ? h : w);
    var d;
    if (vertical) {
      d = positive
        ? "M" + x + "," + (y + h) + "V" + (y + r) + "q0," + -r + " " + r + "," + -r
          + "h" + (w - 2 * r) + "q" + r + ",0 " + r + "," + r + "V" + (y + h) + "z"
        : "M" + x + "," + y + "V" + (y + h - r) + "q0," + r + " " + r + "," + r
          + "h" + (w - 2 * r) + "q" + r + ",0 " + r + "," + -r + "V" + y + "z";
    } else {
      d = positive
        ? "M" + x + "," + y + "h" + (w - r) + "q" + r + ",0 " + r + "," + r
          + "v" + (h - 2 * r) + "q0," + r + " " + -r + "," + r + "H" + x + "z"
        : "M" + (x + w) + "," + y + "h" + -(w - r) + "q" + -r + ",0 " + -r + "," + r
          + "v" + (h - 2 * r) + "q0," + r + " " + r + "," + r + "H" + (x + w) + "z";
    }
    return '<path class="mdc-bar" fill="' + fill + '" d="' + d + '">';
  }

  /* ------------------------------------------------------------ line and area */

  function drawLines(data, opts, filled, width) {
    var cats = data.categories;
    var series = data.series;
    var height = 300;
    var pad = { top: PAD.top, right: 64, bottom: PAD.bottom, left: PAD.left };
    var plotW = width - pad.left - pad.right;
    var plotH = height - pad.top - pad.bottom;

    var all = [];
    series.forEach(function (s) {
      s.values.forEach(function (v) { if (isFinite(v)) all.push(v); });
    });
    var scaleTicks = ticks(Math.min.apply(null, all.concat([0])),
      Math.max.apply(null, all.concat([0])), 4);
    var lo = scaleTicks[0];
    var hi = scaleTicks[scaleTicks.length - 1];

    var x = function (i) {
      return pad.left + (cats.length < 2 ? plotW / 2 : (i / (cats.length - 1)) * plotW);
    };
    var y = function (v) { return pad.top + plotH - ((v - lo) / (hi - lo || 1)) * plotH; };

    var svg = [svgOpen(width, height, opts.title)];

    scaleTicks.forEach(function (t) {
      svg.push('<line class="mdc-grid" x1="' + pad.left + '" y1="' + y(t) + '" x2="'
        + (width - pad.right) + '" y2="' + y(t) + '"/>');
      svg.push('<text class="mdc-tick" x="' + (pad.left - 8) + '" y="' + (y(t) + 4)
        + '" text-anchor="end">' + esc(fmt(t)) + "</text>");
    });

    cats.forEach(function (c, i) {
      svg.push('<text class="mdc-cat" x="' + x(i) + '" y="' + (pad.top + plotH + 18)
        + '" text-anchor="middle">' + esc(c) + "</text>");
    });

    series.forEach(function (s, si) {
      var pts = [];
      s.values.forEach(function (v, i) {
        if (isFinite(v)) pts.push([x(i), y(v), v, i]);
      });
      if (!pts.length) return;

      if (filled) {
        /* A wash, never a saturated block: the line carries the series, the fill only
           says "area under it". */
        var area = "M" + pts[0][0] + "," + (pad.top + plotH)
          + pts.map(function (p) { return "L" + p[0] + "," + p[1]; }).join("")
          + "L" + pts[pts.length - 1][0] + "," + (pad.top + plotH) + "z";
        svg.push('<path class="mdc-area" fill="' + color(si) + '" d="' + area + '"/>');
      }

      svg.push('<path class="mdc-line" stroke="' + color(si) + '" d="M'
        + pts.map(function (p) { return p[0] + "," + p[1]; }).join("L") + '"/>');

      pts.forEach(function (p) {
        svg.push('<circle class="mdc-dot" fill="' + color(si) + '" cx="' + p[0]
          + '" cy="' + p[1] + '" r="' + MARKER_R + '"><title>'
          + esc((s.name || "value") + " — " + cats[p[3]] + ": " + fmt(p[2]))
          + "</title></circle>");
      });

      /* End labels only while they stay attached to their own line. Past four series
         they converge and nudging them apart reads as noise - the legend carries it. */
      if (series.length <= 4) {
        var last = pts[pts.length - 1];
        svg.push('<text class="mdc-value" x="' + (last[0] + 8) + '" y="' + (last[1] + 4)
          + '">' + esc(fmt(last[2])) + "</text>");
      }
    });

    svg.push("</svg>");
    return svg.join("");
  }

  /* ------------------------------------------------------------- pie and donut */

  function drawPie(data, opts, donut) {
    var values = data.series[0].values;
    var labels = data.categories;
    var total = values.reduce(function (a, b) { return a + (isFinite(b) ? b : 0); }, 0);
    if (total <= 0) return errorSvg("every slice is zero");

    var size = 300;
    var cx = size / 2;
    var cy = size / 2;
    var outer = 110;
    var inner = donut ? 62 : 0;
    var svg = [svgOpen(size, size, opts.title)];
    var angle = -Math.PI / 2;

    values.forEach(function (v, i) {
      if (!isFinite(v) || v <= 0) return;
      var sweep = (v / total) * Math.PI * 2;
      /* The 2px separation is a gap in the surface, not a stroke: a stroke would add ink
         that is not data. Converted to radians at the outer edge so it stays 2px wide. */
      var gap = Math.min(sweep / 3, SURFACE_GAP / outer);
      var a0 = angle + gap / 2;
      var a1 = angle + sweep - gap / 2;
      svg.push('<path class="mdc-slice" fill="' + color(i) + '" d="'
        + arc(cx, cy, outer, inner, a0, a1) + '"><title>'
        + esc(labels[i] + " — " + fmt(v) + " (" + Math.round((v / total) * 100) + "%)")
        + "</title></path>");
      angle += sweep;
    });

    if (donut) {
      svg.push('<text class="mdc-hero" x="' + cx + '" y="' + (cy + 6)
        + '" text-anchor="middle">' + esc(fmt(total)) + "</text>");
    }
    svg.push("</svg>");
    return svg.join("");
  }

  function arc(cx, cy, outer, inner, a0, a1) {
    var large = a1 - a0 > Math.PI ? 1 : 0;
    var x0 = cx + outer * Math.cos(a0), y0 = cy + outer * Math.sin(a0);
    var x1 = cx + outer * Math.cos(a1), y1 = cy + outer * Math.sin(a1);
    if (!inner) {
      return "M" + cx + "," + cy + "L" + x0 + "," + y0 + "A" + outer + "," + outer
        + " 0 " + large + " 1 " + x1 + "," + y1 + "z";
    }
    var xi1 = cx + inner * Math.cos(a1), yi1 = cy + inner * Math.sin(a1);
    var xi0 = cx + inner * Math.cos(a0), yi0 = cy + inner * Math.sin(a0);
    return "M" + x0 + "," + y0 + "A" + outer + "," + outer + " 0 " + large + " 1 "
      + x1 + "," + y1 + "L" + xi1 + "," + yi1 + "A" + inner + "," + inner
      + " 0 " + large + " 0 " + xi0 + "," + yi0 + "z";
  }

  /* --------------------------------------------------------------- stat tile */

  /**
   * Sometimes the answer is not a chart.
   *
   * A single number plotted as one bar is a bar chart that says less than the number
   * would on its own, so "type: stat" is offered and a one-value chart suggests it.
   */
  function drawStat(data, opts) {
    var value = data.series[0].values[0];
    var label = opts.title || data.categories[0] || "";
    var delta = opts.delta;
    var parts = ['<div class="mdc-stat">'];
    if (label) parts.push('<p class="mdc-stat-label">' + esc(label) + "</p>");
    parts.push('<p class="mdc-stat-value">' + esc(fmt(value))
      + (opts.unit ? '<span class="mdc-stat-unit">' + esc(opts.unit) + "</span>" : "")
      + "</p>");
    if (delta) {
      var down = /^-/.test(delta.trim());
      parts.push('<p class="mdc-stat-delta ' + (down ? "is-down" : "is-up") + '">'
        + esc(delta) + "</p>");
    }
    parts.push("</div>");
    return parts.join("");
  }

  /* ------------------------------------------------------------------- errors */

  function errorBlock(message, source) {
    return '<div class="mdc-error"><p class="mdc-error-msg">' + esc(message)
      + '</p><pre class="mdc-error-src">' + esc(source) + "</pre></div>";
  }

  function errorSvg(message) {
    return '<div class="mdc-error"><p class="mdc-error-msg">' + esc(message) + "</p></div>";
  }

  /* -------------------------------------------------------------------- render */

  /**
   * @param width the pixel width to lay out for; defaults to a readable page column when
   *              the caller cannot measure one (rendering outside a document, say).
   */
  function render(source, width) {
    width = Math.max(320, Math.round(width || 680));
    var parsed = parse(source);
    var opts = parsed.settings;
    var type = (opts.type || "").toLowerCase();

    if (!type) return errorBlock("chart needs a type: " + TYPES.join(", "), source);
    if (TYPES.indexOf(type) < 0) {
      return errorBlock('unknown chart type "' + type + '" - use one of: '
        + TYPES.join(", "), source);
    }

    var data = readData(parsed);
    if (!data.series.length || !data.categories.length) {
      return errorBlock("chart has no data rows", source);
    }

    /* Refused rather than cycled. A ninth hue is indistinguishable from one already in
       use, so the honest answer is to say so and let the author fold or facet. */
    if (data.series.length > SERIES_SLOTS) {
      return errorBlock("a chart carries at most " + SERIES_SLOTS + " series; this has "
        + data.series.length + ". Fold the tail into one row, or split into two charts.",
        source);
    }

    var body;
    if (type === "stat") {
      body = drawStat(data, opts);
      return '<figure class="mdc-figure mdc-figure-stat">' + body + "</figure>";
    }

    if (type === "pie" || type === "donut") {
      if (!data.singleSeries) {
        return errorBlock("a pie takes one value per row; for several series use "
          + '"type: column"', source);
      }
      if (data.categories.length > 6) {
        return errorBlock("a pie is unreadable past 6 slices (" + data.categories.length
          + " given) - use \"type: bar\"", source);
      }
      body = drawPie(data, opts, type === "donut");
    } else if (type === "line" || type === "area") {
      body = drawLines(data, opts, type === "area", width);
    } else {
      body = drawBars(data, opts, type === "column", width);
    }

    var pieLegend = (type === "pie" || type === "donut")
      ? legendFromCategories(data.categories)
      : legend(data.series);

    return '<figure class="mdc-figure">'
      + (opts.title ? '<figcaption class="mdc-title">' + esc(opts.title)
          + (opts.unit ? ' <span class="mdc-unit">(' + esc(opts.unit) + ")</span>" : "")
          + "</figcaption>" : "")
      + pieLegend
      + '<div class="mdc-plot">' + body + "</div>"
      + table(data, opts.unit)
      + "</figure>";
  }

  /** A pie's slices are its categories, so its legend is built from those, not series. */
  function legendFromCategories(cats) {
    var items = cats.map(function (c, i) {
      return '<span class="mdc-key"><span class="mdc-swatch" style="background:'
        + color(i) + '"></span>' + esc(c) + "</span>";
    }).join("");
    return '<div class="mdc-legend">' + items + "</div>";
  }

  /**
   * Replaces every un-rendered chart block on the page.
   *
   * Marked done rather than removed, so a re-render of the same DOM does not compile a
   * chart twice, and so a failure leaves the source visible instead of a blank plate.
   */
  function renderAll(root) {
    var scope = root || document;
    var blocks = scope.querySelectorAll("pre.mdv-chart:not([data-mdc-done])");
    for (var i = 0; i < blocks.length; i++) {
      var pre = blocks[i];
      var source = pre.textContent || "";
      var host = document.createElement("div");
      host.className = "mdv-chart-out";
      /* The source is kept on the element so a resize can lay the same chart out again
         at the new width. Reading it back off the rendered SVG would be guesswork. */
      host.setAttribute("data-mdc-src", source);
      host.setAttribute("data-mdc-done", "1");
      if (pre.parentNode) pre.parentNode.replaceChild(host, pre);
      draw(host, source);
    }
    return blocks.length;
  }

  /** Lays one chart out at the width its container actually has. */
  function draw(host, source) {
    var width = measure(host);
    try {
      host.innerHTML = render(source, width);
    } catch (e) {
      host.innerHTML = errorBlock("chart failed to render: " + (e && e.message), source);
    }
  }

  /**
   * The usable width inside the chart plate.
   *
   * <p>Measured from the host's parent rather than the host: the host is empty at this
   * point, and an empty block's own width is its parent's anyway, but the parent is the
   * one that survives the plate's padding being taken off.
   */
  function measure(host) {
    var parent = host.parentNode;
    var w = (parent && parent.clientWidth) || host.clientWidth || 0;
    /* The figure's own padding and border, which the plot does not get to use. */
    return w > 80 ? w - 40 : 680;
  }

  /**
   * Re-lays every chart after the pane changes width.
   *
   * <p>Debounced, because dragging the split divider fires continuously and each redraw
   * rebuilds an SVG. Compared against the last width used so that a resize in the other
   * axis - or one that rounds to the same width - costs nothing.
   */
  var resizeTimer = null;
  var lastWidth = 0;
  function watchResize() {
    if (typeof window === "undefined" || window.__mdcWatching) return;
    window.__mdcWatching = true;
    window.addEventListener("resize", function () {
      if (resizeTimer) clearTimeout(resizeTimer);
      resizeTimer = setTimeout(relayout, 120);
    });
  }

  function relayout() {
    var hosts = document.querySelectorAll(".mdv-chart-out[data-mdc-src]");
    if (!hosts.length) return;
    var w = measure(hosts[0]);
    if (Math.abs(w - lastWidth) < 8) return;
    lastWidth = w;
    for (var i = 0; i < hosts.length; i++) {
      draw(hosts[i], hosts[i].getAttribute("data-mdc-src") || "");
    }
  }

  watchResize();

  global.MdChart = {
    render: render, renderAll: renderAll, relayout: relayout,
    parse: parse, types: TYPES
  };
})(typeof window !== "undefined" ? window : this);
