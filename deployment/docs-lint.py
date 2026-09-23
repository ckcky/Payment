# -*- coding: utf-8 -*-
"""
PaymentArch Documentation Governance v1 — 终检脚本（16 项）
输出：每项 PASS/FAIL + 命中明细（最多 12 条）。
只读，不修改任何文件。
"""
import os
import re
import sys
import json
import collections

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
os.chdir(ROOT)

MD_ROOTS = ["docs", "AGENTS.md", "README.md", "CLAUDE.md", "RELEASE.md", "CHANGELOG.md"]
SKIP_DIRS = {".git", "node_modules", "target", "output", "logs", "archive_bak"}
# 模板是「占位符」：其相对链接按**目标落点**书写（复制到 docs/... 后才成立），
# 因此在模板目录内做链接可达性检查无意义 —— 显式豁免（见 documentation-review §2.6）。
TEMPLATE_PREFIX = "docs/templates/"
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
FENCE_RE = re.compile(r"^[ \t]*(```|~~~).*?^[ \t]*\1[ \t]*$", re.S | re.M)
CODE_SPAN_RE = re.compile(r"`[^`\n]*`")
ANCHOR_DEF_RE = re.compile(r'<a\s+(?:id|name)="([^"]+)"')
HEAD_RE = re.compile(r"^(#{1,6})\s+(.*)$")
# 口径注明 / 历史提示 banner：带 banner 的历史文档豁免「10 个服务」与旧服务名检查
BANNER_MARKERS = ("口径注明（2026-09-22 文档治理）", "历史文档提示（2026-09-22 文档治理）")

results = []


def add(name, ok, hits, note=""):
    results.append((name, ok, hits[:12], len(hits), note))


def md_files(roots=MD_ROOTS):
    out = []
    for r in roots:
        if os.path.isfile(r):
            out.append(r)
            continue
        for dp, dns, fns in os.walk(r):
            dns[:] = [d for d in dns if d not in SKIP_DIRS]
            for fn in fns:
                if fn.endswith(".md"):
                    out.append(os.path.join(dp, fn).replace("\\", "/"))
    return sorted(set(out))


ALL_MD = md_files()


def read(p):
    try:
        with open(p, encoding="utf-8") as f:
            return f.read()
    except Exception:
        return ""


def rendered(p):
    """渲染视角的正文：去掉围栏代码块与行内代码。

    Markdown 渲染器不会把代码块 / 行内代码里的 `[...](...)` 变成链接，
    而规范类文档**故意**包含「错误写法」示例（如 `[ADR-0006](../adr/...md)`），
    故此处必须按渲染语义剔除，否则会把自己的反例判成失效链接。
    """
    return CODE_SPAN_RE.sub("", FENCE_RE.sub("", read(p)))


def slug(text):
    """GitHub-ish heading slug for CJK-safe approximation."""
    t = text.strip().lower()
    t = re.sub(r"`|\*", "", t)
    t = re.sub(r"[^\w\u4e00-\u9fff\s-]", "", t)
    t = t.replace(" ", "-")
    return t


# ---------------------------------------------------------------- 1. 链接可达
def check_links():
    hits = []
    for f in ALL_MD:
        if f.startswith(TEMPLATE_PREFIX) or f.startswith("docs/archive/"):
            continue
        base = os.path.dirname(f)
        for m in LINK_RE.finditer(rendered(f)):
            tgt = m.group(1)
            if tgt.startswith(("http://", "https://", "mailto:", "#", "file://")):
                continue
            path, _, anchor = tgt.partition("#")
            if not path:
                continue
            full = os.path.normpath(os.path.join(base, path))
            if not os.path.exists(full):
                hits.append("%s -> %s" % (f, tgt))
                continue
            if anchor and full.endswith(".md"):
                txt = read(full)
                heads = [slug(h.group(2)) for h in (HEAD_RE.match(l) for l in txt.split("\n")) if h]
                heads += [a.lower() for a in ANCHOR_DEF_RE.findall(txt)]
                if anchor not in heads:
                    hits.append("%s -> %s [锚点不可达]" % (f, tgt))
    add("1. Markdown 内部链接可达（模板目录豁免）", not hits, hits)


