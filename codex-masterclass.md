# Codex Masterclass 专题手册

> 14 个核心主题，覆盖 Codex Agent 从基础到高级的完整知识体系。
> 每个主题统一结构：**是什么 → 为什么 → 怎么用 → 最佳实践 → 面试/实战要点**

---

## 目录

1. [Rule — 规则系统](#1-rule--规则系统)
2. [Skill — 技能扩展](#2-skill--技能扩展)
3. [MCP — 模型上下文协议](#3-mcp--模型上下文协议)
4. [Memory — 记忆与上下文管理](#4-memory--记忆与上下文管理)
5. [Hooks — 生命周期钩子](#5-hooks--生命周期钩子)
6. [SubAgent — 子代理协作](#6-subagent--子代理协作)
7. [Git Worktree — 并行工作树](#7-git-worktree--并行工作树)
8. [Agent — 智能体架构](#8-agent--智能体架构)
9. [Plan — 计划驱动开发](#9-plan--计划驱动开发)
10. [TDD — 测试驱动开发](#10-tdd--测试驱动开发)
11. [SDD — 规格驱动开发](#11-sdd--规格驱动开发)
12. [OpenSpec — 开放规格协议](#12-openspec--开放规格协议)
13. [Superpowers — 超级能力](#13-superpowers--超级能力)
14. [Context — 上下文工程](#14-context--上下文工程)

---

# 1. Rule — 规则系统

## 是什么

Rule 是 Codex 的"宪法"——持久化的指令集，告诉 Agent **什么能做、什么不能做、怎么做**。
它不像 Prompt 那样一次性的，而是跨会话生效的约束和指南。

## 为什么

- **一致性**：不同会话、不同模型都遵循相同规范
- **可审计**：规则是文件，可版本控制、可 Code Review
- **降本**：减少每次对话重复说明约束的 Token 消耗
- **安全**：明确禁止危险操作，防止 Agent 越界

## 怎么用

### 文件位置与优先级

```
~/.codex/rules/          ← 全局规则（所有项目生效）
<project>/.codex/rules/  ← 项目规则（覆盖全局）
<project>/AGENTS.md      ← 项目级 Agent 规则（最常用）
```

优先级：**项目规则 > 全局规则**，同级文件按文件名字母序加载。

### AGENTS.md 示例

```markdown
# Project Rules

## Tech Stack
- JDK 8, no var, no records, no switch expressions
- JUnit 4.13.2, no Mockito
- No Spring, pure JDK

## Code Style
- Chinese Javadoc
- Method names: camelCase
- Test names: shouldXxxWhenYyy

## Git
- Branch prefix: codex/
- Commit: conventional commits (feat/fix/docs/refactor/test)
```

### .codex/rules/ 目录结构

```
.codex/rules/
├── 00-safety.md         # 安全红线（最高优先级）
├── 10-code-style.md     # 代码风格
├── 20-testing.md        # 测试规范
└── 30-git.md            # Git 规范
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 分层规则 | 安全 > 风格 > 业务，用数字前缀控制优先级 |
| 粒度适中 | 每条规则 1-3 行，避免写成文档 |
| 可测试 | 规则应可验证，如"所有测试必须通过 mvn test" |
| 版本控制 | AGENTS.md 和 .codex/rules/ 都纳入 Git |
| 定期审计 | 每月检查规则是否过时、冲突 |

## 面试/实战要点

**Q: Rule 和 Prompt 有什么区别？**
A: Rule 是持久化的、跨会话的、可版本控制的；Prompt 是一次性的。Rule 更像是"配置"，Prompt 更像是"对话"。

**Q: 如何避免规则冲突？**
A: 分层 + 数字前缀 + 明确优先级。安全规则永远最高优先级。

---

# 2. Skill — 技能扩展

## 是什么

Skill 是 Codex 的"插件系统"——将特定领域的知识、工具、工作流打包成可复用的模块。
一个 Skill 可以包含指令、MCP 工具、文件模板等。

## 为什么

- **专业分工**：不同领域（前端、数据库、DevOps）各有专精
- **即插即用**：安装后自动生效，无需每次配置
- **社区共享**：可发布到 marketplace，也可从 GitHub 安装
- **降低复杂度**：将复杂工作流封装为简单触发词

## 怎么用

### Skill 结构

```
my-skill/
├── SKILL.md              # 核心指令（必需）
├── templates/            # 文件模板（可选）
├── scripts/              # 辅助脚本（可选）
└── skill.json            # 元数据（可选）
```

### SKILL.md 示例

```markdown
# Database Skill

## 触发条件
当用户要求操作数据库、写 SQL、分析慢查询时激活。

## 工作流
1. 先用 EXPLAIN 分析 SQL
2. 检查索引使用情况
3. 给出优化建议

## 约束
- 禁止直接执行 DROP/DELETE without WHERE
- 所有 DDL 需要用户确认
```

### 安装 Skill

```bash
# 从 GitHub 安装
codex skill install github:user/repo/path

# 从 marketplace 安装
codex skill install database-optimizer

# 列出已安装
codex skill list
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 单一职责 | 一个 Skill 解决一类问题 |
| 明确触发 | SKILL.md 开头写清何时激活 |
| 安全约束 | 写明禁止操作，防止误用 |
| 可测试 | 提供示例输入输出 |
| 文档完整 | README + SKILL.md + 示例 |

## 面试/实战要点

**Q: Skill 和 Rule 的区别？**
A: Rule 是约束（不能做什么），Skill 是能力（能做什么）。Rule 是被动的，Skill 是主动的。

**Q: 如何创建一个高质量的 Skill？**
A: 明确触发条件 → 定义工作流 → 添加安全约束 → 提供示例 → 测试验证。

---

# 3. MCP — 模型上下文协议

## 是什么

MCP（Model Context Protocol）是连接 LLM 与外部工具/数据的标准协议。
它让 Agent 能够调用外部 API、访问数据库、操作文件系统等。

## 为什么

- **标准化**：统一的工具调用接口，避免每个工具写适配器
- **安全**：权限控制、沙箱隔离
- **可组合**：多个 MCP Server 可以组合使用
- **生态**：社区贡献的 MCP Server 可直接复用

## 怎么用

### MCP Server 架构

```
Agent (Client)
    ↓ MCP Protocol
MCP Server A (数据库)
MCP Server B (文件系统)
MCP Server C (API 网关)
```

### 配置 MCP Server

```json
// .codex/mcp.json
{
  "servers": {
    "mysql": {
      "command": "mcp-server-mysql",
      "args": ["--host", "localhost", "--port", "3306"],
      "env": {
        "MYSQL_PASSWORD": "${MYSQL_PASSWORD}"
      }
    },
    "filesystem": {
      "command": "mcp-server-filesystem",
      "args": ["/allowed/path"]
    }
  }
}
```

### 工具调用流程

```
1. Agent 分析用户需求
2. 选择合适的 MCP 工具
3. 构造参数
4. 调用工具
5. 处理返回结果
6. 继续推理或返回用户
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 最小权限 | 只暴露必要的工具和路径 |
| 环境变量 | 敏感信息用环境变量，不硬编码 |
| 错误处理 | 工具调用失败要有优雅降级 |
| 超时控制 | 设置合理的超时时间 |
| 日志审计 | 记录所有工具调用 |

## 面试/实战要点

**Q: MCP 和 Function Calling 有什么区别？**
A: Function Calling 是模型层面的能力；MCP 是协议层面的标准。MCP 定义了如何发现工具、如何调用、如何返回结果的完整流程。

**Q: 如何保证 MCP 调用的安全性？**
A: 最小权限原则 + 沙箱隔离 + 环境变量 + 审计日志。

---

# 4. Memory — 记忆与上下文管理

## 是什么

Memory 是 Agent 的"长期记忆"——跨会话持久化的信息，包括项目知识、用户偏好、历史决策等。

## 为什么

- **连续性**：跨会话保持上下文，避免重复说明
- **个性化**：记住用户偏好和习惯
- **效率**：减少重复推理，加速任务完成
- **知识积累**：项目知识逐步沉淀

## 怎么用

### Memory 类型

```
┌─────────────────────────────────────┐
│           Memory Layer              │
├──────────┬──────────┬───────────────┤
│  Short   │  Medium  │    Long       │
│ (会话内) │ (项目级) │  (全局级)     │
├──────────┼──────────┼───────────────┤
│ 当前对话 │ AGENTS.md│ ~/.codex/     │
│ 上下文   │ .codex/  │   memory/     │
│          │ MEMORY.md│               │
└──────────┴──────────┴───────────────┘
```

### 项目级 Memory

```markdown
# MEMORY.md

## 项目决策
- 选择 MySQL 而非 PostgreSQL，因为团队熟悉
- 使用乐观锁处理并发，不用分布式锁
- 所有金额用分（long）存储，避免浮点精度问题

## 已知问题
- 数据库连接池偶尔超时，需要重试
- 某个第三方 API 响应慢，设置了 10s 超时

## 用户偏好
- 喜欢简洁的代码风格
- 偏好中文注释
- 测试用 shouldXxxWhenYyy 命名
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 分层存储 | 短期（会话）/ 中期（项目）/ 长期（全局） |
| 定期清理 | 过时的 Memory 要及时删除 |
| 结构化 | 用 Markdown 格式，便于检索 |
| 版本控制 | MEMORY.md 纳入 Git |
| 避免冗余 | Memory 不是文档，只存关键决策和偏好 |

## 面试/实战要点

**Q: Memory 和 Context 有什么区别？**
A: Memory 是持久化的、跨会话的；Context 是临时的、当前会话的。Memory 是"记住的"，Context 是"看到的"。

**Q: 如何管理大量 Memory？**
A: 分层 + 结构化 + 定期清理 + 用 MEMORY.md 记录关键决策。

---

# 5. Hooks — 生命周期钩子

## 是什么

Hooks 是 Agent 生命周期中的"拦截器"——在特定事件发生时自动执行的代码。
类似 Git Hooks、ESLint Hooks，但作用于 Agent 的推理和执行流程。

## 为什么

- **自动化**：在关键节点自动执行检查、格式化、通知
- **安全网**：防止危险操作（如直接推送到 main）
- **一致性**：确保每次操作都遵循相同流程
- **可观测**：记录操作日志，便于审计

## 怎么用

### Hook 类型

```
Agent Lifecycle:
  ┌──────────┐
  │  Start   │ ← on_start
  └────┬─────┘
       ↓
  ┌──────────┐
  │  Plan    │ ← on_plan_created
  └────┬─────┘
       ↓
  ┌──────────┐
  │  Execute │ ← on_tool_call, on_file_write
  └────┬─────┘
       ↓
  ┌──────────┐
  │  Verify  │ ← on_test_run, on_build
  └────┬─────┘
       ↓
  ┌──────────┐
  │  Commit  │ ← on_git_stage, on_git_commit
  └────┬─────┘
       ↓
  ┌──────────┐
  │  End     │ ← on_end
  └──────────┘
```

### 配置示例

```yaml
# .codex/hooks.yaml
hooks:
  on_file_write:
    - name: "format-check"
      command: "prettier --check ${file}"
      fail_silent: false

  on_git_commit:
    - name: "test-before-commit"
      command: "mvn test -q"
      fail_silent: false

  on_start:
    - name: "check-branch"
      command: "git branch --show-current"
      capture: true
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 快速执行 | Hook 超时 5-10 秒，避免阻塞 |
| 幂等设计 | 多次执行结果一致 |
| 静默失败 | 非关键 Hook 可以 fail_silent |
| 日志记录 | 记录 Hook 执行结果 |
| 最小化 | 只在关键节点使用 Hook |

## 面试/实战要点

**Q: Hooks 和 Rule 的区别？**
A: Rule 是"说什么"（声明式），Hooks 是"怎么做"（命令式）。Rule 定义约束，Hooks 执行检查。

**Q: 如何避免 Hook 影响性能？**
A: 设置超时 + 异步执行 + 只在关键节点使用 + 避免重型操作。

---

# 6. SubAgent — 子代理协作

## 是什么

SubAgent 是 Agent 的"分身"——主 Agent 将任务分解后派发给子代理并行执行。
类似多线程，但每个子代理有独立的推理上下文。

## 为什么

- **并行加速**：多个独立任务同时执行
- **专业分工**：不同子代理专注不同领域
- **上下文隔离**：避免主 Agent 上下文过长
- **容错**：单个子代理失败不影响其他

## 怎么用

### 派发模式

```
Main Agent
  ├── SubAgent A: 数据库分析
  ├── SubAgent B: 代码审查
  └── SubAgent C: 测试生成
      ↓ 汇总结果
Main Agent: 集成输出
```

### 使用场景

```
适合 SubAgent：
  ✅ 独立的代码审查任务
  ✅ 并行生成多个测试文件
  ✅ 同时搜索多个信息源
  ✅ 代码重构（不同模块）

不适合 SubAgent：
  ❌ 强依赖的串行任务
  ❌ 需要全局上下文的决策
  ❌ 简单的单步操作
  ❌ 需要频繁交互的任务
```

### 派发示例

```markdown
## 任务分解

### SubAgent 1: 数据库模块
- 创建 TransactionDemo.java
- 创建 TransactionDemoTest.java
- 目录: src/main/java/.../database/transaction/

### SubAgent 2: 索引模块
- 创建 IndexDemo.java
- 创建 IndexDemoTest.java
- 目录: src/main/java/.../database/index/

### 主 Agent
- 等待两个 SubAgent 完成
- 集成测试
- 更新 README
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 任务独立 | 子任务之间无依赖 |
| 明确范围 | 每个 SubAgent 只负责一个模块 |
| 结果汇总 | 主 Agent 负责集成和验证 |
| 错误处理 | 单个 SubAgent 失败不影响其他 |
| 控制数量 | 一般 2-5 个，避免资源竞争 |

## 面试/实战要点

**Q: SubAgent 和多线程有什么区别？**
A: SubAgent 是 Agent 级别的并行，有独立的推理上下文；多线程是代码级别的并行，共享内存。SubAgent 更适合复杂的、需要推理的任务。

**Q: 如何处理 SubAgent 之间的依赖？**
A: 尽量避免依赖。如果必须依赖，用主 Agent 串行调度，或用中间文件传递结果。

---

# 7. Git Worktree — 并行工作树

## 是什么

Git Worktree 允许一个仓库同时有多个工作目录，每个目录可以是不同的分支。
这让 Agent 可以在不切换分支的情况下并行处理多个任务。

## 为什么

- **并行开发**：同时在多个分支上工作
- **无损切换**：不需要 stash 或 commit 半成品
- **隔离性**：每个 worktree 独立，互不干扰
- **Agent 友好**：天然适合多任务并行

## 怎么用

### 基本操作

```bash
# 创建 worktree
git worktree add ../project-feature feature-branch
git worktree add ../project-fix fix-branch

# 列出 worktree
git worktree list

# 删除 worktree
git worktree remove ../project-feature

# 清理无效 worktree
git worktree prune
```

### Agent 使用场景

```
场景：同时开发 3 个模块

主仓库 (main)
  ├── worktree/database  → database 分支
  ├── worktree/cache     → cache 分支
  └── worktree/mq        → mq 分支

每个 worktree 由独立的 SubAgent 负责，互不干扰。
```

### 目录结构

```
project/
├── .git/                    # 主仓库
├── src/                     # main 分支的工作目录
├── .worktrees/
│   ├── database/            # database 分支的工作目录
│   ├── cache/               # cache 分支的工作目录
│   └── mq/                  # mq 分支的工作目录
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 统一前缀 | `.worktrees/` 目录存放所有 worktree |
| 及时清理 | 任务完成后删除 worktree |
| 分支命名 | 与 worktree 目录名一致 |
| 避免冲突 | 不要同时修改同一个文件 |
| 定期同步 | 从 main 拉取最新代码 |

## 面试/实战要点

**Q: Git Worktree 和 Git Clone 有什么区别？**
A: Worktree 共享同一个 `.git` 目录，只有一份仓库数据；Clone 是完整的仓库副本。Worktree 更轻量、更易管理。

**Q: Agent 如何利用 Git Worktree？**
A: 主 Agent 创建多个 worktree，每个 SubAgent 在独立的 worktree 中工作，最后合并到主分支。

---

# 8. Agent — 智能体架构

## 是什么

Agent 是具备**感知-推理-行动**能力的自主系统。
它不只是问答机器人，而是能理解目标、制定计划、执行操作、验证结果的完整系统。

## 为什么

- **自主性**：不需要人类逐步指导
- **适应性**：根据环境反馈调整策略
- **可组合**：多个 Agent 可以协作完成复杂任务
- **可扩展**：通过 Tool、Skill、MCP 扩展能力

## 怎么用

### Agent 核心循环

```
┌─────────────────────────────────────┐
│           Agent Loop                │
│  ┌─────────┐                        │
│  │ Perceive │ ← 接收用户输入        │
│  └────┬────┘                        │
│       ↓                             │
│  ┌─────────┐                        │
│  │ Reason   │ ← 分析、推理、规划    │
│  └────┬────┘                        │
│       ↓                             │
│  ┌─────────┐                        │
│  │ Act     │ ← 调用工具、执行操作   │
│  └────┬────┘                        │
│       ↓                             │
│  ┌─────────┐                        │
│  │ Observe │ ← 验证结果、收集反馈   │
│  └────┬────┘                        │
│       ↓                             │
│  Loop until goal achieved           │
└─────────────────────────────────────┘
```

### Agent 能力层次

```
Level 1: 问答（Chatbot）
  └── 回答问题，无状态

Level 2: 工具调用（Tool Use）
  └── 能调用外部工具，有状态

Level 3: 自主规划（Planning）
  └── 能分解任务、制定计划

Level 4: 多 Agent 协作（Multi-Agent）
  └── 能派发任务、协调执行

Level 5: 自我进化（Self-Improvement）
  └── 能从经验中学习、优化策略
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 目标明确 | 每个 Agent 有清晰的目标和约束 |
| 最小能力 | 只暴露必要的工具和权限 |
| 可观测 | 记录推理过程和工具调用 |
| 容错设计 | 工具调用失败有优雅降级 |
| 人类在环 | 关键决策需要人类确认 |

## 面试/实战要点

**Q: Agent 和传统脚本有什么区别？**
A: 脚本是确定性的（输入→固定输出），Agent 是适应性的（输入→推理→动态输出）。Agent 能处理未预见的情况。

**Q: 如何设计一个高质量的 Agent？**
A: 明确目标 → 定义能力边界 → 设计推理循环 → 添加验证机制 → 持续优化。

---

# 9. Plan — 计划驱动开发

## 是什么

Plan 是 Agent 在执行前的"施工图纸"——将复杂任务分解为可执行的步骤。
它让 Agent 不是盲目执行，而是有组织、有验证地推进。

## 为什么

- **可控性**：人类可以审查和修改计划
- **可追溯**：每步都有状态记录
- **高效**：避免重复和遗漏
- **并行**：识别可并行的步骤

## 怎么用

### Plan 文件格式

```markdown
## Plan: 实现数据库模块

### Phase 1: 基础设施
- [x] 创建 JdbcUtil.java（数据库连接工具）
- [x] 创建 db.properties（配置文件）
- [x] 更新 pom.xml（添加 MySQL 依赖）

### Phase 2: 事务模块
- [ ] 创建 TransactionIsolationDemo.java
- [ ] 创建 TransactionIsolationDemoTest.java
- [ ] 运行测试验证

### Phase 3: 索引模块
- [ ] 创建 IndexDemo.java
- [ ] 创建 IndexDemoTest.java
- [ ] 运行测试验证
```

### Plan 工作流

```
1. 接收任务
2. 分析任务复杂度
   └── 简单任务 → 直接执行
   └── 复杂任务 → 创建 Plan
3. 生成 Plan（分解步骤）
4. 人类审查 Plan（可选）
5. 逐步执行
6. 验证每步结果
7. 更新 Plan 状态
8. 完成报告
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 粒度适中 | 每步 5-15 分钟可完成 |
| 可验证 | 每步都有明确的完成标准 |
| 依赖清晰 | 标记步骤间的依赖关系 |
| 状态追踪 | 实时更新完成状态 |
| 灵活调整 | 遇到问题可修改计划 |

## 面试/实战要点

**Q: Plan 和 TODO 有什么区别？**
A: Plan 有依赖关系、状态追踪、验证标准；TODO 只是清单。Plan 是动态的，可以调整；TODO 是静态的。

**Q: 什么时候需要 Plan？**
A: 任务超过 3 个步骤、涉及多个文件、需要验证、有并行机会时。

---

# 10. TDD — 测试驱动开发

## 是什么

TDD 是"先写测试，再写实现"的开发方法。
它的核心循环是：**Red（失败）→ Green（通过）→ Refactor（重构）**。

## 为什么

- **设计优先**：先想清楚接口，再实现细节
- **安全网**：随时可以重构，不怕破坏已有功能
- **文档化**：测试就是最好的使用文档
- **信心**：绿灯就是信心，红灯就是警报

## 怎么用

### TDD 循环

```
1. Red: 写一个失败的测试
   └── 测试描述了期望的行为

2. Green: 写最少的代码让测试通过
   └── 不要过度设计，只要能过

3. Refactor: 优化代码结构
   └── 测试是安全网，放心重构

4. 重复: 下一个测试
```

### 实战示例（LRU Cache）

```java
// Step 1: Red - 写失败的测试
@Test
public void shouldEvictLeastRecentlyWhenCapacityExceeded() {
    LRUCache<Integer, String> cache = new LRUCacheImpl<>(2);
    cache.put(1, "A");
    cache.put(2, "B");
    cache.put(3, "C"); // 应该淘汰 key=1
    assertNull(cache.get(1));
    assertEquals("B", cache.get(2));
    assertEquals("C", cache.get(3));
}

// Step 2: Green - 实现最小代码
public class LRUCacheImpl<K, V> implements LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;

    // ... 实现 get/put
}

// Step 3: Refactor - 提取公共方法，优化结构
```

### 测试分类

```
测试金字塔:
  ┌─────────────┐
  │   E2E 测试   │  ← 少量，验证完整流程
  ├─────────────┤
  │  集成测试    │  ← 适量，验证模块交互
  ├─────────────┤
  │   单元测试   │  ← 大量，验证单个方法
  └─────────────┘
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 一个测试一个行为 | 不要在测试里验证多个不相关的事 |
| 命名清晰 | shouldXxxWhenYyy |
| 独立执行 | 测试之间无依赖 |
| 快速执行 | 单元测试 < 100ms |
| 测试边界 | 正常、异常、边界都要覆盖 |

## 面试/实战要点

**Q: TDD 的最大挑战是什么？**
A: 坚持 Red-Green-Refactor 循环，不跳步。很多人写着写着就变成"先写实现再补测试"。

**Q: 什么时候不需要 TDD？**
A: 探索性编程（不知道怎么做）、一次性脚本、UI 布局（主观性强）。

---

# 11. SDD — 规格驱动开发

## 是什么

SDD（Spec-Driven Development）是"先写规格，再写代码"的开发方法。
规格定义了**做什么**（What），代码实现**怎么做**（How）。

## 为什么

- **需求清晰**：规格是需求的精确表达
- **可验证**：规格可以自动生成测试
- **可追溯**：从规格到代码到测试，全链路可追溯
- **减少歧义**：规格消除了自然语言的模糊性

## 怎么用

### SDD 工作流

```
1. 需求分析
   └── 理解业务需求

2. 编写规格（Spec）
   └── 定义接口、输入、输出、约束

3. 生成测试（可选）
   └── 从规格自动生成测试骨架

4. 实现代码
   └── 满足规格要求

5. 验证
   └── 代码行为符合规格
```

### 接口规格示例

```java
/**
 * LRU Cache 规格
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>capacity > 0</li>
 *   <li>get/put/remove 均为 O(1)</li>
 *   <li>线程不安全（单线程使用）</li>
 * </ul>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>put: 插入键值对，超容量淘汰最久未使用</li>
 *   <li>get: 存在则返回值并标记为最近使用，否则返回 null</li>
 *   <li>remove: 存在则删除并返回值，否则返回 null</li>
 * </ul>
 */
public interface LRUCache<K, V> {
    V get(K key);
    void put(K key, V value);
    V remove(K key);
    int size();
    boolean isEmpty();
}
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 接口优先 | 先定义接口，再实现 |
| 约束明确 | 性能、线程安全、异常行为都要写清 |
| 可测试 | 规格可以直接转化为测试用例 |
| 版本控制 | 规格文件纳入 Git |
| 与代码同步 | 规格变更要同步更新代码 |

## 面试/实战要点

**Q: SDD 和 TDD 的关系？**
A: SDD 是 TDD 的前置步骤。SDD 定义"做什么"，TDD 验证"做得对不对"。两者互补：SDD → TDD → 实现。

**Q: 什么时候用 SDD？**
A: 接口设计、公共库、需要多方协作的模块。

---

# 12. OpenSpec — 开放规格协议

## 是什么

OpenSpec 是一种结构化的规格描述格式，让规格可以被工具解析、验证、生成代码。
它是 SDD 的"标准化版本"。

## 为什么

- **工具友好**：可以被解析器、生成器、验证器处理
- **跨语言**：同一份规格可以生成 Java、Python、Go 代码
- **自动化**：可以自动生成测试、文档、API 定义
- **协作**：团队共享统一的规格格式

## 怎么用

### OpenSpec 格式示例

```yaml
# lru-cache.openspec.yaml
name: LRUCache
version: 1.0.0
description: Least Recently Used Cache

constraints:
  - capacity > 0
  - get: O(1)
  - put: O(1)
  - remove: O(1)

interface:
  methods:
    - name: get
      params: [{ name: key, type: K }]
      returns: V | null
      behavior: "存在返回值并标记最近使用，否则返回 null"

    - name: put
      params: [{ name: key, type: K }, { name: value, type: V }]
      returns: void
      behavior: "插入键值对，超容量淘汰最久未使用"

    - name: remove
      params: [{ name: key, type: K }]
      returns: V | null
      behavior: "存在则删除返回，否则返回 null"

tests:
  - name: shouldGetExistingKey
  - name: shouldReturnNullForMissingKey
  - name: shouldEvictLeastRecentlyUsed
  - name: shouldUpdateExistingKey
```

### 与代码的关系

```
OpenSpec 文件
  ↓ 解析
接口定义（Java Interface）
  ↓ 实现
具体实现类
  ↓ 验证
测试用例
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 版本管理 | 语义化版本（1.0.0） |
| 单一职责 | 一个 Spec 文件描述一个模块 |
| 可执行 | Spec 可以直接生成测试骨架 |
| 文档化 | Spec 本身就是最好的文档 |
| 与 CI 集成 | Spec 变更触发代码重新生成 |

## 面试/实战要点

**Q: OpenSpec 和 Swagger/OpenAPI 有什么区别？**
A: OpenAPI 专注于 REST API；OpenSpec 更通用，可以描述任何接口（包括本地函数、类库）。OpenSpec 更侧重于行为规格，OpenAPI 更侧重于 HTTP 协议。

**Q: 如何在团队中推广 OpenSpec？**
A: 从公共库开始 → 逐步扩展到 API → 与 CI/CD 集成 → 培训和文档。

---

# 13. Superpowers — 超级能力

## 是什么

Superpowers 是 Codex 的"高级特性集"——超越基础对话的特殊能力。
包括可视化、自动化、浏览器控制、文件生成等。

## 为什么

- **超越文本**：不只是代码，还能生成文档、图表、演示
- **自动化**：定时任务、监控、提醒
- **交互式**：浏览器控制、GUI 操作
- **生产力**：大幅提升工作效率

## 怎么用

### Superpowers 分类

```
┌─────────────────────────────────────┐
│         Superpowers                 │
├──────────┬──────────┬───────────────┤
│  生成类   │  控制类   │   自动类      │
├──────────┼──────────┼───────────────┤
│ 文档生成  │ 浏览器    │ 定时任务      │
│ 图表生成  │ 终端      │ 监控告警      │
│ 图片生成  │ 桌面应用  │ 自动回复      │
│ 演示文稿  │ 文件系统  │ 数据同步      │
└──────────┴──────────┴───────────────┘
```

### 文档生成

```markdown
# 生成 Word 文档
请生成一份项目周报，包含本周完成、下周计划、风险项。

# 生成 Excel 报表
请生成一份销售数据报表，包含图表和公式。

# 生成演示文稿
请生成一份 10 页的项目汇报 PPT。
```

### 浏览器控制

```markdown
# 打开网页并截图
请打开 https://example.com 并截图。

# 自动化测试
请登录系统，执行以下操作并截图：
1. 点击"新建订单"
2. 填写表单
3. 提交并验证结果
```

### 可视化

```markdown
# 生成交互式图表
请用 Mermaid 画一个订单状态机图。

# 生成数据可视化
请生成一个柱状图，展示各模块测试覆盖率。
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 按需使用 | 不要为了用而用，解决真实问题 |
| 验证输出 | 生成的内容要人工审查 |
| 保存资产 | 生成的文档、图表要归档 |
| 自动化优先 | 重复性工作用自动化替代 |
| 安全意识 | 浏览器控制要注意敏感操作 |

## 面试/实战要点

**Q: Superpowers 和普通工具调用有什么区别？**
A: Superpowers 是高层能力（如"生成一份报表"），工具调用是底层操作（如"调用 Excel API"）。Superpowers 封装了多个工具调用和推理步骤。

**Q: 如何安全地使用浏览器控制？**
A: 最小权限 + 操作确认 + 日志记录 + 避免敏感操作（如支付）。

---

# 14. Context — 上下文工程

## 是什么

Context 是 Agent 当前"看到"的所有信息——用户输入、文件内容、工具结果、历史对话等。
Context Engineering 是如何高效管理和利用这些信息的工程实践。

## 为什么

- **质量**：好的 Context → 好的回答
- **效率**：减少无关信息，降低 Token 消耗
- **准确性**：相关上下文减少幻觉
- **可控性**：理解 Agent 看到了什么

## 怎么用

### Context 组成

```
┌─────────────────────────────────────┐
│         Context Window              │
├─────────────────────────────────────┤
│ System Prompt        (系统指令)     │
│ Rules                (规则)         │
│ Skills               (技能)         │
│ Memory               (记忆)         │
├─────────────────────────────────────┤
│ User Message         (用户输入)     │
│ File Contents        (文件内容)     │
│ Tool Results         (工具结果)     │
│ Conversation History (对话历史)     │
└─────────────────────────────────────┘
```

### Context 管理策略

```
策略 1: 摘要压缩
  长对话 → 定期摘要 → 保留关键信息

策略 2: 分层加载
  核心信息 → 始终加载
  次要信息 → 按需加载

策略 3: 窗口滑动
  保留最近 N 轮对话
  更早的对话丢弃或摘要

策略 4: 检索增强（RAG）
  从知识库中检索相关文档
  只加载相关片段
```

### 优化 Context 的方法

```markdown
## 减少噪音
- 删除无关文件内容
- 精简工具返回结果
- 去除重复信息

## 增加信号
- 提供明确的文件路径
- 添加相关的代码片段
- 包含错误信息和堆栈

## 结构化
- 用 Markdown 格式组织
- 分层展示信息
- 用表格对比数据
```

## 最佳实践

| 实践 | 说明 |
|------|------|
| 最小化 | 只加载必要的上下文 |
| 结构化 | 用清晰的格式组织信息 |
| 相关性 | 优先加载与任务相关的内容 |
| 及时更新 | 过时的上下文要及时替换 |
| 可观测 | 理解 Agent 看到了什么 |

## 面试/实战要点

**Q: Context 和 Memory 有什么区别？**
A: Context 是当前会话的、临时的；Memory 是跨会话的、持久的。Context 是"看到的"，Memory 是"记住的"。

**Q: 如何处理 Context 窗口限制？**
A: 摘要压缩 + 分层加载 + 检索增强（RAG）+ 窗口滑动。

---

# 总结：14 个主题的关系

```
┌─────────────────────────────────────────────────────┐
│                    Agent 核心                        │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐             │
│  │  Rule   │  │  Skill  │  │   MCP   │             │
│  │ (约束)   │  │ (能力)   │  │ (连接)   │             │
│  └────┬────┘  └────┬────┘  └────┬────┘             │
│       └────────────┼────────────┘                   │
│                    ↓                                │
│  ┌─────────────────────────────────────────────┐   │
│  │              Agent 推理引擎                   │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────────┐ │   │
│  │  │ Context │  │ Memory  │  │   Plan      │ │   │
│  │  │ (当前)   │  │ (历史)   │  │   (未来)    │ │   │
│  │  └─────────┘  └─────────┘  └─────────────┘ │   │
│  └─────────────────────────────────────────────┘   │
│                    ↓                                │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐             │
│  │  Hooks  │  │SubAgent │  │Worktree │             │
│  │ (拦截)   │  │ (协作)   │  │ (并行)   │             │
│  └─────────┘  └─────────┘  └─────────┘             │
└─────────────────────────────────────────────────────┘
         ↑                    ↑
    ┌────┴────┐          ┌───┴────┐
    │  TDD    │          │Super-  │
    │  SDD    │          │powers  │
    │OpenSpec │          │        │
    │(方法论)  │          │(能力)   │
    └─────────┘          └────────┘
```

## 核心理念

| 主题 | 一句话总结 |
|------|-----------|
| Rule | 定义边界，告诉 Agent 什么不能做 |
| Skill | 扩展能力，告诉 Agent 什么能做 |
| MCP | 连接外部，让 Agent 访问真实世界 |
| Memory | 积累经验，让 Agent 越用越聪明 |
| Hooks | 自动检查，让 Agent 不犯低级错误 |
| SubAgent | 分身协作，让 Agent 能并行处理 |
| Worktree | 隔离环境，让 Agent 能多线开发 |
| Agent | 核心架构，感知-推理-行动的循环 |
| Plan | 计划先行，让复杂任务可控可追溯 |
| TDD | 测试先行，让代码质量有保障 |
| SDD | 规格先行，让需求无歧义 |
| OpenSpec | 标准化规格，让规格可被工具处理 |
| Superpowers | 超越代码，让 Agent 能生成文档/图表 |
| Context | 信息管理，让 Agent 看到该看的 |
