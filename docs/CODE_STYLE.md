# AgentCall — Code Style Guide

> **Canonical references:** [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md) | [DEVELOPMENT_GUIDE.md](../DEVELOPMENT_GUIDE.md)

---

## TypeScript

### Formatting
- 2-space indentation
- Max line length: 100 characters
- Semicolons required
- Single quotes for strings
- Trailing commas in multiline objects/arrays
- No trailing whitespace

### Naming
- **Interfaces:** PascalCase, `I` prefix for inversion-of-control (`IAuthService`)
- **Types:** PascalCase (`CallStatus`, `UserRole`)
- **Functions:** camelCase (`getUserById`, `createCall`)
- **Constants:** UPPER_SNAKE_CASE (`MAX_RETRY_COUNT`)
- **Files:** kebab-case (`auth-service.ts`, `event-bus.ts`)
- **Classes:** PascalCase (`AuthService`, `CallManager`)

### Imports
```
// Explicit imports only — no barrel files
import { AuthService } from '../auth/auth-service';
import type { IAuthConfig } from '../auth/types';
```

### Types
- Strict mode required
- No `any` — use `unknown` when type is unknown
- Prefer interfaces over type aliases for object shapes
- Use `type` for unions, intersections, and primitives

### Functions
- Single responsibility (<50 lines preferred)
- Explicit return type annotations
- Async functions use `Promise<T>` return type
- No overloads unless necessary

### Error Handling
- Use structured error classes
- Never `throw` raw strings or objects
- Always handle promise rejections

## Kotlin (Android)

### Formatting
- 4-space indentation
- Max line length: 100 characters
- Trailing comma in multiline expressions
- No wildcard imports

### Naming
- **Classes:** PascalCase (`CallService`, `MainActivity`)
- **Functions:** camelCase (`getTranscript`, `sendMessage`)
- **Constants:** UPPER_SNAKE_CASE (`DEFAULT_HOST`, `MAX_RETRIES`)
- **Composables:** PascalCase (`CallScreen`, `HomeScreen`)

### Conventions
- `val` over `var` unless required
- StateFlow for ViewModel state, not MutableState
- Hilt for DI everywhere
- `when` expressions exhaustive
- Data classes for models

## General
- No dead code, commented-out code, or `console.log`
- ESLint + Prettier enforced via pre-commit hooks
- Format on save enabled