# ------------------------------------------------------- 2. Spec 路径口径
def check_spec_paths():
    hits = []
    pats = [
        (re.compile(r"docs/specs/[0-9]{3}-"), "docs/specs/<NNN-feature>/（缺 stage）"),
        (re.compile(r"(?<![a-zA-Z/._-])/specs/[0-9]{3}-"), "顶层 /specs/<NNN-feature>/"),
        (re.compile(r"`specs/[0-9]"), "顶层 `specs/<NNN>"),
    ]
    extra = [".specify/feature.json", ".specify/repo-config.json",
             ".specify/templates/plan-template.md", ".specify/templates/tasks-template.md",
             ".specify/templates/spec-template.md", ".specify/scripts/bash/create-new-feature.sh"]
    for f in ALL_MD + extra:
        if not os.path.exists(f):
            continue
        if f.startswith("docs/archive/"):
            continue
        txt = read(f)
        for ln, line in enumerate(txt.split("\n"), 1):
            if "governance-report" in f:
                continue
            for p, label in pats:
                if p.search(line):
                    hits.append("%s:%d [%s] %s" % (f, ln, label, line.strip()[:110]))
    add("2. Spec 路径口径统一", not hits, hits)


# ------------------------------------------------------- 3. ADR 索引完整性
def check_adr_index():
    adr_dir = "docs/adr"
    files = sorted(f for f in os.listdir(adr_dir)
                   if f.endswith(".md") and f not in ("README.md", "traceability.md"))
    readme = read(os.path.join(adr_dir, "README.md"))
    missing = [f for f in files if f not in readme]
    add("3. ADR 索引完整（README 覆盖全部 ADR 文件）", not missing,
        ["未登记: " + m for m in missing], "共 %d 个 ADR 文件" % len(files))


# ---------------------------------------------------------- 4. ADR ID 重复
def check_adr_dupes():
    seen = collections.defaultdict(list)
    for f in sorted(os.listdir("docs/adr")):
        if not f.endswith(".md"):
            continue
        for m in re.finditer(r'<a id="adr-(\d{4})"', read(os.path.join("docs/adr", f))):
            seen[m.group(1)].append(f)
    dupes = {k: v for k, v in seen.items() if len(set(v)) > 1 or len(v) > 1}
    allowed = {"0054"}
    bad = {k: v for k, v in dupes.items() if k not in allowed}
    hits = ["ADR-%s -> %s" % (k, ", ".join(sorted(set(v)))) for k, v in sorted(bad.items())]
    note = "已知例外：ADR-0054（%d 处）" % len(dupes.get("0054", []))
    add("4. ADR 编号重复（仅允许 0054 历史例外）", not hits, hits, note)


# --------------------------------------------------- 5. Spec 状态词表合规
STATES = ["Draft", "In Review", "Approved", "In Development",
          "Implemented", "Deprecated", "Superseded", "Not Implemented"]
BAD_WORDS = ["设计完成", "待评审", "提案中", "设计中（", "部分完成"]


def check_spec_status():
    hits = []
    n = 0
    for dp, dns, fns in os.walk("docs/specs"):
        if "spec.md" not in fns:
            continue
        n += 1
        p = os.path.join(dp, "spec.md")
        txt = read(p)
        m = re.search(r"\*\*Status\*\*[：:]\s*([^\n]*)", txt)
        if not m:
            hits.append("%s [缺 Status 头]" % p)
            continue
        val = m.group(1)
        for s in STATES:
            if s in val:
                break
        else:
            hits.append("%s [非法状态] %s" % (p, val[:80]))
    add("5. Spec 状态使用统一 8 态", not hits, hits, "共 %d 篇 spec.md" % n)


