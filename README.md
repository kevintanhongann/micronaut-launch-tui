# Micronaut Launch TUI

A JBang-powered terminal UI for bootstrapping Micronaut projects directly into the current working directory. Replicates the [micronaut.io/launch](https://micronaut.io/launch) experience in the terminal.

## Features

- Interactive terminal UI with keyboard and mouse support
- Configure application type, Java version, language, build tool, and test framework
- Search and select features from the Micronaut ecosystem
- Preview project structure and diff before bootstrapping
- Extracts project directly into the current working directory

## Requirements

- [JBang](https://jbang.dev/) (version 0.97+)
- Java 21+

## Installation

### From JBang Catalog (recommended)

Run directly without cloning:

```bash
jbang mn-launch@kevintanhongann/micronaut-launch-tui
```

Install globally as a command:

```bash
jbang app install mn-launch@kevintanhongann/micronaut-launch-tui
```

Then simply run:

```bash
mn-launch
```

### From source

Clone the repository and run directly:

```bash
jbang MicronautLaunchTUI.java
```

Or install locally:

```bash
jbang app install --name mn-launch MicronautLaunchTUI.java
```

## Usage

1. Run the command:
   ```bash
   mn-launch
   # or without installing:
   jbang mn-launch@kevintanhongann/micronaut-launch-tui
   ```

2. Configure your project:
   - Select application type, Java version, language, build tool, and test framework
   - Enter project name and base package
   - Search and select features

3. Press **Shift+Enter** to bootstrap the project

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Shift+Enter` | Bootstrap project |
| `Shift+P` | Preview project structure |
| `Shift+D` | Show feature diff |
| `Shift+C` | Show CLI commands |
| `Tab` / `Shift+Tab` | Navigate between fields |
| `Arrows` | Navigate within lists |
| `Space` | Toggle feature selection |
| `q` | Quit |

## Building

The project uses JBang with multi-source files:

- `MicronautLaunchTUI.java` - Main entry point
- `LaunchApiClient.java` - HTTP client for launch.micronaut.io API
- `ProjectExtractor.java` - ZIP extraction utilities
- `ProjectConfig.java` - Configuration model

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
