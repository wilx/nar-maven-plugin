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

  public void testConstructorWildcardArrayBound() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base { protected <T> Base(java.util.List<? extends T[]> values) {} }",
        "Api.java", "public class Api extends Base { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMixedNullConstructorAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(A value, int other) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMixedNullConstructorAlternativeNonGeneric() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(A value, int other) {} }",
        "Api.java", "public class Api extends Base { public Api() { super(null, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMixedNullConstructorOnlyCandidate() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value, int other) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCastConstructorHigherArityAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(java.util.List<A> values, int other) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((java.util.List) null, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorSelectionWithSameFileNames() throws Exception {
    compile(classes, expected,
        "dep/Base.java", "package dep; public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(A value, int other) {} }",
        "dep/Only.java", "package dep; public class Only { private static class A {} protected Only(A value) {} }",
        "left/Api.java", "package left; public class Api extends dep.Base<String> { public Api() { super(null, 0); } public native void call(); }",
        "right/Api.java", "package right; public class Api extends dep.Only { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorSelectionForNestedTargets() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(A value, int other) {} }",
        "Outer.java", "public class Outer extends Base<String> { public Outer() { super(null, 0); } public native void outer();"
        + " public static class Inner extends Base<Integer> { public Inner() { super(null, 0); } public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorSelectionAddsSupportingMember() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} protected Base(A value, Api.Marker marker) {} }",
        "Api.java", "public class Api extends Base<String> { public interface Marker {}"
        + " public Api() { super(null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testAmbiguousTypedConstructorAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Runnable & java.io.Serializable> Base(U value) {}"
        + " protected <U extends java.io.Serializable & Runnable, V> Base(U value) {} protected Base(int a, int b) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(0, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testAmbiguousConstructorsFailBeforeCompilationAndPreserveHeaders() throws Exception {
    compile(classes, expected, "Api.java", "public class Api { public native int call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    byte[] original = Files.readAllBytes(new File(actual, "Api.h").toPath());
    compile(classes, null,
        "Base.java", "public class Base<T> { private static class A {} private static class B {}"
        + " protected Base(A value) {} protected Base(B value) {} public static A value() { return null; } }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(Base.value()); } public native void call(); }");
    FileUtils.deleteDirectory(new File(work, "generated"));
    failure("ambiguous", Collections.<String>emptySet());
    assertTrue(Arrays.equals(original, Files.readAllBytes(new File(actual, "Api.h").toPath())));
    assertFalse("Reject ambiguous constructors before starting compilation",
        new File(work, "generated/javac.args").exists());
  }

  public void testPrivateConstructorNullOverloadAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {}"
        + " protected Base(Hidden value) {} protected Base(java.util.List<Hidden> values) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorNullOverloadAlternativeNonGeneric() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base { private static class Hidden {}"
        + " protected Base(Hidden value) {} protected Base(java.util.List<Hidden> values) {} }",
        "Api.java", "public class Api extends Base { public Api() { super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testDirectPrivateConstructorParameter() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {} protected Base(Hidden value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFreshNamesSimultaneousRename() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <_NarConstructor1 extends Number & Comparable<_NarConstructor1>, _NarConstructor0 extends _NarConstructor1> Base(_NarConstructor0 value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((Integer) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFallbackRetainsVisibleBound() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {} protected <U extends Number & Hidden, V extends CharSequence & Comparable<V>> Base(U value, V other) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, (String) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorWildcardArgument() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {} protected Base(java.util.List<? extends Hidden> values) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorNullOverloadReversedDeclarations() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {}"
        + " protected Base(java.util.List<Hidden> values) {} protected Base(Hidden value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorNullOverloadInnerSuperclass() throws Exception {
    compile(classes, expected,
        "Owner.java", "public class Owner<T> { private static class Hidden {} public class Base<V> {"
        + " protected Base(Hidden value) {} protected Base(java.util.List<Hidden> values) {} } }",
        "Api.java", "public class Api extends Owner<String>.Base<Integer> {"
        + " public Api(Owner<String> owner) { owner.super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorBoundOverloadAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {}"
        + " protected <U extends Number & Hidden> Base(U value) {} protected Base(java.util.List<Hidden> values) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorNullOverloadMostSpecific() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {} private static class Specific extends Hidden {}"
        + " protected Base(Hidden value) {} protected Base(Specific value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorPrivateTypeArgument() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {} protected Base(java.util.List<Hidden> values) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorPrivateTypeArgumentNonGenericBase() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base { private static class Hidden {} protected Base(java.util.List<Hidden> values) {} }",
        "Api.java", "public class Api extends Base { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorPrivateTypeBound() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {} protected <U extends Number & Hidden> Base(U value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorUnusedPrivateTypeBound() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {} protected <U extends Hidden> Base() {} }",
        "Api.java", "public class Api extends Base<String> { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorRecursiveComparableWildcard() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Number & Comparable<? super U>> Base(U value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((Integer) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testWildcardRecursiveComparableProjection() throws Exception {
    compile(classes, expected,
        "Values.java", "public class Values<T extends CharSequence & Comparable<? super T>> extends java.util.ArrayList<T> {}",
        "Left.java", "public interface Left { java.util.Collection<? extends Comparable<?>> value(); }",
        "Right.java", "public interface Right { Values<?> value(); }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public Values<?> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFormalCapturesSpecializedClass() throws Exception {
    compile(classes, expected,
        "U.java", "public class U {}",
        "Base.java", "public class Base<T> { protected <U extends T> Base(U value) {} }",
        "Api.java", "public class Api extends Base<U> { public Api() { super((U) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFormalCapturesPackage() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <java extends T> Base(java value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((String) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorArgumentsWithOverloads() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {}"
        + " protected Base(java.util.List<Hidden> values) {} protected Base(java.util.Set<Hidden> values) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((java.util.List) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorArgumentAndIntersection() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {}"
        + " protected <U extends Number & Comparable<U>> Base(java.util.List<Hidden> values, U count) {}"
        + " protected Base(java.util.List<Hidden> values, String text) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, (Integer) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorDependentBounds() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {}"
        + " protected <U extends Number & Hidden, V extends U> Base(V[] values, java.util.List<? super U> more) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorInnerSuperclass() throws Exception {
    compile(classes, expected,
        "Owner.java", "public class Owner<T> { private static class Hidden {} public class Base<V> {"
        + " protected Base(java.util.List<Hidden> values, T text, V number) {} } }",
        "Api.java", "public class Api extends Owner<String>.Base<Integer> {"
        + " public Api(Owner<String> owner) { owner.super(null, null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFreshNamesAvoidClassAndPackage() throws Exception {
    compile(classes, expected,
        "_NarConstructor0.java", "public class _NarConstructor0 {}",
        "_NarConstructor1/Value.java", "package _NarConstructor1; public class Value {}",
        "Base.java", "public class Base<T> { protected <java extends T, U extends java>"
        + " Base(java value, U[] more, _NarConstructor1.Value marker) {} }",
        "Api.java", "public class Api extends Base<_NarConstructor0> { public Api() {"
        + " super((_NarConstructor0) null, (_NarConstructor0[]) null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFreshRecursiveNames() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <java extends Number & Comparable<? super java>, U extends java>"
        + " Base(java value, U[] more) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() {"
        + " super((Integer) null, (Integer[]) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorFreshNameAvoidsTargetClass() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends T> Base(U value) {} }",
        "_NarConstructor0.java", "public class _NarConstructor0 extends Base<String> {"
        + " public _NarConstructor0() { super((String) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testPrivateConstructorArgumentArraysAndPrimitives() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private static class Hidden {}"
        + " protected Base(java.util.List<Hidden>[] values, boolean flag, int[] data, long count) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, false, null, 0); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedConstructorIntersection() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Number & Comparable<U>> Base(U value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((Integer) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedConstructorRecursiveBound() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Comparable<U>> Base(U value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((String) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedConstructorDependentArguments() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U> Base(java.util.List<U> value, U other) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super(null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testNativeGenericSuperclassOverride() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { public T value() { return null; } }",
        "Api.java", "public class Api extends Base<java.util.List<String>> { public native java.util.List<String> value(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedGenericConstructorBoundOnClass() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T extends Number & Runnable> { protected Base(T value) {} }",
        "Value.java", "public abstract class Value extends Number implements Runnable {}",
        "Api.java", "public class Api extends Base<Value> { public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantBoundedWildcardReturn() throws Exception {
    compile(classes, expected,
        "TextList.java", "public class TextList<T extends CharSequence> extends java.util.ArrayList<T> {}",
        "Left.java", "public interface Left { java.util.Collection<? extends CharSequence> value(); }",
        "Right.java", "public interface Right { TextList<?> value(); }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public TextList<?> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantBoundedWildcardReturnReversed() throws Exception {
    compile(classes, expected,
        "TextList.java", "public class TextList<T extends CharSequence> extends java.util.ArrayList<T> {}",
        "Left.java", "public interface Left { java.util.Collection<? extends CharSequence> value(); }",
        "Right.java", "public interface Right { TextList<?> value(); }",
        "Api.java", "public enum Api implements Right, Left { VALUE { public TextList<?> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericConstructorIntersectionOverloads() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Number & Comparable<U>> Base(U value) {}"
        + " protected Base(String value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((Integer) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericInnerConstructorIntersection() throws Exception {
    compile(classes, expected,
        "Owner.java", "public class Owner<T> { public class Base<V> {"
        + " protected <U extends Number & Comparable<U>> Base(U value, T other, V[] array) {} } }",
        "Api.java", "public class Api extends Owner<String>.Base<Integer> {"
        + " public Api(Owner<String> owner) { owner.super((Integer) null, null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testChainedGenericConstructorBounds() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Number & Comparable<U>, V extends U>"
        + " Base(java.util.List<? super U> values, V other, U[] array) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() {"
        + " super((java.util.List<Number>) null, (Integer) null, (Integer[]) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testUnusedConstructorFormalNeedsNoDeclaration() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {} protected <U extends Hidden> Base() {} }",
        "Api.java", "public class Api extends Base<String> { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testInaccessibleConstructorBoundUsesAlternative() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { private interface Hidden {}"
        + " protected <U extends Number & Hidden> Base(U value) {} protected Base(String value) {} }",
        "Api.java", "public class Api extends Base<String> { public Api() { super((String) null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testConstructorBoundNeedsSupportingMember() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> { protected <U extends Number & Outer.Marker> Base(U value) {} }",
        "Value.java", "public abstract class Value extends Number implements Outer.Marker {}",
        "Outer.java", "public class Outer { public interface Marker {} public static class Api extends Base<String> {"
        + " public Api() { super((Value) null); } public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantDeclaredLowerWildcardBound() throws Exception {
    boundedWildcardReturns("T extends CharSequence", "? super String", "CharSequence");
  }

  public void testCovariantExplicitWildcardIntersection() throws Exception {
    boundedWildcardReturns("T extends CharSequence", "? extends java.io.Serializable", "CharSequence");
  }

  public void testCovariantDeclaredWildcardIntersection() throws Exception {
    boundedWildcardReturns("T extends CharSequence & java.io.Serializable", "?", "java.io.Serializable");
  }

  public void testCovariantDependentWildcardBounds() throws Exception {
    boundedWildcardReturns("A extends CharSequence, B extends A, T extends B", "?, ?, ?", "CharSequence");
  }

  public void testCovariantRecursiveWildcardBounds() throws Exception {
    boundedWildcardReturns("T extends CharSequence & Comparable<T>", "?", "Comparable<?>");
  }

  public void testCovariantWildcardBoundFromOwner() throws Exception {
    compile(classes, expected,
        "Owner.java", "public class Owner<A extends CharSequence> { public class Values<T extends A> extends java.util.ArrayList<T> {} }",
        "Left.java", "public interface Left { java.util.Collection<? extends CharSequence> value(); }",
        "Right.java", "public interface Right { Owner<String>.Values<?> value(); }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public Owner<String>.Values<?> value() { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Right, Left { VALUE { public Owner<String>.Values<?> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  private void boundedWildcardReturns(String formals, String arguments, String upper) throws Exception {
    String result = "Bounded<" + arguments + ">";
    compile(classes, expected,
        "Bounded.java", "public class Bounded<" + formals + "> extends java.util.ArrayList<T> {}",
        "Left.java", "public interface Left { java.util.Collection<? extends " + upper + "> value(); }",
        "Right.java", "public interface Right { " + result + " value(); }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public " + result + " value() { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Right, Left { VALUE { public " + result + " value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRawParentParameterizedDirectInterface() throws Exception {
    compile(classes, expected,
        "Base.java", "public abstract class Base<T> implements java.util.function.Supplier<T> {}",
        "Text.java", "public interface Text<T> extends java.util.function.Supplier<T> {}",
        "Api.java", "public abstract class Api extends Base<String> implements Text<String> { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedCovariantEnumMethod() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { java.util.List<? extends CharSequence> value(); }",
        "Right.java", "public interface Right { java.util.List<String> value(); }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public java.util.List<String> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedCovariantEnumMethodReversed() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { java.util.List<? extends CharSequence> value(); }",
        "Right.java", "public interface Right { java.util.List<String> value(); }",
        "Api.java", "public enum Api implements Right, Left { VALUE { public java.util.List<String> value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedSuperclassConstructor() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> implements java.util.function.Supplier<T> {"
        + " protected Base(T value) {} public final T get() { return null; } }",
        "Text.java", "public interface Text<T> extends java.util.function.Supplier<T> {}",
        "Api.java", "public class Api extends Base<String> implements Text<String> {"
        + " public Api() { super(null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testParameterizedInnerSuperclassConstructor() throws Exception {
    compile(classes, expected,
        "Owner.java", "public class Owner<T> { public abstract class Base<U> implements java.util.function.Supplier<T> {"
        + " protected Base(T value, U other) {} } }",
        "Text.java", "public interface Text<T> extends java.util.function.Supplier<T> {}",
        "Api.java", "public abstract class Api extends Owner<String>.Base<Integer> implements Text<String> {"
        + " public Api(Owner<String> owner) { owner.super(null, null); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGeneratedGenericSuperclassCompatibility() throws Exception {
    compile(classes, expected,
        "Base.java", "public abstract class Base<T> implements java.util.function.Supplier<T> { public native void base(); }",
        "Text.java", "public interface Text<T> extends java.util.function.Supplier<T> {}",
        "Api.java", "public abstract class Api extends Base<String> implements Text<String> { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantLowerWildcardReturns() throws Exception {
    covariantReturns("java.util.List<? super String> value()", "java.util.List<? super CharSequence> value()",
        "java.util.List<Object> value()");
  }

  public void testCovariantNestedWildcardReturns() throws Exception {
    covariantReturns("java.util.List<? extends java.util.List<? extends CharSequence>> value()",
        "java.util.List<java.util.List<String>> value()", "java.util.List<java.util.List<String>> value()");
  }

  public void testCovariantGenericSubtypeReturns() throws Exception {
    covariantReturns("java.util.Collection<? extends CharSequence> value()", "java.util.ArrayList<String> value()",
        "java.util.ArrayList<String> value()");
  }

  public void testCovariantProjectedWildcardReturns() throws Exception {
    covariantReturns("java.util.Collection<? super String> value()", "java.util.List<? super CharSequence> value()",
        "java.util.List<Object> value()");
  }

  public void testCovariantParameterizedArrayReturns() throws Exception {
    covariantReturns("java.util.List<? extends CharSequence>[] value()", "java.util.List<String>[] value()",
        "java.util.List<String>[] value()");
  }

  public void testCovariantGenericMethodReturns() throws Exception {
    covariantReturns("<T extends CharSequence> java.util.List<? extends T> value()",
        "<U extends CharSequence> java.util.List<U> value()", "<V extends CharSequence> java.util.List<V> value()");
  }

  public void testCovariantMethodBoundReturns() throws Exception {
    covariantReturns("<T extends CharSequence> java.util.List<? extends CharSequence> value()",
        "<U extends CharSequence> java.util.List<U> value()", "<V extends CharSequence> java.util.List<V> value()");
  }

  public void testCovariantSupportingInterfaceBoundReturns() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left<T extends Number & CharSequence> { java.util.List<? extends CharSequence> value(); }",
        "Right.java", "public interface Right<T extends Number & CharSequence> { java.util.List<T> value(); }",
        "Outer.java", "public class Outer { public abstract static class Digits extends Number implements CharSequence {} public interface Contract<T extends Number & CharSequence> extends Left<T>, Right<T> {}"
        + " public enum Api implements Contract<Digits> { VALUE { public java.util.List<Digits> value() { return null; } };"
        + " public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  private void covariantReturns(String left, String right, String implementation) throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { " + left + "; }",
        "Right.java", "public interface Right { " + right + "; }",
        "Api.java", "public enum Api implements Left, Right { VALUE { public " + implementation
        + " { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Right, Left { VALUE { public " + implementation
        + " { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testEnumArgumentBoundOnGeneratedClass() throws Exception {
    compile(classes, expected,
        "Bound.java", "public interface Bound<T extends Comparable<T>> {}",
        "Payload.java", "public class Payload implements Comparable<Payload> { public int compareTo(Payload p) { return 0; } public native void call(); }",
        "Api.java", "public enum Api implements Bound<Payload> { VALUE; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }
  public void testSupportingInterfaceGenericMethodConflict() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left<T> { default <U extends T> U value(U input) { return null; } }",
        "Right.java", "public interface Right<T> { <U extends T> U value(U input); }",
        "Outer.java", "public class Outer { public interface Contract<T extends CharSequence> extends Left<T>, Right<T> { <U extends T> U value(U input); }"
        + " public enum Api implements Contract<String> { VALUE { public <U extends String> U value(U input) { return null; } }; public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }
  public void testSupportingInterfaceSelfBound() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer { public interface Ordered<T extends Ordered<T>> extends Comparable<T> {}"
        + " public enum Api implements Ordered<Api> { VALUE; public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }
  public void testEnumNestedWildcardArguments() throws Exception {
    compile(classes, expected,
        "Sink.java", "public interface Sink<T> { void accept(T input); }",
        "Api.java", "public enum Api implements Sink<java.util.List<? super String>> { VALUE { public void accept(java.util.List<? super String> input) {} }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testSupportingGenericMethodChainedBounds() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left<T> { default <U extends T, V extends U> V value(V input) { return null; } }",
        "Right.java", "public interface Right<T> { <U extends T, V extends U> V value(V input); }",
        "Outer.java", "public class Outer { public interface Contract<T extends CharSequence> extends Left<T>, Right<T> {"
        + " <U extends T, V extends U> V value(V input); }"
        + " public enum Api implements Contract<String> { VALUE {"
        + " public <U extends String, V extends U> V value(V input) { return null; } }; public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testSupportingGenericMethodIntersectionAndShadowing() throws Exception {
    String method = "<T extends Number & Outer.Marker & Comparable<T>> T[] value(java.util.List<? super T> input, T[] array)";
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Left.java", "public interface Left<T> { default " + method + " { return null; }"
        + " default <U extends Missing> U unrelated() { return null; } }",
        "Right.java", "public interface Right<T> { " + method + "; }",
        "Outer.java", "public class Outer { public interface Marker {}"
        + " public interface Contract<T> extends Left<T>, Right<T> { " + method + "; }"
        + " public enum Api implements Contract<String> { VALUE { public " + method + " { return null; } };"
        + " public native void call(); } }");
    Files.delete(new File(classes, "Missing.class").toPath());
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testSupportingGenericMethodParameterizedOwner() throws Exception {
    String method = "<U extends T> Box<T>.Value<U> value(Box<T>.Value<? extends U> input)";
    compile(classes, expected,
        "Box.java", "public class Box<T> { public class Value<U> {} }",
        "Left.java", "public interface Left<T> { default " + method + " { return null; } }",
        "Right.java", "public interface Right<T> { " + method + "; }",
        "Outer.java", "public class Outer { public interface Contract<T extends CharSequence> extends Left<T>, Right<T> { "
        + method + "; } public enum Api implements Contract<String> { VALUE {"
        + " public <U extends String> Box<String>.Value<U> value(Box<String>.Value<? extends U> input) { return null; } };"
        + " public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericEnumInterface() throws Exception {
    compile(classes, expected,
        "Ordered.java", "public interface Ordered<T> extends Comparable<T> {}",
        "Api.java", "public enum Api implements Ordered<Api> { VALUE; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testInheritedOuterTypeVariable() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer<T> { public class Base { public final void accept(T value) {} } }",
        "Strings.java", "public class Strings extends Outer<String>.Base { public Strings(Outer<String> outer) { outer.super(); } }",
        "Text.java", "public interface Text extends java.util.function.Consumer<String> {}",
        "Wide.java", "public interface Wide { default void accept(String value) {} }",
        "Api.java", "public class Api extends Strings implements Text, Wide { public Api() { super(new Outer<String>()); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMultipleEnclosingGenericScopes() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer<A> { public class Middle<B> { public class Base<C> { public final void accept(A a, B b, C c) {} } } }",
        "Strings.java", "public class Strings extends Outer<String>.Middle<Integer>.Base<Long> { public Strings(Outer<String>.Middle<Integer> owner) { owner.super(); } }",
        "Operation.java", "public interface Operation<A, B, C> { void accept(A a, B b, C c); }",
        "Text.java", "public interface Text extends Operation<String, Integer, Long> {}",
        "Wide.java", "public interface Wide { default void accept(String a, Integer b, Long c) {} }",
        "Api.java", "public class Api extends Strings implements Text, Wide { public Api() { super(new Outer<String>().new Middle<Integer>()); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testMemberTypeParameterShadowsOwnerParameter() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer<T> { public class Base<T> { public final void accept(T value) {} } }",
        "Strings.java", "public class Strings extends Outer<Integer>.Base<String> { public Strings(Outer<Integer> owner) { owner.super(); } }",
        "Text.java", "public interface Text extends java.util.function.Consumer<String> {}",
        "Wide.java", "public interface Wide { default void accept(String value) {} }",
        "Api.java", "public class Api extends Strings implements Text, Wide { public Api() { super(new Outer<Integer>()); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRawEnclosingGenericScope() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer<T extends Number> { public class Base { public final void accept(T value) {} } }",
        "Numbers.java", "public class Numbers extends Outer.Base { public Numbers(Outer owner) { owner.super(); } }",
        "Numeric.java", "public interface Numeric extends java.util.function.Consumer<Number> {}",
        "Wide.java", "public interface Wide { default void accept(Number value) {} }",
        "Api.java", "public class Api extends Numbers implements Numeric, Wide { public Api() { super(new Outer()); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testStaticMemberStopsEnclosingGenericScope() throws Exception {
    compile(classes, expected,
        "Outer.java", "public class Outer<T> { public static class Middle<U> { public class Base<V> { public final void accept(U a, V b) {} } } }",
        "Strings.java", "public class Strings extends Outer.Middle<String>.Base<Integer> { public Strings(Outer.Middle<String> owner) { owner.super(); } }",
        "Text.java", "public interface Text extends java.util.function.BiConsumer<String, Integer> {}",
        "Wide.java", "public interface Wide { default void accept(String a, Integer b) {} }",
        "Api.java", "public class Api extends Strings implements Text, Wide { public Api() { super(new Outer.Middle<String>()); } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericMemberEnumInterfaceAndCovariance() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base { public Outer.Ordered<?> call() { return null; } }",
        "Outer.java", "public class Outer<T> { public interface Ordered<T extends Enum<T>> extends Comparable<T> {}"
        + " public enum Api implements Ordered<Api> { VALUE; public native void call(); }"
        + " public enum Reference implements java.util.function.Supplier<Outer<String>> { VALUE; public native Outer<String> get(); }"
        + " public static class Caller extends Base { public native Api call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericSupportingInterfaceConflict() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left<T> { default T value(T input) { return null; } }",
        "Right.java", "public interface Right<T> { T value(T input); }",
        "Outer.java", "public class Outer { public interface Contract<T extends CharSequence> extends Left<T>, Right<T> { T value(T input); }"
        + " public enum Api implements Contract<String> { VALUE { public String value(String input) { return null; } }; public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericEnumNativeContract() throws Exception {
    compile(classes, expected,
        "Sink.java", "public interface Sink<T> { void accept(T value); }",
        "Api.java", "public enum Api implements Sink<String> { VALUE; public native void accept(String value); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRawGenericEnumContract() throws Exception {
    compile(classes, expected,
        "Source.java", "public interface Source<T extends Number> { T get(); }",
        "Api.java", "public enum Api implements Source { VALUE { public Number get() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testErasedSupportingRecordContract() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected,
        "Container.java", "public record Container<T>(T value) implements java.util.function.Supplier<T> {"
        + " public T get() { return value; } public static class Api { public native Container<String> call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testNestedParameterizedSignatureErasure() throws Exception {
    compile(classes, expected,
        "Generic.java", "public interface Generic<T> { java.util.List<T>[] convert(java.util.List<? extends T> value); }",
        "Text.java", "public interface Text extends Generic<String> {}",
        "Api.java", "public enum Api implements Text { VALUE { public java.util.List<String>[] convert(java.util.List<? extends String> value) { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testEnumSpecializedGenericContract() throws Exception {
    compile(classes, expected,
        "Text.java", "public interface Text extends java.util.function.Supplier<String> {}",
        "Api.java", "public enum Api implements Text { VALUE { public String get() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRecordSpecializedGenericContract() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected,
        "Text.java", "public interface Text extends java.util.function.Consumer<String> {}",
        "Container.java", "public record Container(int x) implements Text { public void accept(String s) {} public static class Api { public native Container call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testInheritedCovariantBridgeDefaultConflict() throws Exception {
    compile(classes, expected,
        "Wide.java", "public interface Wide { default Object value() { return null; } }",
        "Narrow.java", "public interface Narrow { String value(); }",
        "Base.java", "public class Base { public String value() { return null; } }",
        "Api.java", "public class Api extends Base implements Wide, Narrow { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testInheritedFinalCovariantBridgeDefaultConflict() throws Exception {
    compile(classes, expected,
        "Wide.java", "public interface Wide { default Object value() { return null; } }",
        "Narrow.java", "public interface Narrow { String value(); }",
        "Base.java", "public class Base { public final String value() { return null; } }",
        "Api.java", "public class Api extends Base implements Wide, Narrow { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testTransitiveGenericContracts() throws Exception {
    compile(classes, expected,
        "Transform.java", "public interface Transform<A, B> { B[][] convert(A[] input); }",
        "Flip.java", "public interface Flip<X, Y> extends Transform<Y, X> {}",
        "Text.java", "public interface Text extends Flip<String, Integer> {}",
        "Lists.java", "public interface Lists<T> extends java.util.function.Supplier<java.util.List<T>> {}",
        "Strings.java", "public interface Strings extends Lists<String> {}",
        "Api.java", "public enum Api implements Text, Strings { VALUE {"
        + " public String[][] convert(Integer[] input) { return null; }"
        + " public java.util.List<String> get() { return null; } }; public native void call(); }",
        "Sink.java", "public interface Sink extends java.util.function.Consumer<String> {}",
        "Native.java", "public enum Native implements Sink { VALUE; public native void accept(String value); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericMethodBoundsAndShadowing() throws Exception {
    compile(classes, expected,
        "Generic.java", "public interface Generic<T> { <T extends Number> T value(); <U extends T> U adjust(U input); }",
        "Text.java", "public interface Text extends Generic<CharSequence> {}",
        "Api.java", "public enum Api implements Text { VALUE {"
        + " public <T extends Number> T value() { return null; }"
        + " public <U extends CharSequence> U adjust(U input) { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRawAndGeneratedGenericInterfaceContracts() throws Exception {
    compile(classes, expected,
        "Generic.java", "public interface Generic<T> extends java.util.function.Supplier<String> {}",
        "Api.java", "public enum Api implements Generic<Integer> { VALUE { public String get() { return null; } }; public native void call(); }",
        "Outer.java", "public class Outer { public interface Generic<T> extends java.util.function.Supplier<T> {}"
        + " public enum Api implements Generic<String> { VALUE { public String get() { return null; } }; public native void call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testSpecializedCovariantContracts() throws Exception {
    compile(classes, expected,
        "Text.java", "public interface Text extends java.util.function.Supplier<String> {}",
        "Wide.java", "public interface Wide { Object get(); }",
        "Api.java", "public enum Api implements Text, Wide { VALUE { public String get() { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Wide, Text { VALUE { public String get() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testInheritedGenericImplementation() throws Exception {
    compile(classes, expected,
        "Base.java", "public class Base<T> implements java.util.function.Supplier<T> { public final T get() { return null; } }",
        "Strings.java", "public class Strings extends Base<String> {}",
        "Text.java", "public interface Text extends java.util.function.Supplier<String> {}",
        "Wide.java", "public interface Wide { default Object get() { return null; } }",
        "Api.java", "public class Api extends Strings implements Text, Wide { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testUnrelatedGenericContractTypeRemainsUnresolved() throws Exception {
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Pair.java", "public interface Pair<A> extends java.util.function.Supplier<A> { default java.util.List<Missing> ignored() { return null; } }",
        "Text.java", "public interface Text extends Pair<String> {}",
        "Api.java", "public enum Api implements Text { VALUE { public String get() { return null; } }; public native void call(); }");
    Files.delete(new File(classes, "Missing.class").toPath());
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testExplicitComparableEnum() throws Exception {
    compile(classes, expected,
        "Api.java", "public enum Api implements Comparable<Api> { VALUE; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testGenericInterfaceAlsoInheritedFromBase() throws Exception {
    compile(classes, expected,
        "Value.java", "public interface Value<T> { T value(); }",
        "Base.java", "public abstract class Base implements Value<String> {}",
        "Api.java", "public abstract class Api extends Base implements Value<String> { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testClassResolvesDefaultConflict() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { default int value() { return 1; } }",
        "Right.java", "public interface Right { default int value() { return 2; } }",
        "Api.java", "public class Api implements Left, Right { public int value() { return 3; } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testEnumResolvesDefaultConflict() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { default int value() { return 1; } }",
        "Right.java", "public interface Right { default int value() { return 2; } }",
        "Api.java", "public enum Api implements Left, Right { VALUE; public int value() { return 3; } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testEnumConstantCovariantContracts() throws Exception {
    compile(classes, expected,
        "Narrow.java", "public interface Narrow { String value(); }",
        "Wide.java", "public interface Wide { Object value(); }",
        "Api.java", "public enum Api implements Narrow, Wide { VALUE { public String value() { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Wide, Narrow { VALUE { public String value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testDefaultOverrideAncestryAndUnrelatedMissingTypes() throws Exception {
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Root.java", "public interface Root { default int value() { return 1; } default Missing ignored() { return null; } }",
        "Left.java", "public interface Left extends Root { default int value() { return 2; } }",
        "Right.java", "public interface Right extends Root {}",
        "Required.java", "public interface Required extends Left { int value(); }",
        "Api.java", "public class Api implements Right, Left { public native void call(); }",
        "Reverse.java", "public class Reverse implements Left, Right { public native void call(); }",
        "PerConstant.java", "public enum PerConstant implements Required { VALUE { public int value() { return 3; } }; public native void call(); }");
    Files.delete(new File(classes, "Missing.class").toPath());
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testDefaultConflictThroughSuperclassAndSupportingDeclarations() throws Exception {
    compile(classes, expected,
        "Left.java", "public interface Left { default int value() { return 1; } }",
        "Right.java", "public interface Right { default int value() { return 2; } }",
        "Base.java", "public class Base implements Left { public final int value() { return 3; } }",
        "Api.java", "public class Api extends Base implements Right { public native void call(); }",
        "Native.java", "public class Native implements Left, Right { public native int value(); }",
        "Outer.java", "public class Outer implements Left, Right { public int value() { return 3; }"
        + " public static class Api extends Outer { public native void call(); } }",
        "Enclosing.java", "public interface Enclosing extends Left, Right { int value();"
        + " public class Api { public native Enclosing call(); } }",
        "Broad.java", "public class Broad { public Object text() { return null; } }",
        "First.java", "public interface First { default String text() { return null; } }",
        "Second.java", "public interface Second { default String text() { return null; } }",
        "Narrow.java", "public class Narrow extends Broad implements First, Second {"
        + " public String text() { return null; } public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRedundantGenericSuperinterfaces() throws Exception {
    compile(classes, expected,
        "Value.java", "public interface Value<T> { T value(); }",
        "Text.java", "public interface Text extends Value<String> {}",
        "Api.java", "public abstract class Api implements Value<String>, Text { public native void call(); }",
        "Reverse.java", "public abstract class Reverse implements Text, Value<String> { public native void call(); }",
        "Base.java", "public abstract class Base implements Text {}",
        "Inherited.java", "public abstract class Inherited extends Base implements Value<String>, Text { public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testCovariantArrayAndInterfaceContracts() throws Exception {
    compile(classes, expected,
        "Narrow.java", "public interface Narrow { String[][] items(); java.util.List value(); }",
        "Wide.java", "public interface Wide { Object[] items(); java.util.Collection value(); }",
        "Api.java", "public enum Api implements Narrow, Wide { VALUE { public String[][] items() { return null; }"
        + " public java.util.List value() { return null; } }; public native void call(); }",
        "Reverse.java", "public enum Reverse implements Wide, Narrow { VALUE { public String[][] items() { return null; }"
        + " public java.util.List value() { return null; } }; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRecordResolvesDefaultConflict() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected,
        "Left.java", "public interface Left { default int value() { return 1; } }",
        "Right.java", "public interface Right { default int value() { return 2; } }",
        "Container.java", "public record Container(int value) implements Left, Right {"
        + " public static class Api { public native Container call(); } }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

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

  public void testCovariantNestedInterfaceReturnWithUnrelatedMissingType() throws Exception {
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Root.java", "public interface Root { Missing ignored(); }",
        "Base.java", "public class Base { public Root call() { return null; } }",
        "Outer.java", "public class Outer { public interface Marker extends Root {}"
        + " public abstract static class Api extends Base implements Marker { public native Api call(); } }");
    Files.delete(new File(classes, "Missing.class").toPath());
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testEnumInterfaceContract() throws Exception {
    compile(classes, expected,
        "Api.java", "public enum Api implements Runnable { VALUE; public void run() {} public native Api call(); }",
        "PerConstant.java", "public enum PerConstant implements Runnable { VALUE { public void run() {} }; public native void call(); }",
        "Identity.java", "public interface Identity { boolean equals(Object o); int hashCode(); }",
        "Inherited.java", "public enum Inherited implements Identity { VALUE; public native void call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRecordInterfaceContractAndConstantOnlyTarget() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 16) { return; }
    compile(classes, expected,
        "Base.java", "public class Base { public java.util.function.IntSupplier call() { return null; } }",
        "Container.java", "public record Container(int value) implements java.util.function.IntSupplier, java.util.function.Supplier<String> {"
        + " @java.lang.annotation.Native public static final int VALUE=42; public int getAsInt() { return value; }"
        + " public String get() { return Integer.toString(value); }"
        + " public static class Api extends Base { public native Container call(); } }");
    generate(classes, Arrays.asList(classes), set("Container"), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testSealedInterfaceOnModernJdk() throws Exception {
    String version = System.getProperty("java.specification.version");
    if (version.startsWith("1.") || Integer.parseInt(version) < 17) { return; }
    compile(classes, expected,
        "Marker.java", "public sealed interface Marker permits Api {}",
        "Base.java", "public class Base { public Marker call() { return null; } }",
        "Api.java", "public final class Api extends Base implements Marker { public native Api call(); }");
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testManifestClassPathOrderAndCycles() throws Exception {
    File dependency = directory("dependency");
    compile(dependency, null, "dep/Base.java", "package dep; public class Base { public static final int VALUE=1; protected Base(String value) {} }");
    boolean modern = compilerRelease() >= 11;
    jar(modern ? "dependency one.jar" : "dependency.jar", dependency, "library.jar");
    File other = directory("other");
    compile(other, null, "dep/Base.java", "package dep; public class Base { public static final int VALUE=2; protected Base(int value) {} }");
    File otherJar = jar("other.jar", other, null);
    File empty = directory("empty");
    File library = jar("library.jar", empty, "bridge.jar missing.jar bridge.jar");
    jar("bridge.jar", empty, "library.jar " + (modern ? "dependency%20one.jar" : "dependency.jar"));
    // A transitive manifest entry precedes later explicit entries, but never earlier ones.
    for (List<File> paths : Arrays.asList(Arrays.asList(library, otherJar), Arrays.asList(otherJar, library))) {
      compileWithPath(classes, expected, paths, "Api.java", "public class Api extends dep.Base {"
          + " public Api() { super(" + (paths.get(0).equals(library) ? "null" : "0") + "); } public native void call(); }");
      List<File> classpath = new ArrayList<File>(Arrays.asList(classes));
      classpath.addAll(paths);
      generate(classes, classpath, Collections.<String>emptySet(), Collections.<String>emptySet());
      equalHeaders();
    }
    // The containing JAR is searched before its own manifest entries.
    jar("library.jar", other, "bridge.jar");
    compileWithPath(classes, expected, Arrays.asList(library), "Api.java", "public class Api extends dep.Base {"
        + " public Api() { super(0); } public native void call(); }");
    generate(classes, Arrays.asList(classes, library), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testManifestDirectoryClassPath() throws Exception {
    boolean modern = compilerRelease() >= 11;
    File dependency = directory(modern ? "dependency classes" : "dependency");
    compile(dependency, null, "dep/Base.java", "package dep; public class Base {}");
    File library = jar("library.jar", directory("empty"), modern ? "dependency%20classes/" : "dependency/");
    compileWithPath(classes, expected, Arrays.asList(library), "Api.java", "public class Api extends dep.Base { public native void call(); }");
    generate(classes, Arrays.asList(classes, library), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testRecordSupportingInterfaceOmitsUnrelatedMembers() throws Exception {
    if (compilerRelease() < 16) { return; }
    compile(classes, expected,
        "Missing.java", "public class Missing {}",
        "Outer.java", "public class Outer { public interface Value { Missing ignored(); }"
        + " public record Container(Missing value) implements Value { public Missing ignored() { return value; } }"
        + " public static class Api { public native Container call(); } }");
    Files.delete(new File(classes, "Missing.class").toPath());
    generate(classes, Arrays.asList(classes), Collections.<String>emptySet(), Collections.<String>emptySet());
    equalHeaders();
  }

  public void testManifestPathsUseSelectedCompilerVersion() throws Exception {
    File literal = directory("literal");
    File decoded = directory("decoded");
    compile(literal, null, "Api.java", "public class Api { public static final int VALUE=8; }");
    compile(decoded, null, "Api.java", "public class Api { public static final int VALUE=11; }");
    jar("dependency%20one.jar", literal, null);
    jar("dependency one.jar", decoded, null);
    File library = jar("library.jar", directory("empty"), "dependency%20one.jar");
    File javaHome = TestJavah.jdkTool("javac").getCanonicalFile().getParentFile().getParentFile();
    for (int release : new int[] {8, 9, 10, 11, 21}) {
      try (JniClassPath metadata = new JniClassPath(Arrays.asList(library), javaHome, release)) {
        assertEquals("Manifest interpretation for compiler JDK " + release,
            Integer.valueOf(release < 11 ? 8 : 11), metadata.resolve("Api").constants.get(0).value);
      }
    }
  }

  private static int compilerRelease() {
    String version = System.getProperty("java.specification.version");
    return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
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
    String diagnostics = text(new File(work, "generated/javac.log"));
    assertFalse("Unrelated compiler errors must not change constructors", diagnostics.contains("Compilation attempt 2:"));
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
    File wrapper = jar("wrapper.jar", directory("empty"), "dependency.jar");
    generate(classes, Arrays.asList(wrapper), set("Api"), Collections.<String>emptySet());
    assertEquals("Manifest lookup retains the selected multi-release view", header, text(new File(actual, "Api.h")));
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
    File diagnostics = new File(work, "generated/javac.log");
    if (diagnostics.isFile()) {
      assertFalse("Select a valid call before compiling", text(diagnostics).contains("Compilation attempt 2:"));
    }
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

  private File jar(String name, File base, String classPath) throws Exception {
    File jar = new File(work, name);
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (classPath != null) { manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath); }
    try (OutputStream stream = Files.newOutputStream(jar.toPath()); JarOutputStream out = new JarOutputStream(stream, manifest)) {
      addJarClasses(out, base, "");
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