# --------------------------------------------- 6. 自由文本状态残留
def check_free_status():
    hits = []
    pat = re.compile(r"Status[^\n]{0,20}(设计完成|待评审|提案中|开发中|部分完成)")
    for f in ALL_MD:
        if f.startswith("docs/standards/") or f.startswith("docs/templates/"):
            continue
        for ln, line in enumerate(read(f).split("\n"), 1):
            if not pat.search(line):
                continue
            # 历史留痕（“原「…」为设计轮表述，已被…取代”）与删除线不视为违规
            if any(k in line for k in ("原「", "~~", "为设计轮表述", "已被后续实现取代", "历史")):
                continue
            hits.append("%s:%d %s" % (f, ln, line.strip()[:110]))
    add("6. 无自由文本状态", not hits, hits)


# ------------------------------------------------ 7. Spec 四件套齐备
def check_four_pieces():
    hits = []
    feats = []
    for dp, dns, fns in os.walk("docs/specs"):
        if "spec.md" in fns:
            feats.append(dp.replace("\\", "/"))
    design_only = []
    for f in feats:
        txt = read(os.path.join(f, "spec.md"))
        # 仅当 spec.md 头部**显式声明** delivery_mode 豁免时才跳过四件套要求；
        # 正文里偶然提到 “design-only” 不算（见 docs/standards/spec-standard.md §5）。
        declared = any(
            "delivery_mode" in ln and "design-only" in ln
            for ln in txt.split("\n")
        )
        if declared:
            design_only.append(f)
            continue
        for need in ("plan.md", "tasks.md"):
            if need not in os.listdir(f):
                hits.append("%s [缺 %s]" % (f, need))
    add("7. Spec 四件套齐备（design-only 例外已声明）", not hits, hits,
        "共 %d 个 Feature，其中 design-only %d 个" % (len(feats), len(design_only)))


# ------------------------------------------- 8. 旧版本号残留（v2.3.0）
def check_version():
    hits = []
    ok_ctx = ("修订历史", "追认", "Amendment", "审查时为", "升级", "v2.2.0 →", "→ 2.3.0",
              "2.3.0 →", "由宪法", "已由", "历史", "CHANGELOG", "例外（v2.3.0")
    for f in ALL_MD + [".specify/memory/constitution.md"]:
        if f in ALL_MD and f.startswith("docs/archive/"):
            continue
        for ln, line in enumerate(read(f).split("\n"), 1):
            if "2.3.0" not in line:
                continue
            if f == "CHANGELOG.md" or any(k in line for k in ok_ctx):
                continue
            hits.append("%s:%d %s" % (f, ln, line.strip()[:110]))
    add("8. 宪法版本号统一（无未加限定的 v2.3.0 引用）", not hits, hits)


# --------------------------------------------- 9. 过时服务名 refund-service
def check_service_names():
    hits = []
    allow = ("已并入", "并入", "不复存在", "历史", "原 refund-service", "原独立 refund-service",
             "Superseded", "退役", "旧文档", "口径注明", "历史文档提示")
    for f in ALL_MD:
        if f.startswith(("docs/archive/", "docs/adr/", "docs/standards/")):
            continue
        if f == "CHANGELOG.md":
            continue
        txt = read(f)
        if any(b in txt for b in BANNER_MARKERS):
            continue
        for ln, line in enumerate(txt.split("\n"), 1):
            if "refund-service" in line and not any(a in line for a in allow):
                hits.append("%s:%d %s" % (f, ln, line.strip()[:120]))
    add("9. 无过时服务名退款服务（未加历史标注的）", not hits, hits)


# ------------------------------------------- 10. 服务数口径（10 个服务）
def check_service_count():
    hits = []
    pat = re.compile(r"10\s*个服务")
    for f in ALL_MD:
        if f.startswith("docs/archive/") or f.startswith("docs/adr/") or f == "CHANGELOG.md":
            continue
        txt = read(f)
        if any(b in txt for b in BANNER_MARKERS):
            continue
        for ln, line in enumerate(txt.split("\n"), 1):
            if not pat.search(line):
                continue
            # 描述「旧口径过时」（引述待修注释）不算违规
            if "口径过时" in line or "注释" in line and "过时" in line:
                continue
            hits.append("%s:%d %s" % (f, ln, line.strip()[:120]))
    add("10. 服务数口径（禁止「10 个服务」）", not hits, hits)


