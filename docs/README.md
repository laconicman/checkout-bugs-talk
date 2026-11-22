# Findings: this fork against the talk's repository

[chernybro/checkout-bugs-talk](https://github.com/chernybro/checkout-bugs-talk) is the companion
code of the talk *When Checkout Checks Out*: three checkout bugs, each reproduced by tests and
followed by the fix the team shipped. This fork keeps all of that and asks one more question of
each story: is there a fix closer to the cause?

Nothing of the talk's was removed. Each of our fixes sits next to the talk's, with its own tests,
so the two can be read and run side by side.

## At a glance

| | Upstream `main` (`a6d80b5`) | This fork's `main` |
|---|---|---|
| CI | Never reached Gradle: fails in SDK setup after 29 s | Green; optional emulator job |
| Unit tests | 28 in 8 classes. They pass, but upstream's CI never ran them. | 47 in 10 classes |
| On an Android runtime | none | 9 tests on an API 35 emulator |
| Story 1, frozen app | A marker in the coroutine context rejects network calls at run time | Also: who really waits, the marker's blind spot, a transaction body that cannot suspend |
| Story 2, phantom items | Walk the child tables by hand; or sweep orphans once | Also: the sweep repeats until clean and runs at the end of every migration; a real Room upgrade test |
| Story 3, white screen | Imperative tiered loading | Also: every input starts as "not yet" and one `combine` derives the frame; a test that could not fail now can |

To see the whole difference:

```sh
git remote add upstream https://github.com/chernybro/checkout-bugs-talk.git
git fetch upstream
git diff upstream/main main -- . ':!docs' ':!.devin' ':!REVIEW.md'
```

or [compare on GitHub](https://github.com/chernybro/checkout-bugs-talk/compare/main...laconicman:checkout-bugs-talk:main).
That is 17 files, 860 lines added and 18 removed, most of it tests.

## Pages

| Page | Read it for |
|---|---|
| [Story 1: the frozen app](story-1-frozen-app.md) | Which calls wait for an open transaction and why; marker versus a transaction that cannot suspend |
| [Story 2: phantom items](story-2-phantom-items.md) | Where `foreign_keys` really stands during a Room migration; walker versus sweep |
| [Story 3: the white screen](story-3-white-screen.md) | Why `combine` stays silent; tiered loading versus inputs that start as "not yet" |
| [Testing](testing.md) | Why each test exists and how it proves its claim; the three environments |
| [Gotchas](gotchas.md) | Every trap we stepped on, by layer, with where it is shown |
| [Changelog against upstream](changelog.md) | Every change, large and small, and what we got wrong on the way |
| [Sources](sources.md) | What was read, asked and measured, and how far each source can be trusted |
| [Evidence](evidence/README.md) | Raw logs and results behind the claims |

## How to read the claims

Every page separates three kinds of statement:

- **Measured**: a test in this repository or a command in [evidence](evidence/README.md) shows it.
- **Read**: taken from source code or documentation of the pinned versions, named in [Sources](sources.md).
- **Reasoned**: follows from the above but nothing here demonstrates it. These are marked.

## Status

- Merged here: eight reviewed pull requests, [#1](https://github.com/laconicman/checkout-bugs-talk/pull/1)
  to [#8](https://github.com/laconicman/checkout-bugs-talk/pull/8), in one merge commit. See the [changelog](changelog.md).
- Sent upstream: nothing yet. The branches are kept so that each change can be proposed on its own.
- Not verified anywhere: a physical device, API levels other than 35, Room with a `SQLiteDriver` set.
