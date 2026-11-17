// SPDX-License-Identifier: MPL-2.0
package dev.pontik.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rules the compiler cannot check, read from the source files themselves.
 */
class SourceTreeTest {

    private static final Path MAIN = Paths.get("src/main/java/dev/pontik/cluster");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern LICENSED =
        Pattern.compile(".*\\.(java|kts|properties|ya?ml|txt|json|xml)|Dockerfile");
    // Build output, Gradle's own wrapper and the art; folders whose name starts with a dot are local
    // tool folders, except the two we commit.
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList("build", "gradle", "art"));

    @Test
    void everyStatementLivesInSql() throws IOException {
        // All of our SQL lives in Sql, where SqlTest checks its clock and its writes, and it is all
        // upper case, so a statement anywhere else is one nobody checked. Message text such as
        // "Please update the mode" is lower case.
        Pattern statement = Pattern.compile("\\b(?:SELECT|INSERT INTO|DELETE FROM|UPDATE|CREATE TABLE"
            + "|ALTER TABLE|DROP TABLE|TRUNCATE)\\b");
        Path sql = MAIN.resolve("lease/Sql.java");

        for (Path file : javaFiles(MAIN)) {
            if (!file.equals(sql)) {
                for (String literal : literals(read(file))) {
                    assertFalse(statement.matcher(literal).find(), file + ": " + literal);
                }
            }
        }
    }

    @Test
    void everyFileWithCodeOrSettingsNamesItsLicense() throws IOException {
        // Gradle does not see these files as test inputs, so after you edit only one of them, run this
        // test with --rerun. CI always runs it fresh.
        Path root = Paths.get("..").toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                String name = dir.getFileName().toString();
                boolean skipped = !dir.equals(root) && (SKIPPED.contains(name)
                    || name.startsWith(".") && !name.equals(".github") && !name.equals(".devcontainer"));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (LICENSED.matcher(file.getFileName().toString()).matches()
                        && !read(file).contains("SPDX-License-Identifier: MPL-2.0")) {
                    missing.add(root.relativize(file));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertTrue(missing.isEmpty(), "These files do not name their license: " + missing);
    }

    private static List<String> literals(String source) {
        List<String> found = new ArrayList<>();
        Matcher m = LITERAL.matcher(source);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    private static List<Path> javaFiles(Path... dirs) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : dirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                files.addAll(walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()));
            }
        }
        assertFalse(files.isEmpty());
        return files;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
