#!/usr/bin/env python3
"""Check representative rendered contrast on synthetic Rust pages."""

from __future__ import annotations

import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Any, Literal

from playwright.sync_api import Page, sync_playwright

from ci.stricknani import rust_fixture, seed_rust, web_ui_browser

Theme = Literal["light", "dark"]
THEMES: tuple[Theme, ...] = ("light", "dark")


class ContrastFailure(RuntimeError):
    """A safe-to-display contrast acceptance failure."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ContrastFailure(message)


def _sample(page: Page, category: str, selector: str) -> list[dict[str, Any]]:
    value: object = page.evaluate(
        """({category, selector}) => {
          const parse = value => {
            if (value === 'transparent') return [0, 0, 0, 0];
            if (!/^rgba?\\(/i.test(value)) return null;
            const parts = value.match(/[\\d.]+/g)?.map(Number);
            if (!parts || parts.length < 3) return null;
            return [parts[0], parts[1], parts[2], parts[3] ?? 1];
          };
          const over = (top, bottom) => {
            const alpha = top[3] + bottom[3] * (1 - top[3]);
            if (alpha === 0) return [0, 0, 0, 0];
            return [0, 1, 2].map(index =>
              (top[index] * top[3] + bottom[index] * bottom[3]
                * (1 - top[3])) / alpha
            ).concat(alpha);
          };
          const luminance = color => color.slice(0, 3)
            .map(value => value / 255)
            .map(value => value <= 0.04045
              ? value / 12.92
              : ((value + 0.055) / 1.055) ** 2.4)
            .reduce((sum, value, index) =>
              sum + value * [0.2126, 0.7152, 0.0722][index], 0);
          const cssColor = color =>
            `rgb(${color.slice(0, 3).map(value => Math.round(value)).join(', ')})`;
          const selectorPath = element => {
            const parts = [];
            for (let node = element; node instanceof Element;
              node = node.parentElement) {
              const classes = [...node.classList].slice(0, 3)
                .map(name => `.${CSS.escape(name)}`).join('');
              let part = `${node.tagName.toLowerCase()}${classes}`;
              if (node.parentElement) {
                const siblings = [...node.parentElement.children]
                  .filter(sibling => sibling.tagName === node.tagName);
                if (siblings.length > 1) {
                  part += `:nth-of-type(${siblings.indexOf(node) + 1})`;
                }
              }
              parts.unshift(part);
              if (node.tagName === 'MAIN') break;
            }
            return parts.join(' > ');
          };
          const ratio = (foreground, background) => {
            const values = [luminance(foreground), luminance(background)]
              .sort((a, b) => b - a);
            return (values[0] + 0.05) / (values[1] + 0.05);
          };
          const visible = element => {
            const style = getComputedStyle(element);
            const rect = element.getBoundingClientRect();
            return style.display !== 'none' && style.visibility !== 'hidden'
              && !('disabled' in element && element.disabled)
              && Number(style.opacity) > 0 && rect.width > 0 && rect.height > 0;
          };
          const background = element => {
            let color = [255, 255, 255, 1];
            const chain = [];
            for (let node = element; node && node instanceof Element;
              node = node.parentElement) chain.push(node);
            for (const node of chain.reverse()) {
              const style = getComputedStyle(node);
              if (style.backgroundImage !== 'none'
                || Number(style.opacity) !== 1) {
                return {unsupported: true};
              }
              const layer = parse(style.backgroundColor);
              if (layer) color = over(layer, color);
            }
            return {color};
          };
          const elements = [...document.querySelectorAll(selector)]
            .filter(visible).slice(0, 8);
          return elements.map(element => {
            const style = getComputedStyle(element);
            const backdrop = background(
              category === 'control-border' ? element.parentElement : element,
            );
            if (backdrop.unsupported) return {skip: 'image-or-opacity-layer'};
            const bg = backdrop.color;
            const fg = parse(style.color);
            if (!fg) return {skip: 'unparsed-foreground'};
            const visibleFg = over(fg, bg);
            const large = parseFloat(style.fontSize) >= 24
              || (parseFloat(style.fontSize) >= 18.66
                && Number.parseInt(style.fontWeight, 10) >= 700);
            const entry = {
              ratio: ratio(visibleFg, bg),
              threshold: large ? 3 : 4.5,
              large_text: large,
              target_selector: selectorPath(element),
              foreground: cssColor(visibleFg),
              background: cssColor(bg),
            };
            if (category === 'placeholder') {
              const placeholder = parse(
                getComputedStyle(element, '::placeholder').color,
              );
              if (!placeholder) return {skip: 'unparsed-placeholder'};
              const placeholderStyle = getComputedStyle(element, '::placeholder');
              placeholder[3] *= Number(placeholderStyle.opacity);
              const visiblePlaceholder = over(placeholder, bg);
              entry.ratio = ratio(visiblePlaceholder, bg);
              entry.foreground = cssColor(visiblePlaceholder);
            }
            if (category === 'control-border') {
              const border = parse(style.borderTopColor);
              if (!border || style.borderTopStyle === 'none'
                || parseFloat(style.borderTopWidth) < 1) {
                return {skip: 'no-distinguishing-border'};
              }
              const visibleBorder = over(border, bg);
              entry.ratio = ratio(visibleBorder, bg);
              entry.foreground = cssColor(visibleBorder);
              entry.threshold = 3;
            }
            return entry;
          });
        }""",
        {"category": category, "selector": selector},
    )
    if not isinstance(value, list):
        raise ContrastFailure("browser returned invalid contrast measurements")
    return [item for item in value if isinstance(item, dict)]


def _focus_check(page: Page) -> dict[str, Any]:
    control = page.locator(
        "main input:not([type=hidden]):not([type=file]):visible, "
        "main button:visible, main select:visible, main textarea:visible"
    ).first
    _require(control.count() == 1, "route has no visible focusable control")
    page.evaluate(
        "() => { if (document.activeElement instanceof HTMLElement) "
        "document.activeElement.blur(); }"
    )
    for _ in range(80):
        if control.evaluate("element => document.activeElement === element"):
            break
        page.keyboard.press("Tab")
    _require(
        control.evaluate("element => document.activeElement === element"),
        "Tab traversal did not reach the selected control",
    )
    probe: object = control.evaluate(
        """element => {
          const style = getComputedStyle(element);
          const rect = element.getBoundingClientRect();
          const parse = value => {
            if (value === 'transparent') return [0, 0, 0, 0];
            if (!/^rgba?\\(/i.test(value)) return null;
            const parts = value.match(/[\\d.]+/g)?.map(Number);
            return parts && parts.length >= 3
              ? [parts[0], parts[1], parts[2], parts[3] ?? 1] : null;
          };
          const color = parse(style.outlineColor);
          const parts = [];
          for (let node = element; node instanceof Element;
            node = node.parentElement) {
            const classes = [...node.classList].slice(0, 3)
              .map(name => `.${CSS.escape(name)}`).join('');
            parts.unshift(`${node.tagName.toLowerCase()}${classes}`);
            if (node.tagName === 'MAIN') break;
          }
          let surface = [255, 255, 255, 1];
          const chain = [];
          for (let node = element.parentElement;
            node && node instanceof Element;
            node = node.parentElement) chain.push(node);
          for (const node of chain.reverse()) {
            const ancestorStyle = getComputedStyle(node);
            if (ancestorStyle.backgroundImage !== 'none'
              || Number(ancestorStyle.opacity) !== 1) {
              return {skipped: 'unsupported-background'};
            }
            const layer = parse(ancestorStyle.backgroundColor);
            if (layer) {
              const alpha = layer[3] + surface[3] * (1 - layer[3]);
              surface = [0, 1, 2].map(index =>
                (layer[index] * layer[3] + surface[index] * surface[3]
                  * (1 - layer[3])) / alpha
              ).concat(alpha);
            }
          }
          if (style.backgroundImage !== 'none'
            || Number(style.opacity) !== 1) {
            return {skipped: 'unsupported-background'};
          }
          if (!color || style.outlineStyle === 'none') {
            return {skipped: 'no-outline'};
          }
          const lum = c => c.slice(0, 3).map(v => v / 255).map(v => v <= 0.04045
            ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)
            .reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
          const paintedOutline = [0, 1, 2].map(index =>
            color[index] * color[3] + surface[index] * (1 - color[3])
          );
          const pair = [lum(paintedOutline), lum(surface)].sort((a, b) => b - a);
          return {
            target_selector: parts.join(' > '),
            ratio: (pair[0] + 0.05) / (pair[1] + 0.05),
            outline_width_css_px: parseFloat(style.outlineWidth),
            visible: rect.width > 0 && rect.height > 0
              && element.matches(':focus-visible'),
          };
        }""",
    )
    if not isinstance(probe, dict):
        raise ContrastFailure("keyboard focus indicator is not measurable")
    return {
        key: probe[key]
        for key in (
            "target_selector",
            "ratio",
            "outline_width_css_px",
            "visible",
            "skipped",
        )
        if key in probe
    }


def _check_route(
    page: Page,
    origin: str,
    route: str,
    label: str,
    theme: str,
    report_path: Path,
    report_records: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    response = page.goto(f"{origin}{route}", wait_until="domcontentloaded")
    _require(response is not None and response.status == 200, "page route failed")
    page.get_by_role("heading", level=1).first.wait_for(state="visible")
    loaded_selector = {
        "login": ".auth-form",
        "projects": ".project-card a.project-open",
        "reader": ".reader h1",
        "editor": ".project-editor form.editor-form",
        "yarn-editor": ".yarn-form",
        "account": ".account-page .profile-email",
        "gauge": ".gauge-page form.gauge-form",
    }[label]
    page.locator(loaded_selector).first.wait_for(state="visible", timeout=10_000)
    if label in {"projects", "reader"}:
        page.get_by_role("heading", name=seed_rust.PROJECT_NAME, exact=True).wait_for(
            state="visible", timeout=10_000
        )
    selectors = [
        ("heading", "main h1"),
        ("body-text", "main p:not(.field-hint):not([aria-hidden='true'])"),
        ("muted-text", "main .field-hint"),
        ("link-text", "main a"),
        (
            "control-text",
            "main input:not([type=hidden]):not([type=file]):not([type=checkbox]):"
            "not([type=radio]):not([type=range]), main textarea, main select, "
            "main button",
        ),
        (
            "placeholder",
            "main input[placeholder]:placeholder-shown:not([type=hidden]):"
            "not([type=file]):not([type=checkbox]):not([type=radio]):"
            "not([type=range]), main textarea[placeholder]:placeholder-shown",
        ),
        (
            "control-border",
            "main input:not([type=hidden]):not([type=file]):not([type=checkbox]):"
            "not([type=radio]):not([type=range]), main textarea, main select",
        ),
    ]
    records: list[dict[str, Any]] = []
    for category, selector in selectors:
        samples = _sample(page, category, selector)
        if not samples:
            if category in {
                "body-text",
                "muted-text",
                "link-text",
                "placeholder",
                "control-border",
            }:
                record = {
                    "route": label,
                    "theme": theme,
                    "category": category,
                    "skipped": "no-visible-target",
                }
                records.append(record)
                report_records.append(record)
                _write_report(report_path, report_records)
                continue
            raise ContrastFailure(f"route {route} has no sample for {category}")
        assessable = [sample for sample in samples if "ratio" in sample]
        if not assessable:
            record = {
                "route": label,
                "theme": theme,
                "category": category,
                "skipped": "unsupported-background",
            }
            records.append(record)
            report_records.append(record)
            _write_report(report_path, report_records)
            continue
        failures = [
            sample
            for sample in assessable
            if float(sample["ratio"]) < float(sample.get("threshold", 4.5))
        ]
        candidates = failures or assessable
        weakest_sample = min(
            candidates,
            key=lambda sample: (
                float(sample["ratio"]) / float(sample.get("threshold", 4.5))
            ),
        )
        weakest = float(weakest_sample["ratio"])
        threshold = float(weakest_sample.get("threshold", 4.5))
        passed = all(
            float(sample["ratio"]) >= float(sample.get("threshold", 4.5))
            for sample in assessable
        )
        sample_record: dict[str, Any] = {
            "route": label,
            "theme": theme,
            "category": category,
            "worst_ratio": round(weakest, 2),
            "threshold": threshold,
            "samples": len(assessable),
            "unsupported_samples": len(samples) - len(assessable),
            "target_selector": weakest_sample.get("target_selector", "unknown"),
            "foreground": weakest_sample.get("foreground", "unknown"),
            "background": weakest_sample.get("background", "unknown"),
            "passed": passed,
        }
        records.append(sample_record)
        report_records.append(sample_record)
        _write_report(report_path, report_records)
    focus = {
        "route": label,
        "theme": theme,
        "category": "keyboard-focus",
        **_focus_check(page),
    }
    if focus.get("skipped"):
        records.append(focus)
        report_records.append(focus)
        _write_report(report_path, report_records)
        return records
    focus["threshold"] = 3
    focus["passed"] = (
        focus.get("visible") is True
        and isinstance(focus.get("outline_width_css_px"), (float, int))
        and focus["outline_width_css_px"] >= 1
        and isinstance(focus.get("ratio"), (float, int))
        and focus["ratio"] >= 3
    )
    records.append(focus)
    report_records.append(focus)
    _write_report(report_path, report_records)
    return records


def _write_report(path: Path, records: list[dict[str, Any]]) -> None:
    path.write_text(json.dumps({"checks": records}, sort_keys=True), encoding="utf-8")
    path.chmod(0o600)


def _run_fixture(
    binary: Path,
    web_root: Path,
    private_dir: Path,
    executable: Path | None,
) -> list[dict[str, Any]]:
    fixture_file = private_dir / "fixture.json"
    state_file = private_dir / "fixture-state.json"
    token_file = private_dir / "seed-token.json"
    stage_file = private_dir / "contrast-stage.txt"

    def mark_stage(value: str) -> None:
        stage_file.write_text(value, encoding="ascii")
        stage_file.chmod(0o600)

    started = False
    try:
        mark_stage("starting synthetic server")
        rust_fixture.start_fixture(
            binary=binary,
            temp_dir=private_dir / "runtime",
            fixture_file=fixture_file,
            state_file=state_file,
            web_root=web_root,
            signup_enabled=False,
        )
        started = True
        mark_stage("seeding synthetic records")
        seed_rust.seed_fixture(fixture_file, token_file)
        token_file.unlink(missing_ok=True)
        mark_stage("reading synthetic server address")
        fixture = rust_fixture.load_fixture_environment(fixture_file)
        origin = web_ui_browser._loopback_origin(fixture["STRICKNANI_RUST_TEST_URL"])
        output: list[dict[str, Any]] = []
        report_path = private_dir / "contrast-results.json"
        with sync_playwright() as playwright:
            mark_stage("launching Chromium")
            browser = playwright.chromium.launch(
                headless=True,
                executable_path=str(executable) if executable else None,
            )
            try:
                mark_stage("creating synthetic browser context")
                context = browser.new_context(
                    locale="en-US", viewport={"width": 1280, "height": 900}
                )
                try:
                    mark_stage("opening synthetic sign-in page")
                    page = context.new_page()
                    response = page.goto(
                        f"{origin}/app/login", wait_until="domcontentloaded"
                    )
                    _require(
                        response is not None and response.status == 200,
                        "sign-in page failed",
                    )
                    for theme in THEMES:
                        page.emulate_media(color_scheme=theme)
                        mark_stage(f"{theme} login")
                        _check_route(
                            page,
                            origin,
                            "/app/login",
                            "login",
                            theme,
                            report_path,
                            output,
                        )
                    mark_stage("entering synthetic sign-in")
                    page.get_by_label("Email", exact=True).fill(
                        fixture["STRICKNANI_RUST_TEST_EMAIL"]
                    )
                    page.get_by_label("Password", exact=True).fill(
                        fixture["STRICKNANI_RUST_TEST_PASSWORD"]
                    )
                    page.get_by_role("button", name="Sign in", exact=True).click()
                    mark_stage("waiting for synthetic Projects route")
                    page.wait_for_url("**/app/projects", timeout=10_000)
                    card_link = page.locator(".project-card a.project-open").first
                    card_link.wait_for(state="visible")
                    project_path = card_link.get_attribute("href")
                    if not project_path:
                        raise ContrastFailure("seed project link is missing")
                    page.goto(f"{origin}/app/yarns", wait_until="domcontentloaded")
                    yarn_card = page.locator(".yarn-card").filter(
                        has=page.get_by_role(
                            "heading", name=seed_rust.YARN_NAME, exact=True
                        )
                    )
                    yarn_card.wait_for(state="visible", timeout=10_000)
                    yarn_path = yarn_card.locator("a.yarn-open").get_attribute("href")
                    if not yarn_path:
                        raise ContrastFailure("seed yarn link is missing")
                    routes = [
                        ("projects", "/app/projects"),
                        ("reader", project_path),
                        ("editor", f"{project_path.rstrip('/')}/edit"),
                        ("yarn-editor", f"{yarn_path.rstrip('/')}/edit"),
                        ("account", "/app/account"),
                        ("gauge", "/app/gauge"),
                    ]
                    for theme in THEMES:
                        page.emulate_media(color_scheme=theme)
                        for label, route in routes:
                            mark_stage(f"{theme} {label}")
                            _check_route(
                                page,
                                origin,
                                route,
                                label,
                                theme,
                                report_path,
                                output,
                            )
                    for theme in THEMES:
                        categories = {
                            record["category"]
                            for record in output
                            if record.get("theme") == theme
                            and ("worst_ratio" in record or "ratio" in record)
                        }
                        required = {
                            "heading",
                            "body-text",
                            "muted-text",
                            "link-text",
                            "control-text",
                            "placeholder",
                            "control-border",
                            "keyboard-focus",
                        }
                        missing = sorted(required - categories)
                        _require(
                            not missing,
                            f"theme={theme} missing assessable categories="
                            f"{','.join(missing)}",
                        )
                    failures = [
                        record for record in output if record.get("passed") is False
                    ]
                    if failures:
                        first = failures[0]
                        raise ContrastFailure(
                            "contrast below threshold: "
                            f"theme={first['theme']}, route={first['route']}, "
                            f"category={first['category']}, "
                            "selector="
                            f"{first.get('target_selector', 'not-applicable')}, "
                            "ratio="
                            f"{first.get('worst_ratio', first.get('ratio', 0))}, "
                            f"threshold={first.get('threshold', '3.0')}; "
                            f"additional_failures={len(failures) - 1}"
                        )
                finally:
                    context.close()
            finally:
                browser.close()
        return output
    finally:
        token_file.unlink(missing_ok=True)
        if started:
            rust_fixture.stop_fixture(state_file)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--web-root", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path, required=True)
    parser.add_argument("--browser-executable", type=Path)
    parser.add_argument("--browser-cache", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    private_dir: Path | None = None
    try:
        if args.browser_cache is not None:
            browser_cache = args.browser_cache.resolve()
            _require(
                browser_cache.is_dir(), "matched browser cache directory is unavailable"
            )
            os.environ["PLAYWRIGHT_BROWSERS_PATH"] = str(browser_cache)
        private_dir = web_ui_browser._private_work_dir(args.private_dir)
        results = _run_fixture(
            args.binary, args.web_root, private_dir, args.browser_executable
        )
    except (ContrastFailure, rust_fixture.FixtureError) as error:
        print(f"Contrast browser acceptance failed: {error}", file=sys.stderr)
        return 1
    except Exception as error:
        stage = "unknown"
        if private_dir is not None:
            stage_file = private_dir / "contrast-stage.txt"
            if stage_file.is_file():
                stage = stage_file.read_text(encoding="ascii")
        frames = traceback.extract_tb(error.__traceback__)
        location = (
            f"{Path(frames[-1].filename).name}:{frames[-1].lineno}"
            if frames
            else "unknown"
        )
        print(
            f"Contrast browser acceptance failed at {stage} "
            f"({type(error).__name__}, {location})",
            file=sys.stderr,
        )
        return 1
    print(json.dumps({"checks": results}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
