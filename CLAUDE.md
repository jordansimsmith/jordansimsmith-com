/m# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test commands

- Build all: `bazel build //...`
- Build specific API: `bazel build //service_name_api:all` (e.g., `//immersion_tracker_api:all`)
- Test all: `bazel test --test_env=TVDB_API_KEY --test_output=errors --verbose_failures //...`
- Test specific API: `bazel test --test_env=TVDB_API_KEY --test_output=errors --verbose_failures //service_name_api:all`
- Test single class: `bazel test --test_filter=ClassName //service_name_api:target`
- Test single method: `bazel test --test_filter=ClassName.methodName //service_name_api:target`
- Format code: `bazel run //:format`
- Tidy Bazel modules: `bazel mod tidy`

## Java style guidelines

- Use Google Java Format (enforced by formatter)
- Use `var` for variables when type is obvious from assignment
- Explicit type declarations only when not clear from right side of assignment
- Comments should be minimal, lowercase, and explain "why" not "what"
- No Javadoc on classes unless strictly necessary (complex behavior/design decisions)
- Public APIs consumed by other teams should have appropriate Javadoc
- Exception handling: Public methods should call private implementation methods that can throw
- Public methods should catch and wrap exceptions into runtime exceptions

## Testing guidelines

- Test naming: `Test.java` (unit), `IntegrationTest.java` (integration), `E2ETest.java` (end-to-end)
- Test method naming: camelCase, no underscores, describe scenario and expected outcome
- Test structure: Organize with "arrange", "act", "assert" comments
- Use AssertJ assertions with static imports: `import static org.assertj.core.api.Assertions.assertThat;`
- Unit tests: No network dependencies, use fakes over mocks
- Integration tests: Use local dependencies (Testcontainers), test component interactions
- E2E tests: Build local stack of complete system, test real user workflows

## TDD approach

- Write tests before implementation code
- Follow steps: stub implementation, write integration tests, ensure it builds, implement feature, write unit tests
- Keep iterations minimal with working, tested code
- Prioritize integration tests for top-level interfaces, then unit tests for internals

## Service consistency

- Check other services for corresponding boilerplate or similar functionality
- Use existing implementations as examples for new code
- Follow established style and structure precisely
- Don't invent new patterns when existing ones can be adapted

## Documentation standards

- Service documentation in README.md at root of service directory
- Use sentence casing for headings (only capitalize first word and proper nouns)
- Follow structure: service name, overview, system architecture, requirements, implementation details
- Include Mermaid diagrams for system architecture