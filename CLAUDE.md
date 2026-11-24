# Code Steering

Rules for all code written in this repository.

## 1. Clean code
- Small, focused functions and classes, each doing one thing.
- No duplication, no dead code, no magic values (use named constants).
- Keep nesting shallow; prefer early returns.

## 2. Self-explaining code
- Use meaningful, intention-revealing names for classes, methods, variables, and packages.
- Do not write comments unless they are really needed (non-obvious *why*, external constraints, workarounds). Never comment *what* the code does; rename or extract instead.

## 3. SOLID
- **S**ingle responsibility: one reason to change per class/module.
- **O**pen/closed: extend behavior through new implementations, not by editing existing ones.
- **L**iskov substitution: subtypes must be usable wherever their base type is expected.
- **I**nterface segregation: small, role-specific interfaces.
- **D**ependency inversion: depend on abstractions; inject dependencies.

## 4. Reuse before building
Before writing code, check in this order:
1. An official library that already solves the problem.
2. A built-in feature of the project's framework (Spring, Angular/React, etc.).
3. An existing utility or pattern already in this codebase.

Write custom code only when none of these fit.

## 5. Maintainability and reusability first
- Prefer the simple, readable solution over a clever or compact one.
- Design components so they can be reused and changed without touching unrelated code.
- Follow the conventions already present in the codebase; do not introduce a second style.

## 6. Unit tests
- Write unit tests for every piece of new code.
- Tests must be deterministic, isolated, and cover behavior (including edge cases and failures), not implementation details.
- Follow the test framework and conventions already used in the module.

## 7. Building and testing
- Always build and test with `./build.sh`, never with plain `mvn`. It uses a clean Maven settings file and its own local repository (`.mvn/repository`), which avoids corrupt artifacts from the machine-wide `~/.m2`.
- `./build.sh` with no arguments runs `clean package`. Any other arguments are passed through to Maven, for example `./build.sh test` or `./build.sh -pl backend/kafkatower-app -am package -DskipTests`.
