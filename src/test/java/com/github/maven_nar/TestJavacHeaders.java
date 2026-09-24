/*
 * #%L
 * Native ARchive plugin for Maven
 * %%
 * Copyright (C) 2002 - 2014 NAR Maven Plugin developers.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.github.maven_nar;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import junit.framework.TestCase;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.codehaus.plexus.util.FileUtils;

/** Compares regenerated headers with javac's headers from the original sources. */
public class TestJavacHeaders extends TestCase {
  private File work;
  private File classes;
  private File expected;
  private File actual;

  @Override
  protected void setUp() throws Exception {
    work = Files.createTempDirectory("nar header parity ").toFile();
    classes = directory("classes");
    expected = directory("expected");
    actual = new File(work, "actual");
  }

  @Override
  protected void tearDown() throws Exception { FileUtils.deleteDirectory(work); }

  public void testCovariantInterfaceReturn() throws Exception {
    compile(classes, expected,
      "Marker.java", "public interface Marker {}",
      "Base.java", "public class Base { public Marker call() { return null; } }",
      "Api.java", "public class Api extends Base implements Marker { public native Api call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantEnclosingRecordReturn() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected,
      "Base.java", "public class Base { public Record call() { return null; } }",
      "Container.java", "public record Container(int x) { public static class Api extends Base { public native Container call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testManifestClassPath() throws Exception {
    File dep = directory("dependency");
    compile(dep, null, "dep/Base.java", "package dep; public class Base {}");
    File dependencyJar = jar(dep, null, false);
    File library = directory("library");
    compile(library, null, "Anchor.java", "public class Anchor {}");
    File libraryJar = new File(work, "library.jar");
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "dependency.jar");
    try (OutputStream stream = Files.newOutputStream(libraryJar.toPath()); JarOutputStream out = new JarOutputStream(stream, manifest)) {
      addJarClasses(out, library, "");
    }
    compileWithPath(classes, expected, Arrays.asList(libraryJar),
      "Api.java", "public class Api extends dep.Base { public native void call(); }");
    File javah = TestJavah.jdkTool("javah");
    if (javah.isFile()) {
      File legacy = directory("legacy");
      Process process = new ProcessBuilder(javah.getPath(), "-classpath", classes + File.pathSeparator + libraryJar,
          "-d", legacy.getPath(), "Api").inheritIO().start();
      assertEquals("Legacy javah resolves manifest Class-Path", 0, process.waitFor());
      assertTrue(new File(legacy, "Api.h").isFile());
      System.out.println("Legacy javah control passed with manifest Class-Path");
    }
    generate(classes, Arrays.asList(classes, libraryJar), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testNestedDeclarationsAndSignatures() throws Exception {
    compile(classes, expected,
        "p/Outer.java", "package p; public class Outer {"
        + " public static class Static { private native long call(int[] v); public static native Throwable call(Throwable v);"
        + "  public native Inner sibling(Inner v); public native Support support(Support v); public static class Deep { native String[] array(String... values); } }"
        + " public class Inner { protected native double call(double[][] v); public class Deep { public native void call(); } }"
        + " public static class Support { public native void ignored(); } }",
        "p/Outer$Dollar.java", "package p; public class Outer$Dollar { native char call(char c); }",
        "p/Interface.java", "package p; public interface Interface { class Nested { public native boolean call(); } }",
        "DefaultNative.java", "public class DefaultNative { public native void call(); }",
        "p/Choice.java", "package p; public enum Choice { FIRST; public native Choice call(Choice c); }");
    Set<String> excludes = set("**/Outer$Support.class");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), excludes);
    Files.delete(new File(expected, "p_Outer_Support.h").toPath());
    equalHeaders();
    assertFalse("Support-only outer must not request a header", new File(actual, "p_Outer.h").exists());
    // Recreate the class directory in reverse order; graph construction must not depend on discovery order.
    File reversed = directory("reversed");
    List<File> files = classFiles(classes);
    Collections.reverse(files);
    for (File file : files) {
      File destination = new File(reversed, classes.toPath().relativize(file.toPath()).toString());
      Files.createDirectories(destination.getParentFile().toPath());
      Files.copy(file.toPath(), destination.toPath());
    }
    generate(reversed, Arrays.asList(reversed), Collections.<String>emptySet(), excludes);
    equalHeaders();
  }

