/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.codegen;

import org.elasticsoftware.akces.codegen.model.EventModelDefinition;
import org.testng.annotations.Test;

import javax.tools.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Tests that verify the generated Java source code actually compiles.
 * Uses the Java Compiler API ({@link javax.tools.JavaCompiler}) to compile
 * the generated files with the Akces API on the classpath.
 * <p>
 * Generated sources are written to {@code target/generated-test-sources/akces-codegen}
 * which is managed by Maven (cleaned by {@code mvn clean}).
 */
public class CompilationTest {

    private static final Path GENERATED_SOURCES_DIR = Path.of("target", "generated-test-sources", "akces-codegen");

    private final AkcesCodeGenerator generator = new AkcesCodeGenerator();

    @Test
    public void testAccountGeneratedCodeCompiles() throws Exception {
        EventModelDefinition definition = loadDefinition("crypto-trading-account.json");
        assertCompiles(definition, "Account");
    }

    @Test
    public void testWalletGeneratedCodeCompiles() throws Exception {
        EventModelDefinition definition = loadDefinition("crypto-trading-wallet.json");
        assertCompiles(definition, "Wallet");
    }

    @Test
    public void testBothAggregatesCompileTogether() throws Exception {
        // Generate and compile both definitions into the same source tree
        // to verify there are no naming collisions
        EventModelDefinition accountDef = loadDefinition("crypto-trading-account.json");
        EventModelDefinition walletDef = loadDefinition("crypto-trading-wallet.json");

        Path outputDir = GENERATED_SOURCES_DIR;
        Files.createDirectories(outputDir);

        generator.generateToDirectory(accountDef, outputDir);
        generator.generateToDirectory(walletDef, outputDir);

        List<Path> sourceFiles = collectJavaFiles(outputDir);
        assertFalse(sourceFiles.isEmpty(), "Should have generated Java source files");

        compileAndAssert(sourceFiles, outputDir);
    }

    /**
     * Generates code from the definition, writes it to
     * {@code target/generated-test-sources/akces-codegen},
     * and compiles it using the Java Compiler API.
     */
    private void assertCompiles(EventModelDefinition definition, String aggregateName) throws Exception {
        Path outputDir = GENERATED_SOURCES_DIR;
        Files.createDirectories(outputDir);

        List<GeneratedFile> generated = generator.generateToDirectory(definition, outputDir);
        assertFalse(generated.isEmpty(),
                "Should have generated files for " + aggregateName);

        // Verify all generated files exist on disk
        for (GeneratedFile gf : generated) {
            Path filePath = outputDir.resolve(gf.relativePath());
            assertTrue(Files.exists(filePath),
                    "Generated file should exist: " + gf.relativePath());
        }

        List<Path> sourceFiles = collectJavaFiles(outputDir);
        assertFalse(sourceFiles.isEmpty(), "Should have .java files on disk");

        compileAndAssert(sourceFiles, outputDir);
    }

    /**
     * Compiles the given Java source files and asserts that compilation succeeds.
     */
    private void compileAndAssert(List<Path> sourceFiles, Path outputDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler not available (requires JDK, not JRE)");

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            // Set the classpath to include the test classpath (which has akces-api and its transitive deps)
            String classpath = System.getProperty("java.class.path");

            // Create output directory for compiled classes
            Path classOutputDir = outputDir.resolve("classes");
            Files.createDirectories(classOutputDir);

            List<String> options = List.of(
                    "-classpath", classpath,
                    "-d", classOutputDir.toString()
            );

            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

            Boolean success = task.call();

            // Collect error diagnostics for the failure message
            if (!success) {
                StringBuilder errorMsg = new StringBuilder("Compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        errorMsg.append("  ").append(diagnostic.getSource() != null ? diagnostic.getSource().getName() : "unknown")
                                .append(":").append(diagnostic.getLineNumber())
                                .append(": ").append(diagnostic.getMessage(null))
                                .append("\n");
                    }
                }
                // Also print the generated source files for debugging
                errorMsg.append("\nGenerated source files:\n");
                for (Path sourceFile : sourceFiles) {
                    errorMsg.append("  ").append(sourceFile.getFileName()).append(":\n");
                    errorMsg.append(Files.readString(sourceFile)).append("\n");
                }
                fail(errorMsg.toString());
            }
        }
    }

    /**
     * Recursively collects all .java files under the given directory.
     */
    private List<Path> collectJavaFiles(Path dir) throws Exception {
        List<Path> javaFiles = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        }
        return javaFiles;
    }

    private EventModelDefinition loadDefinition(String resourceName) {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName);
        assertNotNull(is, "Test resource not found: " + resourceName);
        return generator.parse(is);
    }
}
