# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test commands

- Build all: `bazel build //...`
- Build specific API: `bazel build //service_name_api:all` (e.g., `//immersion_tracker_api:all`)
- Test all: `bazel test --test_env=TVDB_API_KEY //...`
- Test specific API: `bazel test --test_env=TVDB_API_KEY //service_name_api:all`
- Test single class: `bazel test --test_filter=ClassName //service_name_api:target`
- Test single method: `bazel test --test_filter=ClassName.methodName //service_name_api:target`
- Format code: `bazel run //:format`
- Tidy Bazel modules: `bazel mod tidy`

## Format changes after task completion

Always run these commands after completing a task:

1. First, tidy up Bazel module dependencies: `bazel mod tidy`
2. Then format all code files: `bazel run //:format`

These commands should be run:

- After completing a feature or bug fix
- Before creating a pull request
- After addressing review comments that involved code changes
- Any time significant code modifications have been made

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
- Don't add private methods when the logic is simple and used only once - inline it
- Let exceptions bubble up naturally when appropriate instead of unnecessarily catching them
- Always use imports instead of fully qualified names (e.g., `import java.util.ArrayList;` instead of `java.util.ArrayList`)

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

- Service documentation in README.md at root of service directory
- Use sentence casing for headings (only capitalize first word and proper nouns)
- Follow structure: service name, overview, system architecture, requirements, implementation details
- Include Mermaid diagrams for system architecture
- Service overview should be exactly one sentence that clearly describes what the service does
- System architecture section must include a Mermaid diagram showing the main components and interactions
- Requirements section should use bullet points and include both functional and non-functional requirements
- Implementation details should include technologies used, design patterns, and notable code structures