  public void testConstantsConstructorsAndThrowableHierarchy() throws Exception {
    compile(classes, expected,
        "p/Base.java", "package p; public abstract class Base<T> {"
        + " public static final int INHERITED=42, HIDDEN=1; private static final long PRIVATE=7L;"
        + " protected Base(String name, int count) throws Exception {} public abstract void ignored(T value); }",
        "p/Api.java", "package p; public abstract class Api extends Base<String> {"
        + " public static final int HIDDEN=2; public static final long BIG=1234567890123L, MIN=Long.MIN_VALUE;"
        + " public static final boolean YES=true; public static final char LETTER='x';"
        + " public static final byte BYTE=-2; public static final short SHORT=4;"
        + " public static final float NAN=Float.NaN, INFINITY=Float.NEGATIVE_INFINITY, FLOAT=-0.0f;"
        + " public static final double DOUBLE=1.25, DINF=Double.POSITIVE_INFINITY;"
        + " public Api() throws Exception { super(null, 0); }"
        + " native Failure call(Failure failure); native void overloaded(int a); native void overloaded(long a);"
        + " void overloaded(String a) {} public static class Failure extends Exception {} }",
        "p/Outer.java", "package p; public class Outer { public class Base {"
        + " protected Base(int a) throws Exception {} } }",
        "p/Child.java", "package p; public class Child extends Outer.Base {"
        + " public Child(Outer o) throws Exception { o.super(1); } native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
    String api = text(new File(actual, "p_Api.h"));
    assertTrue(api.contains("jthrowable"));
    assertTrue(api.contains("1234567890123"));
  }

  public void testGeneratedSuperclassAndInnerSuperclass() throws Exception {
    compile(classes, expected,
        "p/Outer.java", "package p; public class Outer { public class Base {"
        + " protected Base(String s) throws Exception {} native void base(); }"
        + " public class Child extends Base { public Child() throws Exception { super(null); } native void call(); } }",
        "p/Base.java", "package p; public class Base { protected Base(int i) {} native void base(); }",
        "p/Child.java", "package p; public class Child extends Base { public Child() { super(0); } native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testDependencyOnlyAndConstantOnlyExtraClasses() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, expected,
        "dep/Outer.java", "package dep; public class Outer { public static class Api { public native void call(); } }",
        "dep/Constants.java", "package dep; public class Constants {"
        + " @java.lang.annotation.Native public static final long VALUE=42L; }");
    File jar = jar(dependency, null, false);
    generate(classes, Arrays.asList(jar), set("dep.Outer$Api", "dep.Constants"), Collections.<String>emptySet());
    equalHeaders();
    assertFalse(text(new File(actual, "dep_Constants.h")).contains("__nar_header"));
  }

  public void testUnrelatedMissingDependencyMembersAreNotResolved() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, null,
        "dep/Missing.java", "package dep; public class Missing {}",
        "dep/Payload.java", "package dep; public class Payload { public Missing field; public Missing ignored(Missing x) { return x; } }",
        "dep/Base.java", "package dep; public abstract class Base { protected Base() {} public abstract Missing ignored(); }");
    compileWithPath(classes, expected, Arrays.asList(dependency),
        "Api.java", "public abstract class Api extends dep.Base { public native dep.Payload call(dep.Payload p); }");
    Files.delete(new File(dependency, "dep/Missing.class").toPath());
    generate(classes, Arrays.asList(classes, dependency), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMissingRequiredTypesAndSuperclass() throws Exception {
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Api.java", "public class Api { public native Missing call(); }");
    Files.delete(new File(classes, "Missing.class").toPath());
    failure("Missing class definition: Missing", Collections.<String>emptySet());
    FileUtils.deleteDirectory(classes);
    classes.mkdirs();
    compile(classes, expected,
        "Base.java", "public class Base {}",
        "Api.java", "public class Api extends Base { public native void call(); }");
    Files.delete(new File(classes, "Base.class").toPath());
    failure("Missing class definition: Base", Collections.<String>emptySet());
  }

  public void testUnsupportedTargetsAndHeaderCollisions() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer { Object make() { class Local { native void call();"
        + " class Member { native void call(); } } return new Local(); }"
        + " Object anonymous = new Object() { public native void call(); }; }");
    for (String target : Arrays.asList("Outer$1Local", "Outer$1Local$Member", "Outer$1")) {
      try {
        generate(classes, Arrays.asList(classes), set(target), set("**/*.class"));
        fail("Expected unsupported local/anonymous target " + target);
      } catch (IOException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains("local/anonymous")); }
    }
    FileUtils.deleteDirectory(classes);
    classes.mkdirs();
    failure("JDK/platform", set("java.lang.String"));
    compile(classes, null,
        "A.java", "public class A { public static class B { native void call(); } }",
        "A_B.java", "public class A_B { native void call(); }");
    failure("filename collision", Collections.<String>emptySet());
  }

  public void testFailedCompilerPreservesPublishedHeaders() throws Exception {
    compile(classes, expected, "Api.java", "public class Api { public native int call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    byte[] original = Files.readAllBytes(new File(actual, "Api.h").toPath());
    // Valid bytecode, but a method name that the selected javac cannot compile as source.
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Api", null, "java/lang/Object", null);
    writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "call", "()V", null, null).visitEnd();
    writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "call", "()I", null, null).visitEnd();
    writer.visitEnd();
    Files.write(new File(classes, "Api.class").toPath(), writer.toByteArray());
    failure("failed (exit", Collections.<String>emptySet());
    assertTrue(Arrays.equals(original, Files.readAllBytes(new File(actual, "Api.h").toPath())));
    assertTrue(new File(work, "generated/javac.log").isFile());
  }

