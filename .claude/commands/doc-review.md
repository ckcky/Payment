---
description: 对指定文档跑统一文档检查（判定类型 → 加载 Standard/Template → 10 组检查 → PASS/WARN/FAIL）
argument-hint: <file> [--fix]
---

# /doc-review

对 `$ARGUMENTS` 指定的文档执行一次**统一文档 Review**。

## 执行

1. **判定类型**：按 `.claude/skills/documentation-review/SKILL.md` 的「类型与标准映射」表确定文档类型，并加载对应 Standard 与 Template（**实时读取**，不凭记忆）。
2. **跑检查**：逐项执行 [docs/standards/documentation-review.md](../../docs/standards/documentation-review.md) §2 的 10 组检查；机械可判定项 MUST 用该文件 §5 的命令验证，不靠目测。
3. **输出**：严格按该文件 §3 的格式输出（Type / Standard / Template / Status → Summary → Violations 表 → Missing Sections → Drift → Broken References → Final Status）。

## 约束

- **默认只读**：不修改被审查文档，产出「结论 + 证据（文件:行号）」。
  - 用户显式传 `--fix` 时，才按 P0 → P1 顺序修复，**每改一处说明依据的条款**；`Accepted` ADR 正文、已归档审计记录**不在可修范围**。
- **不静默放行**；无问题也要给出已核对的检查组清单。
- 发现 `DOCUMENTATION_DRIFT` 时**只上报**，列出各方描述与待确认问题，交人类裁决。
- 涉及 Constitution §Governance › 人类决策边界的问题（领域边界、状态机、安全策略、破坏性 schema 等），标「需人类确认」，不自行处置。

## 多文件

传目录时，对目录下每个文档**逐个**出结论，最后给一张汇总表（file → type → status → P0/P1/P2 计数）。不要只给一个笼统结论。
