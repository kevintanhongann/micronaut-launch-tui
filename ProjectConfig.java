import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ProjectConfig {
    private String type = "default";
    private String jdkVersion = "21";
    private String lang = "java";
    private String build = "gradle";
    private String test = "junit";
    private String name = "demo";
    private String basePackage = "com.example";
    private List<String> features = new ArrayList<>();

    public ProjectConfig() {}

    public ProjectConfig(String type, String jdkVersion, String lang, String build, 
                         String test, String name, String basePackage, List<String> features) {
        this.type = type;
        this.jdkVersion = jdkVersion;
        this.lang = lang;
        this.build = build;
        this.test = test;
        this.name = name;
        this.basePackage = basePackage;
        this.features = features != null ? new ArrayList<>(features) : new ArrayList<>();
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getJdkVersion() { return jdkVersion; }
    public void setJdkVersion(String jdkVersion) { this.jdkVersion = jdkVersion; }

    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }

    public String getBuild() { return build; }
    public void setBuild(String build) { this.build = build; }

    public String getTest() { return test; }
    public void setTest(String test) { this.test = test; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBasePackage() { return basePackage; }
    public void setBasePackage(String basePackage) { this.basePackage = basePackage; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { 
        this.features = features != null ? new ArrayList<>(features) : new ArrayList<>(); 
    }

    public void addFeature(String feature) {
        if (!this.features.contains(feature)) {
            this.features.add(feature);
        }
    }

    public void removeFeature(String feature) {
        this.features.remove(feature);
    }

    public void toggleFeature(String feature) {
        if (this.features.contains(feature)) {
            removeFeature(feature);
        } else {
            addFeature(feature);
        }
    }

    public String getTargetDirectory(String cwd) {
        return cwd + "/" + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectConfig that = (ProjectConfig) o;
        return Objects.equals(type, that.type) &&
               Objects.equals(jdkVersion, that.jdkVersion) &&
               Objects.equals(lang, that.lang) &&
               Objects.equals(build, that.build) &&
               Objects.equals(test, that.test) &&
               Objects.equals(name, that.name) &&
               Objects.equals(basePackage, that.basePackage) &&
               Objects.equals(features, that.features);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, jdkVersion, lang, build, test, name, basePackage, features);
    }
}

record SelectOptions(
    List<Option> types,
    List<Option> langs,
    List<Option> builds,
    List<Option> tests,
    List<Option> jdkVersions
) {}

record Option(String label, String value, String description) {}

record Feature(String name, String title, String description, String category, boolean preview, boolean community) {}
