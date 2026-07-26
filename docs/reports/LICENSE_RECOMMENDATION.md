# License Recommendation for AgentCall

## Current Status

The README.md declares **MIT** as the license, but no `LICENSE` file exists in the repository root. A LICENSE file is required for any public GitHub repository.

## Suitable Options

### MIT (Recommended)

- **Best fit for:** Open-source projects that want maximum adoption
- **Permissions:** Commercial use, modification, distribution, private use, sublicensing
- **Conditions:** Include the original copyright notice
- **Limitations:** No liability, no warranty
- **Why recommended:** AgentCall's README already declares MIT. The project philosophy is "Free First." MIT is the most permissive license and aligns with the goal of broad AI integration. It is the standard choice for Node.js/TypeScript open-source projects.

### Apache-2.0

- **Best fit for:** Projects that want patent protection for contributors
- **Permissions:** Same as MIT plus express patent rights
- **Conditions:** Include copyright notice, state changes
- **Limitations:** No liability, no warranty
- **Why not:** Less compatible with permissive ecosystems. Adds complexity without clear benefit for a communication platform.

### GPL-3.0

- **Best fit for:** Projects that want to ensure derivatives remain open-source
- **Permissions:** Commercial use, modification, distribution
- **Conditions:** Disclose source, include original, state changes, same license
- **Limitations:** No liability, no warranty, copyleft
- **Why not:** Copyleft may deter AI companies and commercial adopters from integrating AgentCall. Contradicts the "AI Agnostic" philosophy.

## Recommendation

**MIT** — it is already declared in README.md, aligns with the project's "Free First" philosophy, and is the standard for open-source Node.js/TypeScript projects. Create a `LICENSE` file with the standard MIT license text:

```text
MIT License

Copyright (c) 2026 AgentCall

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Action Required

1. Create `LICENSE` file with the MIT license text
2. Set the copyright holder to the appropriate entity