# ------------------------------------------------ 11. 架构图引用可达
def check_diagrams():
    hits = []
    dd = "docs/architecture/diagrams"
    files = sorted(os.listdir(dd))
    pumls = [f for f in files if f.endswith(".puml")]
    svgs = [f for f in files if f.endswith(".svg")]
    stale = [f for f in files if re.match(r"0[123]-", f) and not re.match(r"0[123]-(system-context|container-overview|payment-components)", f)]
    if stale:
        hits.append("残留旧图: " + ", ".join(stale))
    for f in ALL_MD:
        if f.startswith(TEMPLATE_PREFIX) or f.startswith("docs/archive/"):
            continue
        for m in LINK_RE.finditer(rendered(f)):
            t = m.group(1)
            if "diagrams/" in t and t.endswith((".puml", ".svg")):
                p, _, _ = t.partition("#")
                full = os.path.normpath(os.path.join(os.path.dirname(f), p))
                if not os.path.exists(full):
                    hits.append("%s -> %s（不可达）" % (f, t))
    # .puml 是否都是 SVG 的唯一事实源（每张图被至少一处引用）
    referenced = set()
    for f in ALL_MD:
        for m in LINK_RE.finditer(rendered(f)):
            referenced.add(os.path.basename(m.group(1).partition("#")[0]))
    orphan = [p for p in pumls if p not in referenced]
    if orphan:
        hits.append("未被引用的图: " + ", ".join(orphan))
    add("11. 架构图引用可达 / 无残留旧图", not hits, hits,
        "%d puml / %d svg" % (len(pumls), len(svgs)))


# ------------------------------------------- 12. System Design 15 章骨架
CORE = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15"]


def check_15_chapters():
    hits = []
    for f in sorted(os.listdir("docs/architecture/systems")):
        if not f.endswith(".md"):
            continue
        p = os.path.join("docs/architecture/systems", f)
        nums = re.findall(r"^## (\d+)\.", read(p), flags=re.M)
        if nums[:15] != CORE:
            hits.append("%s: 章号序列 %s" % (p, nums[:15]))
    add("12. System Design 15 章骨架", not hits, hits)


# ------------------------------------------------- 13. 模板符合性
def check_templates():
    hits = []
    need = ["technical-solution.md", "system-design.md", "spec.md", "adr.md", "runbook.md"]
    for n in need:
        p = os.path.join("docs/templates", n)
        if not os.path.exists(p):
            hits.append("缺模板 " + p)
    # 模板不得含 Payment 业务内容
    for n in need:
        p = os.path.join("docs/templates", n)
        if not os.path.exists(p):
            continue
        txt = read(p)
        for bad in ("paymentNo", "refundNo", "ledger_entries", "surplusRefund", "ADR-00"):
            if bad in txt:
                hits.append("%s 含业务内容: %s" % (p, bad))
    add("13. 模板齐备且无业务内容", not hits, hits)


# ------------------------------------- 14. AI 体系引用治理层 + 路径可达
def check_ai():
    hits = []
    ai_files = []
    for dp, dns, fns in os.walk(".claude"):
        for fn in fns:
            if fn.endswith(".md"):
                ai_files.append(os.path.join(dp, fn).replace("\\", "/"))
    for dp, dns, fns in os.walk(".specify"):
        for fn in fns:
            if fn.endswith(".md"):
                ai_files.append(os.path.join(dp, fn).replace("\\", "/"))
    ai_files += ["AGENTS.md"]
    for f in ai_files:
        base = os.path.dirname(f)
        for m in LINK_RE.finditer(rendered(f)):
            t = m.group(1)
            if t.startswith(("http", "#", "mailto:")):
                continue
            p, _, _ = t.partition("#")
            if not p:
                continue
            full = os.path.normpath(os.path.join(base, p))
            if not os.path.exists(full):
                hits.append("%s -> %s（不可达）" % (f, t))
    # AGENTS.md 必须指向治理层三件
    ag = read("AGENTS.md")
    for need in ("docs/standards/", "docs/templates/", "documentation-review"):
        if need not in ag:
            hits.append("AGENTS.md 未引用 " + need)
    add("14. AI 体系引用治理层且链接可达", not hits, hits,
        "检查 %d 个 AI 规则文件" % len(ai_files))


