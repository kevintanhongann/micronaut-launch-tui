import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ProjectExtractor {
    private static final String GRADLEW = "gradlew";
    private static final String MVNW = "mvnw";

    public record ExtractionResult(List<String> extractedFiles, String targetDir) {}

    public ExtractionResult extractToDirectory(InputStream zipStream, Path targetDir) throws IOException {
        ensureTargetDirectoryReady(targetDir);

        List<String> extractedFiles = new ArrayList<>();
        String rootPrefix = null;

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (rawName == null || rawName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                String normalizedName = rawName.replace('\\', '/');
                if (rootPrefix == null) {
                    rootPrefix = detectRootPrefix(normalizedName);
                }

                String relativeName = stripRootPrefix(normalizedName, rootPrefix);
                if (relativeName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                Path entryPath = resolveZipEntryPath(targetDir, relativeName);
                if (entryPath == null) {
                    zis.closeEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Path parentDir = entryPath.getParent();
                    if (parentDir != null) {
                        Files.createDirectories(parentDir);
                    }

                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    extractedFiles.add(entryPath.toString());
                    setExecutableIfNeeded(entryPath);
                }

                zis.closeEntry();
            }
        }

        return new ExtractionResult(extractedFiles, targetDir.toString());
    }

    public boolean targetDirectoryExists(Path cwd, String projectName) {
        Path targetDir = cwd.resolve(projectName);
        return targetDirectoryHasContent(targetDir);
    }

    public boolean targetDirectoryHasContent(Path targetDir) {
        if (!Files.exists(targetDir)) {
            return false;
        }
        if (!Files.isDirectory(targetDir)) {
            return true;
        }
        try (Stream<Path> entries = Files.list(targetDir)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return true;
        }
    }

    public void clearDirectory(Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            return;
        }
        if (!Files.isDirectory(targetDir)) {
            throw new IOException("Target path exists but is not a directory: " + targetDir);
        }
        try (Stream<Path> walk = Files.walk(targetDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    if (path.equals(targetDir)) {
                        return;
                    }
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to delete " + path + ": " + e.getMessage(), e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }

    public void makeExecutable(Path targetDir) throws IOException {
        Path gradlew = targetDir.resolve(GRADLEW);
        if (Files.exists(gradlew)) {
            makeFileExecutable(gradlew);
        }

        Path mvnw = targetDir.resolve(MVNW);
        if (Files.exists(mvnw)) {
            makeFileExecutable(mvnw);
        }
    }

    private void ensureTargetDirectoryReady(Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            if (!Files.isDirectory(targetDir)) {
                throw new IOException("Target path exists but is not a directory: " + targetDir);
            }
            if (targetDirectoryHasContent(targetDir)) {
                throw new IOException(
                    "Target directory is not empty: " + targetDir
                        + ". Confirm overwrite or choose a different project name."
                );
            }
            return;
        }
        Files.createDirectories(targetDir);
    }

    private String detectRootPrefix(String entryName) {
        int separator = entryName.indexOf('/');
        if (separator <= 0) {
            return null;
        }
        String firstSegment = entryName.substring(0, separator);
        if (firstSegment.matches("^[a-zA-Z0-9_.-]+$")) {
            return firstSegment + "/";
        }
        return null;
    }

    private String stripRootPrefix(String entryName, String rootPrefix) {
        String cleaned = entryName;
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        if (rootPrefix != null && cleaned.startsWith(rootPrefix)) {
            cleaned = cleaned.substring(rootPrefix.length());
        }
        return cleaned;
    }

    private Path resolveZipEntryPath(Path targetDir, String entryName) {
        if (entryName.contains("..") || entryName.startsWith("/") || entryName.matches("^[a-zA-Z]:.*")) {
            System.err.println("Warning: Skipping potentially malicious zip entry: " + entryName);
            return null;
        }

        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Path candidate = normalizedTarget.resolve(entryName).normalize();
        if (!candidate.startsWith(normalizedTarget)) {
            System.err.println("Warning: Skipping zip entry outside target dir: " + entryName);
            return null;
        }
        return candidate;
    }

    private void setExecutableIfNeeded(Path path) {
        String filename = path.getFileName() != null ? path.getFileName().toString() : "";
        if (!filename.equals(GRADLEW) && !filename.equals(MVNW) && !filename.endsWith(".sh")) {
            return;
        }
        try {
            makeFileExecutable(path);
        } catch (IOException e) {
            System.err.println("Warning: Could not set executable on " + path + ": " + e.getMessage());
        }
    }

    private void makeFileExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(path));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem, nothing to do.
        }
    }
}
