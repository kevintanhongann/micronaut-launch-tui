///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.11.0
//REPOS central=https://repo1.maven.org/maven2/
//DEPS dev.tamboui:tamboui-toolkit:0.1.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.1.0
//SOURCES ProjectConfig.java
//SOURCES LaunchApiClient.java
//SOURCES ProjectExtractor.java

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.form.SelectFieldState;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.tabs.TabsState;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static dev.tamboui.toolkit.Toolkit.*;

public class MicronautLaunchTUI extends ToolkitApp {
    private static final String APP_VERSION = "v1.0.0";
    private static final String DEFAULT_NAME = "demo";
    private static final String DEFAULT_PACKAGE = "com.example";
    private static final String DEFAULT_TYPE = "default";
    private static final String DEFAULT_JAVA_VERSION = "21";
    private static final String DEFAULT_LANG = "java";
    private static final String DEFAULT_BUILD = "gradle";
    private static final String DEFAULT_TEST = "junit";

    private static final String NAME_INPUT_ID = "name-input";
    private static final String PACKAGE_INPUT_ID = "package-input";
    private static final String SEARCH_INPUT_ID = "search-input";

    private final LaunchApiClient apiClient = new LaunchApiClient();
    private final ProjectExtractor extractor = new ProjectExtractor();
    private final Path cwd = Paths.get("").toAbsolutePath().normalize();

    private SelectOptions selectOptions;

    private List<Option> typeOptions = defaultTypeOptions();
    private List<Option> jdkOptions = defaultJdkOptions();
    private List<Option> langOptions = defaultLangOptions();
    private List<Option> buildOptions = defaultBuildOptions();
    private List<Option> testOptions = defaultTestOptions();

    private SelectFieldState typeState = new SelectFieldState(optionLabels(typeOptions), 0);
    private SelectFieldState jdkState = new SelectFieldState(optionLabels(jdkOptions), 0);
    private TabsState langTabsState = new TabsState(0);
    private TabsState buildTabsState = new TabsState(0);
    private TabsState testTabsState = new TabsState(0);
    private final TextInputState nameState = new TextInputState(DEFAULT_NAME);
    private final TextInputState packageState = new TextInputState(DEFAULT_PACKAGE);
    private final TextInputState searchState = new TextInputState("");
    private final TextAreaState modalTextState = new TextAreaState("");

    private boolean loadingOptions = true;
    private boolean loadingFeatures = false;
    private boolean bootstrapping = false;
    private String errorMessage;

    private List<Feature> allFeatures = new ArrayList<>();
    private List<FeatureRow> featureRows = new ArrayList<>();
    private final Set<String> selectedFeatures = new LinkedHashSet<>();
    private int selectedFeatureRowIndex = 0;
    private String selectedFeatureName;
    private int featureListScrollOffset = 0;
    private int featureListViewportHeight = 0;

    private String lastFeatureConfigKey = "";
    private int featureRequestToken = 0;

    private ModalType modalType = ModalType.NONE;
    private String modalTitle = "";
    private ProjectConfig pendingOverwriteConfig;
    private Path pendingOverwriteDir;

    private enum ModalType {
        NONE,
        PREVIEW,
        DIFF,
        COMMANDS,
        OVERWRITE_CONFIRM
    }

    private record FeatureRow(String label, Feature feature) {
        static FeatureRow header(String category) {
            return new FeatureRow("--- " + category + " ---", null);
        }

        static FeatureRow feature(Feature feature, boolean selected) {
            String marker = selected ? "[x]" : "[ ]";
            String label = marker + " " + feature.name() + " - " + feature.title();
            return new FeatureRow(label, feature);
        }

        boolean selectable() {
            return feature != null;
        }
    }