  public void testMultiReleaseJarUsesCompilerVersion() throws Exception {
    File base = directory("base");
    File versioned = directory("versioned");
    compile(base, null, "Api.java", "public class Api { public static final int VERSION=8; public native int call(); }");
    compile(versioned, null, "Api.java", "public class Api { public static final int VERSION=9; public native long call(); }");
    File jar = jar(base, versioned, true);
    generate(classes, Arrays.asList(jar), set("Api"), Collections.<String>emptySet());
    String header = text(new File(actual, "Api.h"));
    boolean modern = !System.getProperty("java.specification.version").startsWith("1.");
    assertTrue(header.contains("#define Api_VERSION " + (modern ? "9L" : "8L")));
    assertTrue(header.contains(modern ? "jlong" : "jint"));
  }

  public void testEnclosingRecordOnModernJdk() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected, "Container.java", "public record Container(String value) {"
        + " public static class Api { public native Container call(Container c); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorSelectionAndDiagnostics() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, null,
        "dep/Base.java", "package dep; public class Base { private static class Hidden {}"
        + " protected Base(Hidden value) {} protected Base(String value, int count) throws Throwable {} }");
    compileWithPath(classes, expected, Arrays.asList(dependency),
        "Api.java", "public class Api extends dep.Base { public Api() throws Throwable { super(null, 0); }"
        + " native void call(); }");
    generate(classes, Arrays.asList(classes, dependency), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
    FileUtils.deleteDirectory(dependency);
    dependency.mkdirs();
    compile(dependency, null,
        "dep/Missing.java", "package dep; public class Missing {}",
        "dep/Base.java", "package dep; public class Base { protected Base(Missing value) {} }");
    Files.delete(new File(dependency, "dep/Missing.class").toPath());
    try {
      generate(classes, Arrays.asList(classes, dependency), Collections.<String>emptySet(), Collections.<String>emptySet());
      fail("Missing constructor argument must be diagnosed");
    } catch (IOException ex) {
      assertTrue(ex.getMessage(), ex.getMessage().contains("Cannot construct superclass dep/Base for Api"));
      assertTrue(ex.getMessage(), ex.getMessage().contains("(Ldep/Missing;)V"));
    }
  }

  public void testProtectedConstructorParameter() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, null, "dep/Base.java", "package dep; public class Base {"
        + " protected static class Parameter {} protected Base(Parameter p) throws Exception {} }");
    compileWithPath(classes, expected, Arrays.asList(dependency),
        "Api.java", "public class Api extends dep.Base { public Api() throws Exception { super(null); } native void call(); }");
    generate(classes, Arrays.asList(classes, dependency), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMissingSignatureSuperclassAndEnclosingClass() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base {}",
        "Payload.java", "public class Payload extends Base {}",
        "Api.java", "public class Api { native Payload call(); }");
    Files.delete(new File(classes, "Base.class").toPath());
    failure("Missing class definition: Base", Collections.<String>emptySet());
    FileUtils.deleteDirectory(classes);
    classes.mkdirs();
    compile(classes, expected, "Outer.java", "public class Outer { public static class Api { native void call(); } }");
    Files.delete(new File(classes, "Outer.class").toPath());
    failure("Missing class definition: Outer", Collections.<String>emptySet());
  }

  public void testExcludedEnclosingClassDoesNotGenerateHeader() throws Exception {
    compile(classes, expected, "Outer.java", "public class Outer { native void outer();"
        + " public static class Inner { public native void inner(); } }");
    generate(classes, Arrays.asList(classes), set("Outer$Inner"), set("**/*.class"));
    Files.delete(new File(expected, "Outer.h").toPath());
    equalHeaders();
  }

  public void testConstantOnlyInterfaceAndDependencyChanges() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, expected, "Constants.java", "public interface Constants {"
        + " @java.lang.annotation.Native int VALUE=1; }");
    generate(classes, Arrays.asList(dependency), set("Constants"), Collections.<String>emptySet());
    equalHeaders();
    compile(dependency, expected, "Constants.java", "public interface Constants {"
        + " @java.lang.annotation.Native int VALUE=2; }");
    generate(classes, Arrays.asList(dependency), set("Constants"), Collections.<String>emptySet());
    equalHeaders();
    assertTrue(text(new File(actual, "Constants.h")).contains("VALUE 2L"));
  }

  public void testRestrictedTypeNameUsesCompilerLanguage() throws Exception {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "var", null, "java/lang/Object", null);
    writer.visitEnd();
    Files.write(new File(classes, "var.class").toPath(), writer.toByteArray());
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 10) {
      generate(classes, Arrays.asList(classes), set("var"), Collections.<String>emptySet());
      assertTrue(new File(actual, "var.h").isFile());
    } else {
      failure("Source-inexpressible type name", set("var"));
      assertFalse("Validate names before emitting sources", new File(work, "generated/sources").exists());
    }
  }

  public void testMetadataValidation() throws Exception {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Bad-Name", null, "java/lang/Object", null);
    writer.visitEnd();
    Files.write(new File(classes, "Bad-Name.class").toPath(), writer.toByteArray());
    failure("Source-inexpressible", set("Bad-Name"));
    FileUtils.deleteDirectory(classes);
    classes.mkdirs();
    malformedMember("A", "B", "A");
    malformedMember("B", "A", "B");
    failure("Containment cycle", set("A"));
  }

  private void malformedMember(String name, String outer, String simple) throws Exception {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    writer.visitInnerClass(name, outer, simple, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
    writer.visitEnd();
    Files.write(new File(classes, name + ".class").toPath(), writer.toByteArray());
  }

  private void failure(String message, Set<String> extras) throws Exception {
    try {
      generate(classes, Arrays.asList(classes), extras, Collections.<String>emptySet());
      fail("Expected failure containing " + message);
    } catch (IOException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(message)); }
  }

  private void generate(File scanned, List<File> paths, Set<String> extras, Set<String> excludes) throws Exception {
    new JavacHeaders(TestJavah.jdkTool("javac"), new File(work, "generated"), paths,
        Collections.<File>emptyList(), new SystemStreamLog())
        .generate(scanned, set("**/*.class"), excludes, extras, actual);
  }

  private void compile(File destination, File headers, String... sources) throws Exception {
    compileWithPath(destination, headers, Collections.<File>emptyList(), sources);
  }

  private void compileWithPath(File destination, File headers, List<File> paths, String... sources) throws Exception {
    File sourceDirectory = Files.createTempDirectory(work.toPath(), "src").toFile();
    List<String> args = new ArrayList<String>(Arrays.asList("-proc:none", "-encoding", "UTF-8", "-d", destination.getPath()));
    if (headers != null) { Collections.addAll(args, "-h", headers.getPath()); }
    if (!paths.isEmpty()) {
      StringBuilder path = new StringBuilder();
      for (File entry : paths) { if (path.length() != 0) { path.append(File.pathSeparator); } path.append(entry); }
      Collections.addAll(args, "-classpath", path.toString());
    }
    for (int i = 0; i < sources.length; i += 2) {
      File file = new File(sourceDirectory, sources[i]);
      Files.createDirectories(file.getParentFile().toPath());
      Files.write(file.toPath(), sources[i + 1].getBytes(StandardCharsets.UTF_8));
      args.add(file.getPath());
    }
    assertEquals("Fixture compilation", 0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
        args.toArray(new String[args.size()])));
  }

  private File jar(File base, File versioned, boolean multiRelease) throws Exception {
    File jar = new File(work, "dependency.jar");
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (multiRelease) { manifest.getMainAttributes().putValue("Multi-Release", "true"); }
    try (OutputStream stream = Files.newOutputStream(jar.toPath()); JarOutputStream out = new JarOutputStream(stream, manifest)) {
      addJarClasses(out, base, "");
      if (versioned != null) { addJarClasses(out, versioned, "META-INF/versions/9/"); }
    }
    return jar;
  }

  private void addJarClasses(JarOutputStream out, File directory, String prefix) throws Exception {
    for (File file : classFiles(directory)) {
      out.putNextEntry(new JarEntry(prefix + directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')));
      Files.copy(file.toPath(), out);
      out.closeEntry();
    }
  }

  private List<File> classFiles(File directory) {
    List<File> result = new ArrayList<File>();
    File[] children = directory.listFiles();
    if (children != null) {
      Arrays.sort(children);
      for (File child : children) {
        if (child.isDirectory()) { result.addAll(classFiles(child)); }
        else if (child.getName().endsWith(".class")) { result.add(child); }
      }
    }
    return result;
  }

  private void equalHeaders() throws Exception {
    Set<String> expectedNames = new HashSet<String>(Arrays.asList(expected.list()));
    Set<String> actualNames = new HashSet<String>(Arrays.asList(actual.list()));
    assertEquals("Only requested headers", expectedNames, actualNames);
    for (String name : expectedNames) { assertEquals(name, text(new File(expected, name)), text(new File(actual, name))); }
  }

  private File directory(String name) throws IOException {
    File directory = new File(work, name);
    Files.createDirectories(directory.toPath());
    return directory;
  }

  private static Set<String> set(String... values) { return new HashSet<String>(Arrays.asList(values)); }
  private static String text(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
