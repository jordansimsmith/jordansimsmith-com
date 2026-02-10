# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project context

Before implementing changes in any service project (e.g., auction_tracker_api, immersion_tracker_api), always read that service's README.md first.

For service README authoring and update standards, use the project skill at `.cursor/skills/service-readme-authoring/SKILL.md`.

## Build and test commands

- Build all: `bazel build //...`
- Build specific API: `bazel build //service_name_api:all` (e.g., `//immersion_tracker_api:all`)
- Test all: `bazel test //...`
- Test specific API: `bazel test //service_name_api:all`
- Test single class: `bazel test --test_filter=ClassName //service_name_api:target`
- Test single method: `bazel test --test_filter=ClassName.methodName //service_name_api:target`
- Format code: `bazel run //:format`
- Tidy Bazel modules: `bazel mod tidy`

## Steps after task completion

Always run these commands after completing a task:

1. First, perform a code review of generated code
2. Then, run tests to ensure everything works: `bazel test //...` (or scope to directories changed, e.g., `bazel test //auction_tracker_api:all`)
3. Then, tidy up Bazel module dependencies: `bazel mod tidy`
4. Finally, format all code files: `bazel run //:format`

These commands should be run:

- After completing a feature or bug fix
- Before creating a pull request
- After addressing review comments that involved code changes
- Any time significant code modifications have been made

## Code review checklist

When performing the code review step, check for:

- **Import optimization**: Ensure all imports are used instead of fully qualified names (e.g., `import java.util.ArrayList;` instead of `java.util.ArrayList`)
- **Comment quality**: Remove comments that don't add value over what is already communicated in the code
- **Code clarity**: Verify that the code is self-explanatory and follows established patterns
- **Style consistency**: Check that the code follows all style guidelines and matches surrounding code
- **Unnecessary complexity**: Simplify code where possible without losing functionality

## Bazel guidelines

- Use kebab-case (skewer-case) for all Bazel target names (e.g., `update-fixtures-handler`)
- Group targets in BUILD.bazel files with production code first (lib, handlers) followed by tests (test-lib, unit-tests, integration-tests)
- When build or test errors occur, fix the underlying code issues rather than modifying tests to accept buggy code
- Address build errors first, then test failures

## Java style guidelines

- Use Google Java Format (enforced by formatter)
- Use `var` for variables when type is obvious from assignment
- Explicit type declarations only when not clear from right side of assignment
- Do not include comments on classes and methods - code should be self-explanatory
- No Javadoc on classes or methods
- Code comments should only be used when something is truly non-obvious and needs explaining
- All comments should be lowercase with rare exceptions for proper nouns
- Exception handling: Public methods should call private implementation methods that can throw
- Public methods should catch and wrap exceptions into runtime exceptions
- If a dependency doesn't need to be shared between providers, inline its creation in the provider method
- Prefer inline method content rather than many small helper private methods - keeps code readable and avoids unnecessary indirection
- Don't add private methods when the logic is simple and used only once - inline it
- Let exceptions bubble up naturally when appropriate instead of unnecessarily catching them
- Always use imports instead of fully qualified names (e.g., `import java.util.ArrayList;` instead of `java.util.ArrayList`)
- Method naming: Use `get` prefix for methods that return a single item, use `find` prefix for methods that return multiple items (collections like List, Set, Collection)

## Testing guidelines

- Test naming: `Test.java` (unit), `IntegrationTest.java` (integration), `E2ETest.java` (end-to-end)
- Test method naming: camelCase, no underscores, use pattern methodNameShouldActionWhenCondition
- Test structure: Organize with "arrange", "act", "assert" comments (always lowercase)
- Use AssertJ assertions with static imports: `import static org.assertj.core.api.Assertions.assertThat;`
- Only use the `.as()` method when the assertion is not self-explanatory
- Unit tests: No network dependencies, use fakes over mocks
- Integration tests: Use local dependencies (Testcontainers), test component interactions
- E2E tests: Build local stack of complete system, test real user workflows
- With DynamoDB test containers, no need to clean up data after tests - containers are reset between tests
- Maintain a testing pyramid: many unit tests, fewer integration tests, fewest E2E tests

## TDD approach

- Write tests before implementation code
- Follow steps: stub implementation, write integration tests, ensure it builds, implement feature, write unit tests
- Keep iterations minimal with working, tested code
- Prioritize integration tests for top-level interfaces, then unit tests for internals
- Make minimal changes at each step to enable tight iteration on small code changes
- Build incrementally, releasing small chunks of working, tested functionality
- Complete one full cycle before starting the next feature iteration
- Prefer multiple small PRs over a single large PR when possible

## Service consistency

- Check other services for corresponding boilerplate or similar functionality
- Use existing implementations as examples for new code
- Follow established style and structure precisely
- Don't invent new patterns when existing ones can be adapted
- Use snake_case for DynamoDB attribute names (e.g., `home_team`, `date_time`)
- Before starting implementation, identify similar features in existing services
- During implementation, maintain consistent naming conventions across similar components
- Preserve method signatures and parameter ordering where applicable
- Respect existing error handling and logging patterns

## Documentation standards

- Service documentation belongs in `README.md` at the root of each service directory.
- Use sentence casing for README headings (only capitalize first word and proper nouns).
- Use `.cursor/skills/service-readme-authoring/SKILL.md` as the canonical README structure/workflow standard.
