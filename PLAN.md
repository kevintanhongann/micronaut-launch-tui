# Micronaut Launch TUI — Implementation Plan

A JBang-powered terminal UI for bootstrapping Micronaut projects directly into the current working directory, built with TamboUI. Replicates the [micronaut.io/launch](https://micronaut.io/launch) experience in the terminal.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                  MicronautLaunchTUI                      │
│                  (ToolkitApp main)                       │
├──────────────┬──────────────────┬────────────────────────┤
│  ConfigPanel │  FeaturesPanel   │  PreviewPanel          │
│  (left side) │  (center)        │  (right side/popup)    │
├──────────────┴──────────────────┴────────────────────────┤
│              LaunchApiClient (HTTP)                       │
│         talks to launch.micronaut.io REST API            │
├─────────────────────────────────────────────────────────┤
│              ProjectExtractor (ZIP → CWD)                │
│     downloads ZIP, extracts into ./{name}/ in-place      │
└─────────────────────────────────────────────────────────┘
```

## Tech Stack

| Component       | Choice                                              |
|-----------------|-----------------------------------------------------|
| Runtime         | JBang (single-file or multi-source Java)            |
| TUI Framework   | TamboUI Toolkit DSL (`dev.tamboui:tamboui-toolkit`)  |
| Backend         | JLine3 (`dev.tamboui:tamboui-jline3-backend`)        |
| HTTP Client     | `java.net.http.HttpClient` (JDK built-in)           |
| JSON Parsing    | `com.google.code.gson:gson` (lightweight, no config) |
| ZIP Extraction  | `java.util.zip.ZipInputStream` (JDK built-in)       |
| Java Version    | 21+                                                  |

## Micronaut Launch REST API Endpoints Used

| Endpoint | Purpose |
|----------|---------|
| `GET /select-options` | All dropdown options (type, lang, build, test, jdkVersion) with defaults |
| `GET /application-types/{type}/features?lang=&build=&test=&javaVersion=` | Available features for a given config |
| `GET /create/{type}/{name}?lang=&build=&test=&javaVersion=&features=` | Download project as ZIP (streamed, extracted in-place) |
| `GET /preview/{type}/{name}?lang=&build=&test=&javaVersion=&features=` | Preview generated file tree |
| `GET /diff/{type}/{name}?lang=&build=&test=&javaVersion=&features=` | Diff of selected features vs. base |

## UI Layout (Single-Screen Form)

```
┌─ Micronaut Launch TUI ──────────────────────────────────────────────┐
│                                                                     │
│  Application Type: [▾ Micronaut Application                    ]    │
│  Java Version:     [▾ 21                                       ]    │
│  Language:         ( ) Java    ( ) Groovy    ( ) Kotlin              │
│  Build Tool:       ( ) Gradle  ( ) Gradle Kotlin  ( ) Maven         │
│  Test Framework:   ( ) JUnit   ( ) Spock     ( ) Kotest              │
│  Name:             [ demo_________________________ ]                │
│  Base Package:     [ com.example___________________ ]               │
│                                                                     │
│  ┌─ Features (search: [___________]) ────────────────────────────┐  │
│  │  [ ] annotation-api          API                              │  │
│  │  [ ] graphql                 API                              │  │
│  │  [ ] openapi                 API                              │  │
│  │  [ ] flyway                  Database                         │  │
│  │  [ ] data-jpa                Database                         │  │
│  │  │ ▼ scroll...                                                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  Selected: annotation-api, flyway (2)                               │
│                                                                     │
│  [Bootstrap Project]   [Preview]   [Show Diff]   [Quit]             │
├─────────────────────────────────────────────────────────────────────┤
│  Target: /home/user/projects/demo/                                  │
│  Tab/↑↓: Navigate  Space: Toggle Feature  Enter: Bootstrap  q: Quit │
└─────────────────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: Project Bootstrap & API Client

**File:** `MicronautLaunchTUI.java` (single JBang source file to start)

1. **JBang header** with dependencies:
   ```java
   ///usr/bin/env jbang "$0" "$@" ; exit $?
   //DEPS dev.tamboui:tamboui-toolkit:0.2.0-SNAPSHOT
   //DEPS dev.tamboui:tamboui-jline3-backend:0.2.0-SNAPSHOT
   //DEPS com.google.code.gson:gson:2.11.0
   ```

2. **`LaunchApiClient`** — inner class or separate source:
   - Uses `java.net.http.HttpClient` to call `launch.micronaut.io`
   - `fetchSelectOptions()` → returns parsed `SelectOptions` record (types, langs, builds, tests, jdkVersions with defaults)
   - `fetchFeatures(type, lang, build, test, jdkVersion)` → returns `List<Feature>` with name, title, description, category
   - `generateProject(type, name, pkg, lang, build, test, jdkVersion, features)` → returns ZIP as `InputStream` for extraction
   - `fetchPreview(...)` → returns file tree as text
   - `fetchDiff(...)` → returns diff text

3. **`ProjectExtractor`** — handles in-place bootstrapping:
   - `extractToDirectory(InputStream zipStream, Path targetDir)` → extracts ZIP contents into `CWD/{name}/`
   - Uses `java.util.zip.ZipInputStream` — no extra dependencies
   - Creates the target directory if it doesn't exist
   - **Safeguards:**
     - Refuses to extract if `CWD/{name}/` already exists and is non-empty (prompt to overwrite or abort)
     - Validates ZIP entries to prevent path traversal (zip slip protection)
   - Sets executable bit on `gradlew` / `mvnw` after extraction
   - Returns a list of extracted file paths for the success summary

4. **Data model records:**
   ```java
   record SelectOptions(List<Option> types, List<Option> langs, List<Option> builds, List<Option> tests, List<Option> jdkVersions) {}
   record Option(String label, String value, String description) {}
   record Feature(String name, String title, String description, String category, boolean preview, boolean community) {}
   record ProjectConfig(String type, String jdkVersion, String lang, String build, String test, String name, String basePackage, List<String> features) {}
   ```

### Phase 2: Core TUI — Configuration Form

Using TamboUI Toolkit DSL (`ToolkitApp`):

1. **State management:**
   - `ProjectConfig` mutable state object holding all current selections
   - `List<Feature>` loaded from API (refreshed when type/lang/build/test change)
   - `Set<String> selectedFeatures`
   - `String featureSearchQuery`
   - `int focusedField` — tracks which form field has focus

2. **Form widgets:**
   - **Application Type** — `list(...)` as a cycling selector or custom select component (Tab/Enter to cycle through options)
   - **Java Version** — `list(...)` selector, 3 options (17, 21, 25)
   - **Language** — radio-style `tabs("Java", "Groovy", "Kotlin")` using TamboUI `tabs()` element
   - **Build Tool** — `tabs("Gradle", "Gradle Kotlin", "Maven")`
   - **Test Framework** — `tabs("JUnit", "Spock", "Kotest")`
   - **Name** — `textInput(nameState)` with default "demo"
   - **Base Package** — `textInput(packageState)` with default "com.example"

3. **Target directory display:**
   - Live-updating path label: `Target: /current/working/dir/{name}/`
   - Updates as the user types in the Name field
   - Shows warning icon if target directory already exists

4. **Layout:**
   - Top-level `column()` containing:
     - Header row with title + Micronaut version
     - Form rows using `row(label, widget)` for each field
     - Features panel (see Phase 3)
     - Action buttons row
     - Status bar with target path + help text

### Phase 3: Features Browser

1. **Feature list widget:**
   - Scrollable `list()` showing features filtered by search query
   - Each item: `[x] feature-name — category` (checkbox-style)
   - Space to toggle selection
   - Group by category with visual separators

2. **Search:**
   - `textInput(searchState)` at the top of features panel
   - Filters the feature list in real-time as you type

3. **Selected features summary:**
   - Text line below the list: "Selected: feat1, feat2 (N)"

4. **Dynamic refresh:**
   - When type/lang/build/test/jdkVersion changes, re-fetch features from API
   - Use background thread for HTTP call, update state when done

### Phase 4: Actions — Bootstrap, Preview, Diff

1. **Bootstrap Project (`Shift+Enter` or button):**
   - The primary action — generates and extracts the project in one step:
     1. Call `generateProject(...)` → get ZIP `InputStream`
     2. Call `extractToDirectory(stream, CWD/name)` → extract all files in-place
     3. Set executable permissions on wrapper scripts (`gradlew`, `mvnw`)
     4. Exit TUI and print a success summary to stdout:
        ```
        ✓ Project bootstrapped at /home/user/projects/demo/
        
          24 files extracted
          Build: Gradle Kotlin  |  Language: Java  |  Test: JUnit
          Features: openapi, flyway
        
          Next steps:
            cd demo
            ./gradlew run
        ```
   - If target directory exists and is non-empty, show a confirmation popup before overwriting
   - On error, stay in the TUI and show the error inline

2. **Preview (`Shift+P` or button):**
   - Call `fetchPreview(...)` → show file tree in a popup/overlay panel
   - Scrollable text view of generated files
   - Lets the user inspect what will be created before bootstrapping

3. **Diff (`Shift+D` or button):**
   - Call `fetchDiff(...)` → show diff in a popup panel
   - Syntax-highlighted diff (+ green, - red)

4. **Commands (`Shift+C`):**
   - Show equivalent `mn create-app` CLI command and cURL command

### Phase 5: Polish & UX

1. **Keyboard shortcuts** (match web UI where applicable):
   - `Shift+Enter` — Bootstrap project into CWD
   - `Shift+P` — Preview
   - `Shift+D` — Diff
   - `Shift+C` — Show CLI commands
   - `Tab` / `Shift+Tab` — Navigate between form fields
   - `↑/↓` — Navigate within lists/selects
   - `Space` — Toggle feature checkbox
   - `q` or `Ctrl+C` — Quit

2. **Color scheme:**
   - Use TamboUI CSS styling or inline colors
   - Match Micronaut brand: dark background, cyan/teal accents
   - Category labels in different colors

3. **Loading states:**
   - Show spinner/gauge while fetching from API or extracting project
   - "Bootstrapping project..." progress indicator during extraction

4. **Error handling:**
   - Network errors → show inline error message, allow retry
   - Target directory conflict → confirmation popup with overwrite/cancel
   - Invalid project name → highlight field with validation message

5. **Status bar:**
   - Bottom bar: target directory path + keyboard shortcuts help
   - Micronaut version display

## File Structure

For maintainability, split into multiple JBang source files:

```
micronaut-launch-tui/
├── MicronautLaunchTUI.java      # Main entry point (JBang //DEPS, //SOURCES)
├── LaunchApiClient.java          # HTTP client for launch.micronaut.io API
├── ProjectExtractor.java         # ZIP download → extract into CWD/{name}/
├── ProjectConfig.java            # Mutable state for project configuration
├── FeatureBrowser.java           # Feature list/search component
├── PreviewPopup.java             # Preview/Diff popup overlay
└── PLAN.md                       # This file
```

JBang multi-source support:
```java
//SOURCES LaunchApiClient.java
//SOURCES ProjectExtractor.java
//SOURCES ProjectConfig.java
//SOURCES FeatureBrowser.java
//SOURCES PreviewPopup.java
```

## Running

```bash
# Run directly — bootstraps a Micronaut project into the current directory
cd ~/projects
jbang MicronautLaunchTUI.java
# → creates ~/projects/demo/ with the full project

# Or install globally as a command
jbang app install --name mn-launch MicronautLaunchTUI.java
cd ~/projects
mn-launch
# → interactive TUI, then extracts project into ~/projects/{name}/
```

## Key Design Decisions

1. **Bootstrap in-place, not download ZIP** — The tool extracts the generated project directly into `CWD/{name}/`, ready to `cd` into and `./gradlew run`. No manual unzipping step. The TUI exits after bootstrapping so the user is back in their shell, ready to work.

2. **Toolkit DSL over TuiRunner** — Declarative approach with `ToolkitApp` gives us focus management, component events, and simpler layout for free.

3. **JDK HttpClient + ZipInputStream** — No need for extra dependencies for HTTP or ZIP handling. Only Gson is added for JSON parsing.

4. **Single-screen form** — Unlike a wizard, show everything at once (like the web UI). More efficient and allows quick changes.

5. **Lazy feature loading** — Fetch features only when config changes, cache by config hash to avoid redundant API calls.

6. **Popup overlays for Preview/Diff** — Keep the main form visible, overlay results in a modal panel.

7. **Clean exit with summary** — After bootstrapping, the TUI exits and prints a plain-text summary to stdout (not TUI-rendered), so it's visible in the terminal history and pipeable.

## Bootstrap Flow

```
User runs: jbang MicronautLaunchTUI.java  (or mn-launch)
              │
              ▼
    ┌─ TUI launches ─────────────────────────┐
    │  1. Fetch /select-options from API      │
    │  2. Show config form with defaults      │
    │  3. User configures project             │
    │  4. User browses/selects features       │
    │  5. User optionally previews/diffs      │
    │  6. User presses Shift+Enter            │
    └────────────────┬────────────────────────┘
                     │
                     ▼
    ┌─ Bootstrap ────────────────────────────┐
    │  1. GET /create/{type}/{name}?...      │
    │  2. Stream ZIP response                │
    │  3. Extract into CWD/{name}/           │
    │  4. chmod +x gradlew/mvnw              │
    │  5. Exit TUI                           │
    └────────────────┬───────────────────────┘
                     │
                     ▼
    ┌─ stdout summary ──────────────────────┐
    │  ✓ Project bootstrapped at ./demo/    │
    │    24 files extracted                  │
    │    cd demo && ./gradlew run            │
    └───────────────────────────────────────┘
```