    @Override
    protected void onStart() {
        registerGlobalKeyHandlers();
        loadSelectOptions();
    }

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder()
            .mouseCapture(true)
            .build();
    }

    @Override
    protected Element render() {
        maybeRefreshFeatures(false);
        rebuildFeatureRows();

        Path targetDir = cwd.resolve(currentProjectName());
        boolean targetConflict = extractor.targetDirectoryHasContent(targetDir);

        Element main = column(
            header(),
            configPanel(),
            featuresPanel(),
            actionsPanel(),
            statusBar(targetDir, targetConflict)
        ).spacing(1).margin(1);

        if (loadingOptions && selectOptions == null) {
            main = column(
                header(),
                panel("Loading",
                    row(
                        spinner(),
                        text("Fetching options from launch.micronaut.io ...").dim()
                    ).spacing(1)
                ).rounded().borderColor(Color.CYAN)
            ).spacing(1).margin(1);
        }

        if (errorMessage != null && !errorMessage.isBlank()) {
            main = column(
                panel("Error", text(errorMessage).red()).rounded().borderColor(Color.RED),
                main
            ).spacing(1).margin(1);
        }

        if (modalType != ModalType.NONE) {
            return stack(main, modal());
        }
        return main;
    }

    private Element header() {
        return row(
            text("Micronaut Launch TUI").bold().cyan(),
            spacer(),
            text(APP_VERSION).dim()
        );
    }

    private Element configPanel() {
        return panel("Project Configuration",
            column(
                row(
                    formField("Application Type", typeState)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill(),
                    formField("Java Version", jdkState)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .length(28)
                ).spacing(2),
                row(
                    text("Language").dim().length(18),
                    tabs(optionLabels(langOptions))
                        .state(langTabsState)
                        .highlightColor(Color.CYAN)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill()
                ).spacing(2),
                row(
                    text("Build Tool").dim().length(18),
                    tabs(optionLabels(buildOptions))
                        .state(buildTabsState)
                        .highlightColor(Color.CYAN)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill()
                ).spacing(2),
                row(
                    text("Test Framework").dim().length(18),
                    tabs(optionLabels(testOptions))
                        .state(testTabsState)
                        .highlightColor(Color.CYAN)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill()
                ).spacing(2),
                row(
                    formField("Name", nameState)
                        .id(NAME_INPUT_ID)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill(),
                    formField("Base Package", packageState)
                        .id(PACKAGE_INPUT_ID)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .fill()
                ).spacing(2)
            ).spacing(1)
        ).rounded().borderColor(Color.DARK_GRAY);
    }

    private Element featuresPanel() {
        boolean queryBlank = isSearchQueryBlank();
        List<String> lines = queryBlank
            ? List.of()
            : featureRows.isEmpty()
                ? List.of("No matching features.")
                : featureRows.stream().map(FeatureRow::label).toList();
        int boundedIndex = lines.isEmpty() ? 0 : Math.min(selectedFeatureRowIndex, lines.size() - 1);

        var featureList = list(lines)
            .id("feature-list")
            .focusable()
            .selected(boundedIndex)
            .title("Features")
            .rounded()
            .borderColor(Color.DARK_GRAY)
            .highlightColor(Color.CYAN)
            .scrollbar()
            .onKeyEvent(this::onFeatureListKey)
            .fill();
        featureList.onMouseEvent(event -> onFeatureListMouse(featureList, event));

        String selectedSummary = selectedFeatures.isEmpty()
            ? "Selected: none"
            : "Selected: " + String.join(", ", selectedFeatures) + " (" + selectedFeatures.size() + ")";

        Element loadingLine = loadingFeatures
            ? row(spinner(), text("Loading features...").dim()).spacing(1)
            : text(" ");

        return panel("Features",
            column(
                row(
                    text("Search").dim().length(8),
                    textInput(searchState)
                        .id(SEARCH_INPUT_ID)
                        .rounded()
                        .borderColor(Color.DARK_GRAY)
                        .placeholder("Filter by name, title, description, category")
                        .fill()
                ).spacing(1),
                featureList,
                queryBlank ? text("Enter keywords to search features.").dim() : text(" "),
                text(selectedSummary).dim(),
                loadingLine
            ).spacing(1)
        ).rounded().borderColor(Color.DARK_GRAY);
    }

    private Element actionsPanel() {
        Element statusLine = bootstrapping
            ? row(spinner(), text("Bootstrapping project...").yellow()).spacing(1)
            : text("Ready").green();

        return panel("Actions",
            column(
                text("[Shift+Enter] Bootstrap  [Shift+P] Preview  [Shift+D] Diff  [Shift+C] Commands  [q] Quit").cyan(),
                text("Focus the feature list and press Space to toggle a feature.").dim(),
                statusLine
            ).spacing(1)
        ).rounded().borderColor(Color.DARK_GRAY);
    }

    private Element statusBar(Path targetDir, boolean targetConflict) {
        String targetText = targetConflict
            ? "[!] Target exists: " + targetDir
            : "Target: " + targetDir;
        Color color = targetConflict ? Color.YELLOW : Color.GRAY;

        return row(
            text(targetText).fg(color),
            spacer(),
            text("Tab/Shift+Tab: navigate  Arrows: list/tabs  Enter: submit").dim()
        );
    }

    private Element modal() {
        if (modalType == ModalType.OVERWRITE_CONFIRM) {
            return dialog("Directory Already Exists",
                column(
                    text("Target directory is not empty:").yellow(),
                    text(String.valueOf(pendingOverwriteDir)),
                    text("Press Enter or Y to overwrite, Esc or N to cancel.").dim()
                ).spacing(1)
            ).rounded().borderColor(Color.YELLOW).width(100).length(10)
             .onConfirm(this::confirmOverwrite)
             .onCancel(this::cancelOverwrite);
        }

        return dialog(modalTitle,
            column(
                text("Esc/q to close").dim(),
                textArea(modalTextState)
                    .id("modal-text-area")
                    .showCursor(false)
                    .rounded()
                    .borderColor(Color.CYAN)
                    .fill()
            ).spacing(1)
        ).rounded().borderColor(Color.CYAN).width(120).length(36)
         .onConfirm(this::closeModal)
         .onCancel(this::closeModal);
    }

    private void registerGlobalKeyHandlers() {
        runner().eventRouter().addGlobalHandler(event -> {
            if (!(event instanceof KeyEvent keyEvent)) {
                return EventResult.UNHANDLED;
            }

            if (modalType == ModalType.OVERWRITE_CONFIRM) {
                if (keyEvent.isConfirm() || keyEvent.isCharIgnoreCase('y')) {
                    confirmOverwrite();
                    return EventResult.HANDLED;
                }
                if (keyEvent.isCancel() || keyEvent.isCharIgnoreCase('n')) {
                    cancelOverwrite();
                    return EventResult.HANDLED;
                }
                return EventResult.UNHANDLED;
            }

            if (modalType != ModalType.NONE) {
                if (keyEvent.isCancel() || keyEvent.isCtrlC() || keyEvent.isCharIgnoreCase('q')) {
                    closeModal();
                    return EventResult.HANDLED;
                }
                return EventResult.UNHANDLED;
            }

            if (keyEvent.isCtrlC()) {
                quit();
                return EventResult.HANDLED;
            }

            if (keyEvent.hasShift() && keyEvent.isConfirm()) {
                requestBootstrap();
                return EventResult.HANDLED;
            }

            if (!isTextInputFocused() && keyEvent.hasShift() && keyEvent.isCharIgnoreCase('p')) {
                showPreview();
                return EventResult.HANDLED;
            }

            if (!isTextInputFocused() && keyEvent.hasShift() && keyEvent.isCharIgnoreCase('d')) {
                showDiff();
                return EventResult.HANDLED;
            }

            if (!isTextInputFocused() && keyEvent.hasShift() && keyEvent.isCharIgnoreCase('c')) {
                showCommands();
                return EventResult.HANDLED;
            }

            if (!isTextInputFocused() && keyEvent.isCharIgnoreCase('q')) {
                quit();
                return EventResult.HANDLED;
            }

            return EventResult.UNHANDLED;
        });
    }

    private EventResult onFeatureListKey(KeyEvent keyEvent) {
        if (featureRows.isEmpty()) {
            return EventResult.UNHANDLED;
        }

        if (keyEvent.isUp()) {
            moveFeatureSelection(-1);
            return EventResult.HANDLED;
        }

        if (keyEvent.isDown()) {
            moveFeatureSelection(1);
            return EventResult.HANDLED;
        }

        if (keyEvent.isConfirm() || keyEvent.isChar(' ')) {
            toggleSelectedFeature();
            return EventResult.HANDLED;
        }

        return EventResult.UNHANDLED;
    }

    private EventResult onFeatureListMouse(dev.tamboui.toolkit.elements.ListElement<?> featureList, MouseEvent mouseEvent) {
        updateFeatureListViewport(featureList);

        if (featureRows.isEmpty()) {
            return EventResult.UNHANDLED;
        }

        if (mouseEvent.isScroll()) {
            if (mouseEvent.kind() == MouseEventKind.SCROLL_UP) {
                moveFeatureSelection(-1);
                return EventResult.HANDLED;
            }
            if (mouseEvent.kind() == MouseEventKind.SCROLL_DOWN) {
                moveFeatureSelection(1);
                return EventResult.HANDLED;
            }
        }

        if (!mouseEvent.isLeftButton() || !mouseEvent.isPress()) {
            return EventResult.UNHANDLED;
        }

        Integer clickedIndex = clickedFeatureRowIndex(featureList, mouseEvent);
        if (clickedIndex == null) {
            return EventResult.HANDLED;
        }

        FeatureRow clickedRow = featureRows.get(clickedIndex);
        Integer selectedIndex = clickedRow.selectable() ? clickedIndex : nearestSelectableRowIndex(clickedIndex);
        if (selectedIndex != null) {
            selectFeatureRow(selectedIndex);
        }
        if (clickedRow.selectable()) {
            toggleSelectedFeature();
        }

        return EventResult.HANDLED;
    }

    private void loadSelectOptions() {
        loadingOptions = true;
        errorMessage = null;

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return apiClient.fetchSelectOptions();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            })
            .whenComplete((options, throwable) -> onUiThread(() -> {
                loadingOptions = false;
                if (throwable != null) {
                    errorMessage = "Failed to fetch select options: " + rootMessage(throwable);
                    return;
                }
                applySelectOptions(options);
                errorMessage = null;
                lastFeatureConfigKey = "";
                maybeRefreshFeatures(true);
            }));
    }

    private void applySelectOptions(SelectOptions options) {
        selectOptions = options;

        typeOptions = withFallback(options.types(), defaultTypeOptions());
        jdkOptions = withFallback(options.jdkVersions(), defaultJdkOptions());
        langOptions = withFallback(options.langs(), defaultLangOptions());
        buildOptions = withFallback(options.builds(), defaultBuildOptions());
        testOptions = withFallback(options.tests(), defaultTestOptions());

        typeState = createSelectFieldState(typeOptions, DEFAULT_TYPE);
        jdkState = createSelectFieldState(jdkOptions, DEFAULT_JAVA_VERSION);
        langTabsState = new TabsState(indexOfOptionValue(langOptions, DEFAULT_LANG));
        buildTabsState = new TabsState(indexOfOptionValue(buildOptions, DEFAULT_BUILD));
        testTabsState = new TabsState(indexOfOptionValue(testOptions, DEFAULT_TEST));
        apiClient.clearCache();
    }

    private void maybeRefreshFeatures(boolean force) {
        if (loadingOptions || bootstrapping) {
            return;
        }

        ProjectConfig config = buildConfig();
        String configKey = String.join("|",
            config.getType(),
            config.getLang(),
            config.getBuild(),
            config.getTest(),
            config.getJdkVersion()
        );

        if (!force && configKey.equals(lastFeatureConfigKey)) {
            return;
        }

        lastFeatureConfigKey = configKey;
        loadingFeatures = true;
        int requestToken = ++featureRequestToken;

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return apiClient.fetchFeatures(
                        config.getType(),
                        config.getLang(),
                        config.getBuild(),
                        config.getTest(),
                        config.getJdkVersion()
                    );
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            })
            .whenComplete((features, throwable) -> onUiThread(() -> {
                if (requestToken != featureRequestToken) {
                    return;
                }
                loadingFeatures = false;
                if (throwable != null) {
                    allFeatures = new ArrayList<>();
                    featureRows = new ArrayList<>();
                    errorMessage = "Failed to load features: " + rootMessage(throwable);
                    return;
                }
                allFeatures = new ArrayList<>(features);
                Set<String> availableFeatures = allFeatures.stream()
                    .map(Feature::name)
                    .collect(Collectors.toSet());
                selectedFeatures.removeIf(featureName -> !availableFeatures.contains(featureName));
                errorMessage = null;
            }));
    }

    private void rebuildFeatureRows() {
        String query = currentSearchQuery();
        if (query.isBlank()) {
            featureRows = new ArrayList<>();
            fixFeatureSelection();
            return;
        }

        List<Feature> filtered = allFeatures.stream()
            .filter(feature -> feature.name().toLowerCase(Locale.ROOT).contains(query)
                || feature.title().toLowerCase(Locale.ROOT).contains(query)
                || feature.description().toLowerCase(Locale.ROOT).contains(query)
                || feature.category().toLowerCase(Locale.ROOT).contains(query))
            .sorted(Comparator.comparing(Feature::category).thenComparing(Feature::name))
            .toList();

        List<FeatureRow> rows = new ArrayList<>();
        String currentCategory = null;
        for (Feature feature : filtered) {
            if (!feature.category().equals(currentCategory)) {
                currentCategory = feature.category();
                rows.add(FeatureRow.header(currentCategory));
            }
            rows.add(FeatureRow.feature(feature, selectedFeatures.contains(feature.name())));
        }

        featureRows = rows;
        fixFeatureSelection();
    }

    private void fixFeatureSelection() {
        if (featureRows.isEmpty()) {
            selectedFeatureRowIndex = 0;
            selectedFeatureName = null;
            featureListScrollOffset = 0;
            return;
        }

        if (selectedFeatureName != null) {
            for (int i = 0; i < featureRows.size(); i++) {
                FeatureRow row = featureRows.get(i);
                if (row.selectable() && row.feature().name().equals(selectedFeatureName)) {
                    selectedFeatureRowIndex = i;
                    syncFeatureListScrollWithSelection();
                    return;
                }
            }
        }

        if (selectedFeatureRowIndex >= 0
            && selectedFeatureRowIndex < featureRows.size()
            && featureRows.get(selectedFeatureRowIndex).selectable()) {
            selectedFeatureName = featureRows.get(selectedFeatureRowIndex).feature().name();
            syncFeatureListScrollWithSelection();
            return;
        }

        for (int i = 0; i < featureRows.size(); i++) {
            if (featureRows.get(i).selectable()) {
                selectedFeatureRowIndex = i;
                selectedFeatureName = featureRows.get(i).feature().name();
                syncFeatureListScrollWithSelection();
                return;
            }
        }

        selectedFeatureRowIndex = 0;
        selectedFeatureName = null;
        featureListScrollOffset = 0;
    }

    private void moveFeatureSelection(int direction) {
        if (featureRows.isEmpty()) {
            return;
        }

        int candidate = selectedFeatureRowIndex;
        for (int i = 0; i < featureRows.size(); i++) {
            candidate += direction;
            if (candidate < 0) {
                candidate = featureRows.size() - 1;
            } else if (candidate >= featureRows.size()) {
                candidate = 0;
            }

            FeatureRow row = featureRows.get(candidate);
            if (row.selectable()) {
                selectedFeatureRowIndex = candidate;
                selectedFeatureName = row.feature().name();
                syncFeatureListScrollWithSelection();
                return;
            }
        }
    }

    private void selectFeatureRow(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= featureRows.size()) {
            return;
        }
        FeatureRow row = featureRows.get(rowIndex);
        if (!row.selectable()) {
            return;
        }
        selectedFeatureRowIndex = rowIndex;
        selectedFeatureName = row.feature().name();
        syncFeatureListScrollWithSelection();
    }

    private Integer nearestSelectableRowIndex(int fromRowIndex) {
        if (fromRowIndex < 0 || fromRowIndex >= featureRows.size()) {
            return null;
        }
        if (featureRows.get(fromRowIndex).selectable()) {
            return fromRowIndex;
        }

        for (int distance = 1; distance < featureRows.size(); distance++) {
            int lower = fromRowIndex + distance;
            if (lower < featureRows.size() && featureRows.get(lower).selectable()) {
                return lower;
            }
            int upper = fromRowIndex - distance;
            if (upper >= 0 && featureRows.get(upper).selectable()) {
                return upper;
            }
        }
        return null;
    }

    private Integer clickedFeatureRowIndex(dev.tamboui.toolkit.elements.ListElement<?> featureList, MouseEvent mouseEvent) {
        var area = featureList.renderedArea();
        if (area == null || !area.contains(mouseEvent.x(), mouseEvent.y())) {
            return null;
        }

        int viewportTop = area.y() + 1;
        int viewportBottomExclusive = area.y() + area.height() - 1;
        if (mouseEvent.y() < viewportTop || mouseEvent.y() >= viewportBottomExclusive) {
            return null;
        }

        int rowOffset = mouseEvent.y() - viewportTop;
        int rowIndex = featureListScrollOffset + rowOffset;
        if (rowIndex < 0 || rowIndex >= featureRows.size()) {
            return null;
        }
        return rowIndex;
    }

    private void updateFeatureListViewport(dev.tamboui.toolkit.elements.ListElement<?> featureList) {
        var area = featureList.renderedArea();
        if (area == null) {
            return;
        }

        int viewportHeight = Math.max(0, area.height() - 2);
        if (featureListViewportHeight != viewportHeight) {
            featureListViewportHeight = viewportHeight;
            syncFeatureListScrollWithSelection();
        }
    }

    private void syncFeatureListScrollWithSelection() {
        if (featureRows.isEmpty() || featureListViewportHeight <= 0) {
            featureListScrollOffset = 0;
            return;
        }

        if (selectedFeatureRowIndex < featureListScrollOffset) {
            featureListScrollOffset = selectedFeatureRowIndex;
        } else if (selectedFeatureRowIndex >= featureListScrollOffset + featureListViewportHeight) {
            featureListScrollOffset = selectedFeatureRowIndex - featureListViewportHeight + 1;
        }

        int maxOffset = Math.max(0, featureRows.size() - featureListViewportHeight);
        featureListScrollOffset = Math.max(0, Math.min(featureListScrollOffset, maxOffset));
    }

    private boolean isSearchQueryBlank() {
        return currentSearchQuery().isBlank();
    }

    private String currentSearchQuery() {
        return searchState.text() == null ? "" : searchState.text().trim().toLowerCase(Locale.ROOT);
    }

    private void toggleSelectedFeature() {
        if (featureRows.isEmpty()) {
            return;
        }
        if (selectedFeatureRowIndex < 0 || selectedFeatureRowIndex >= featureRows.size()) {
            return;
        }

        FeatureRow row = featureRows.get(selectedFeatureRowIndex);
        if (!row.selectable()) {
            return;
        }

        String featureName = row.feature().name();
        if (selectedFeatures.contains(featureName)) {
            selectedFeatures.remove(featureName);
        } else {
            selectedFeatures.add(featureName);
        }
    }

    private void requestBootstrap() {
        if (bootstrapping) {
            return;
        }

        ProjectConfig config = buildConfig();
        String projectName = config.getName();

        if (projectName == null || projectName.isBlank()) {
            errorMessage = "Project name is required.";
            return;
        }

        if (!projectName.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
            errorMessage = "Invalid project name. Use letters, numbers, underscore, and hyphen.";
            return;
        }

        Path targetDir = cwd.resolve(projectName).normalize();
        if (extractor.targetDirectoryHasContent(targetDir)) {
            pendingOverwriteConfig = config;
            pendingOverwriteDir = targetDir;
            modalType = ModalType.OVERWRITE_CONFIRM;
            return;
        }

        startBootstrap(config, false);
    }

    private void confirmOverwrite() {
        ProjectConfig config = pendingOverwriteConfig;
        closeModal();
        if (config != null) {
            startBootstrap(config, true);
        }
    }

    private void cancelOverwrite() {
        closeModal();
    }

    private void startBootstrap(ProjectConfig config, boolean overwrite) {
        bootstrapping = true;
        errorMessage = null;

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    Path targetDir = cwd.resolve(config.getName()).normalize();
                    if (overwrite) {
                        extractor.clearDirectory(targetDir);
                    }
                    try (InputStream zipStream = apiClient.generateProject(config)) {
                        ProjectExtractor.ExtractionResult result = extractor.extractToDirectory(zipStream, targetDir);
                        extractor.makeExecutable(targetDir);
                        return result;
                    }
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            })
            .whenComplete((result, throwable) -> onUiThread(() -> {
                bootstrapping = false;
                if (throwable != null) {
                    errorMessage = "Bootstrap failed: " + rootMessage(throwable);
                    return;
                }
                quit();
                printSuccessSummary(result, config);
            }));
    }

    private void showPreview() {
        if (bootstrapping || loadingOptions) {
            return;
        }
        fetchModalContent("Project Preview", false);
    }

    private void showDiff() {
        if (bootstrapping || loadingOptions) {
            return;
        }
        fetchModalContent("Feature Diff", true);
    }

    private void fetchModalContent(String title, boolean diff) {
        errorMessage = null;
        ProjectConfig config = buildConfig();

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return diff ? apiClient.fetchDiff(config) : apiClient.fetchPreview(config);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            })
            .whenComplete((content, throwable) -> onUiThread(() -> {
                if (throwable != null) {
                    errorMessage = "Failed to fetch " + (diff ? "diff" : "preview") + ": " + rootMessage(throwable);
                    return;
                }
                modalTitle = title;
                modalTextState.setText(content);
                modalType = diff ? ModalType.DIFF : ModalType.PREVIEW;
            }));
    }

    private void showCommands() {
        ProjectConfig config = buildConfig();
        String commandText = "mn create-app command\n\n"
            + apiClient.generateCliCommand(config)
            + "\n\ncURL command\n\n"
            + apiClient.generateCurlCommand(config);
        modalTitle = "CLI Commands";
        modalTextState.setText(commandText);
        modalType = ModalType.COMMANDS;
    }

    private void closeModal() {
        modalType = ModalType.NONE;
        modalTitle = "";
        pendingOverwriteConfig = null;
        pendingOverwriteDir = null;
    }

    private ProjectConfig buildConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setType(selectedSelectValue(typeState, typeOptions, DEFAULT_TYPE));
        config.setJdkVersion(selectedSelectValue(jdkState, jdkOptions, DEFAULT_JAVA_VERSION));
        config.setLang(selectedTabValue(langTabsState, langOptions, DEFAULT_LANG));
        config.setBuild(selectedTabValue(buildTabsState, buildOptions, DEFAULT_BUILD));
        config.setTest(selectedTabValue(testTabsState, testOptions, DEFAULT_TEST));
        config.setName(currentProjectName());
        config.setBasePackage(currentBasePackage());
        config.setFeatures(new ArrayList<>(selectedFeatures));
        return config;
    }

    private String currentProjectName() {
        String text = nameState.text();
        if (text == null || text.isBlank()) {
            return DEFAULT_NAME;
        }
        return text.trim();
    }

    private String currentBasePackage() {
        String text = packageState.text();
        if (text == null || text.isBlank()) {
            return DEFAULT_PACKAGE;
        }
        return text.trim();
    }

    private boolean isTextInputFocused() {
        ToolkitRunner activeRunner = runner();
        if (activeRunner == null) {
            return false;
        }
        String focusedId = activeRunner.focusManager().focusedId();
        return NAME_INPUT_ID.equals(focusedId)
            || PACKAGE_INPUT_ID.equals(focusedId)
            || SEARCH_INPUT_ID.equals(focusedId);
    }

    private void onUiThread(Runnable action) {
        ToolkitRunner activeRunner = runner();
        if (activeRunner == null) {
            action.run();
            return;
        }
        if (activeRunner.isRenderThread()) {
            action.run();
            return;
        }
        activeRunner.runOnRenderThread(action);
    }

    private void printSuccessSummary(ProjectExtractor.ExtractionResult result, ProjectConfig config) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("Project bootstrapped at " + result.targetDir());
        System.out.println("==================================================");
        System.out.println();
        System.out.println("Files extracted: " + result.extractedFiles().size());
        System.out.println("Build: " + config.getBuild() + " | Language: " + config.getLang() + " | Test: " + config.getTest());
        if (!config.getFeatures().isEmpty()) {
            System.out.println("Features: " + String.join(", ", config.getFeatures()));
        }
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  cd " + config.getName());
        if (config.getBuild().contains("gradle")) {
            System.out.println("  ./gradlew run");
        } else {
            System.out.println("  ./mvnw mn:run");
        }
        System.out.println();
    }

    private String selectedSelectValue(SelectFieldState state, List<Option> options, String fallback) {
        if (options.isEmpty()) {
            return fallback;
        }
        int index = state.selectedIndex();
        if (index < 0 || index >= options.size()) {
            return fallback;
        }
        return options.get(index).value();
    }

    private String selectedTabValue(TabsState state, List<Option> options, String fallback) {
        if (options.isEmpty()) {
            return fallback;
        }
        Integer selected = state.selected();
        int index = selected == null ? 0 : selected;
        if (index < 0 || index >= options.size()) {
            return fallback;
        }
        return options.get(index).value();
    }

    private SelectFieldState createSelectFieldState(List<Option> options, String preferredValue) {
        List<String> labels = optionLabels(options);
        int selectedIndex = indexOfOptionValue(options, preferredValue);
        return new SelectFieldState(labels, selectedIndex);
    }

    private int indexOfOptionValue(List<Option> options, String preferredValue) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equalsIgnoreCase(preferredValue)) {
                return i;
            }
        }
        return 0;
    }

    private static List<Option> withFallback(List<Option> options, List<Option> fallback) {
        if (options == null || options.isEmpty()) {
            return fallback;
        }
        return options;
    }

    private static List<String> optionLabels(List<Option> options) {
        return options.stream()
            .map(Option::label)
            .toList();
    }

    private static List<Option> defaultTypeOptions() {
        return List.of(new Option("Micronaut Application", DEFAULT_TYPE, ""));
    }

    private static List<Option> defaultJdkOptions() {
        return List.of(
            new Option("17", "17", ""),
            new Option("21", "21", ""),
            new Option("25", "25", "")
        );
    }

    private static List<Option> defaultLangOptions() {
        return List.of(
            new Option("Java", "java", ""),
            new Option("Groovy", "groovy", ""),
            new Option("Kotlin", "kotlin", "")
        );
    }

    private static List<Option> defaultBuildOptions() {
        return List.of(
            new Option("Gradle", "gradle", ""),
            new Option("Gradle Kotlin", "gradle-kotlin", ""),
            new Option("Maven", "maven", "")
        );
    }

    private static List<Option> defaultTestOptions() {
        return List.of(
            new Option("JUnit", "junit", ""),
            new Option("Spock", "spock", ""),
            new Option("Kotest", "kotest", "")
        );
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    public static void main(String[] args) throws Exception {
        new MicronautLaunchTUI().run();
    }
}