# ------------------------------ 15. 章节引用一致性（ADR → System Design 旧章号已换算）
def check_cross_refs():
    hits = []
    # 索引类文档不得再指向已废弃章号
    legacy = re.compile(r"(payment-service\.md[^\n|]{0,4}|order-service\.md[^\n|]{0,4}|"
                        r"reconciliation-service\.md[^\n|]{0,6})(?:\*\*)?§(2\.3|3\.1[12]|4\.3\.1)(?![0-9])")
    for f in ALL_MD:
        if f.startswith("docs/adr/00") or f.startswith("docs/archive/"):
            continue  # 历史 ADR 正文不回改（adr-standard §5.6）
        for ln, line in enumerate(read(f).split("\n"), 1):
            if legacy.search(line):
                hits.append("%s:%d %s" % (f, ln, line.strip()[:120]))
    add("15. 索引类文档章号引用已换算（历史 ADR 除外）", not hits, hits)


# ------------------------------ 16. ADR 交叉引用一致性
#   `[ADR-NNNN](NNNN-slug.md)` 的 NNNN MUST 由目标文件承载（即该文件内有 <a id="adr-NNNN">）。
#   背景：2026-09-19「文件名前缀 = 文件内首个 ADR 编号」重命名后，正文里按**旧文件序号**写的
#   标签会与真实编号脱节（如 [ADR-0006](../adr/0016-refund-decisions.md)）—— 是真实漂移。
def check_adr_xref():
    adr_dir = "docs/adr"
    anchors = {}
    if os.path.isdir(adr_dir):
        for fn in os.listdir(adr_dir):
            if fn.endswith(".md"):
                anchors[fn] = set(re.findall(r'<a\s+id="adr-(\d{4})"', read(os.path.join(adr_dir, fn))))
    label_re = re.compile(r"ADR-(\d{4})")
    link_re = re.compile(r"\[([^\]]*?)\]\(([^)\s]+?)\)")
    hits = []
    for f in ALL_MD + md_files([".claude", ".specify"]):
        in_fence = False
        for ln, line in enumerate(read(f).split("\n"), 1):
            if re.match(r"^[ \t]*(```|~~~)", line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for label, target in link_re.findall(CODE_SPAN_RE.sub("", line)):
                nums = label_re.findall(label)
                if not nums:
                    continue
                base = target.split("#")[0].rstrip("/")
                if not base.endswith(".md"):
                    continue
                fname = os.path.basename(base)
                if fname in ("README.md", "traceability.md"):
                    continue  # 索引类文件被当作来源引用是合法的
                if os.path.normpath(os.path.join(os.path.dirname(f), base)).replace("\\", "/") \
                        .rpartition("/")[0] != adr_dir:
                    continue
                if fname not in anchors:
                    hits.append("%s:%d 目标文件不存在 %s" % (f, ln, base))
                elif not (set(nums) & anchors[fname]):
                    hits.append("%s:%d [%s] -> %s（该文件承载 %s）"
                                % (f, ln, "/".join(nums), base,
                                   ",".join(sorted(anchors[fname])) or "无"))
    add("16. ADR 交叉引用编号与目标文件一致", not hits, hits)


def main():
    for fn in (check_links, check_spec_paths, check_adr_index, check_adr_dupes,
               check_spec_status, check_free_status, check_four_pieces, check_version,
               check_service_names, check_service_count, check_diagrams,
               check_15_chapters, check_templates, check_ai, check_cross_refs,
               check_adr_xref):
        try:
            fn()
        except Exception as e:
            results.append((fn.__name__, False, ["EXCEPTION: %r" % e], 1, ""))
    passed = sum(1 for r in results if r[1])
    print("=" * 78)
    for name, ok, hits, total, note in results:
        print("[%s] %-46s %s" % ("PASS" if ok else "FAIL", name, ("(%s)" % note) if note else ""))
        for h in hits:
            print("        - " + h)
        if total > len(hits):
            print("        … 另有 %d 条同类" % (total - len(hits)))
    print("=" * 78)
    print("TOTAL: %d/%d PASS" % (passed, len(results)))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
