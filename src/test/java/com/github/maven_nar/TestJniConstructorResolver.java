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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import junit.framework.TestCase;
import org.codehaus.plexus.util.FileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import com.github.maven_nar.JniSignature.Value;

/** Independent compiler oracle: compare applicability and the selected JVM descriptor. */
public class TestJniConstructorResolver extends TestCase {
  private File work;

  @Override
  protected void setUp() throws Exception { work = Files.createTempDirectory("nar constructor oracle ").toFile(); }
  @Override
  protected void tearDown() throws Exception { FileUtils.deleteDirectory(work); }

  public void testWildcardMostSpecific() throws Exception {
    check(false, "protected Base(java.util.List<?> a, String b) {} protected <T> Base(java.util.Collection<T> a, Object b) {}", "", "(Ljava/util/List<*>;Ljava/lang/String;)V", "(java.util.List<?>) null", "(String) null");
  }
  public void testBoundedWildcardMostSpecific() throws Exception {
    check(false, "protected Base(java.util.List<? extends Number> a, String b) {} protected <T extends Number> Base(java.util.Collection<T> a, Object b) {}", "", "(Ljava/util/List<+Ljava/lang/Number;>;Ljava/lang/String;)V", "(java.util.List<? extends Number>) null", "(String) null");
  }
  public void testGenericMostSpecificWildcardTarget() throws Exception {
    check(true, "protected <T> Base(java.util.List<T> a, String b) {} protected Base(java.util.Collection<?> a, Object b) {}", "", "(Ljava/util/List<Ljava/lang/String;>;Ljava/lang/String;)V", "(java.util.List<String>) null", "(String) null");
  }
  public void testCaptureOwner() throws Exception {
    check(true, "public static class Owner<T> { public class Inner {} } protected <T> Base(Owner<T>.Inner a) {}", "", "(LBase$Owner<*>.Inner;)V", "(Base.Owner<?>.Inner) null");
  }
  public void testWildcardOwnerOverloadAmbiguity() throws Exception {
    check(false, "public static class Owner<T> { public class Inner {} }"
        + " protected Base(Owner<?>.Inner a, CharSequence b) {} protected <T> Base(Owner<T>.Inner a, Object b) {}",
        "", "(LBase$Owner<*>.Inner;Ljava/lang/CharSequence;)V", "(Base.Owner<?>.Inner) null", "(CharSequence) null");
  }

  public void testCaptureOwnerBounds() throws Exception {
    check(true, "public static class Owner<T extends Number & Runnable> { public class Inner {} }"
        + " protected <T extends Number & Runnable> Base(Owner<T>.Inner a) {}", "",
        "(LBase$Owner<*>.Inner;)V", "(Base.Owner<?>.Inner) null");
    check(true, "public static class Owner<T> { public class Inner {} }"
        + " protected <T extends Number> Base(Owner<T>.Inner a) {}", "",
        "(LBase$Owner<+Ljava/lang/Number;>.Inner;)V", "(Base.Owner<? extends Number>.Inner) null");
  }

  public void testCaptureOwnerLowerBound() throws Exception {
    String base = "public static class Owner<T> { public class Inner {} }"
        + " protected <T> Base(Owner<T>.Inner a, T b) {}";
    check(true, base, "", "(LBase$Owner<-Ljava/lang/String;>.Inner;Ljava/lang/String;)V",
        "(Base.Owner<? super String>.Inner) null", "(String) null");
    check(false, base, "", "(LBase$Owner<-Ljava/lang/String;>.Inner;Ljava/lang/Object;)V",
        "(Base.Owner<? super String>.Inner) null", "(Object) null");
  }

  public void testCaptureOwnerAndMemberDependentBounds() throws Exception {
    check(true, "public static class Owner<T extends Number> { public class Inner<U extends T> {} }"
        + " protected <U extends Number> Base(Owner<Number>.Inner<U> a) {}", "",
        "(LBase$Owner<Ljava/lang/Number;>.Inner<*>;)V", "(Base.Owner<Number>.Inner<?>) null");
    // The member capture retains the declaring owner's variable, which is not
    // the fresh wildcard capture inferred for T in the constructor invocation.
    check(false, "public static class Owner<T extends Number> { public class Inner<U extends T> {} }"
        + " protected <T extends Number, U extends T> Base(Owner<T>.Inner<U> a) {}", "",
        "(LBase$Owner<*>.Inner<*>;)V", "(Base.Owner<?>.Inner<?>) null");
  }

  public void testCaptureMultipleOwners() throws Exception {
    check(true, "public static class Owner<T extends Number> { public class Middle<U extends CharSequence> { public class Inner {} } }"
        + " protected <T extends Number, U extends CharSequence> Base(Owner<T>.Middle<U>.Inner a) {}", "",
        "(LBase$Owner<*>.Middle<*>.Inner;)V", "(Base.Owner<?>.Middle<?>.Inner) null");
    check(false, "public static class Owner<T extends Number> { public class Middle<U extends T> { public class Inner {} } }"
        + " protected <T extends Number, U extends T> Base(Owner<T>.Middle<U>.Inner a) {}", "",
        "(LBase$Owner<*>.Middle<*>.Inner;)V", "(Base.Owner<?>.Middle<?>.Inner) null");
  }

  public void testWildcardOwnerAndMemberOverloadAmbiguity() throws Exception {
    check(false, "public static class Owner<T extends Number> { public class Inner<U extends CharSequence> {} }"
        + " protected Base(Owner<?>.Inner<?> a, CharSequence b) {}"
        + " protected <T extends Number, U extends CharSequence> Base(Owner<T>.Inner<U> a, Object b) {}", "",
        "(LBase$Owner<*>.Inner<*>;Ljava/lang/CharSequence;)V", "(Base.Owner<?>.Inner<?>) null", "(CharSequence) null");
  }

  public void testIndependentOwnerCaptures() throws Exception {
    check(false, "public static class Owner<T> { public class Inner {} }"
        + " protected <T> Base(Owner<T>.Inner a, Owner<T>.Inner b) {}", "",
        "(LBase$Owner<*>.Inner;LBase$Owner<*>.Inner;)V", "(Base.Owner<?>.Inner) null", "(Base.Owner<?>.Inner) null");
  }

  public void testOwnerCaptureDoesNotCaptureNestedTypeArguments() throws Exception {
    check(false, "public static class Owner<T> { public class Inner {} }"
        + " protected <T> Base(Owner<java.util.List<T>>.Inner a) {}", "",
        "(LBase$Owner<Ljava/util/List<*>;>.Inner;)V", "(Base.Owner<java.util.List<?>>.Inner) null");
  }

  public void testCaptureShadowedMemberFormal() throws Exception {
    check(true, "public static class Owner<T extends Number> { public class Inner<T extends CharSequence> {} }"
        + " protected <S extends Number, T extends CharSequence> Base(Owner<S>.Inner<T> a) {}", "",
        "(LBase$Owner<*>.Inner<*>;)V", "(Base.Owner<?>.Inner<?>) null");
  }

  public void testNullAmbiguityAndArity() throws Exception {
    String base = "private static class A {} private static class B {}"
        + " protected Base(A a) {} protected Base(B b) {} protected Base(A a, int b) {}";
    check(false, base, "", "(Ljava/lang/Object;)V", "null");
    check(true, base, "", "(Ljava/lang/Object;I)V", "null", "(int) 0");
  }

  public void testNullMostSpecific() throws Exception {
    check(true, "private static class A {} private static class B extends A {}"
        + " protected Base(A a) {} protected Base(B b) {}", "", "(Ljava/lang/Object;)V", "null");
  }

  public void testEquivalentIntersectionBoundsAreAmbiguous() throws Exception {
    String base = "protected <T extends Runnable & java.io.Serializable> Base(T a) {}"
        + " protected <T extends java.io.Serializable & Runnable, U> Base(T a) {}";
    check(false, base, "", "(Ljava/lang/Object;)V", "null");
    check(false, base, "<X extends Runnable & java.io.Serializable>",
        "<X::Ljava/lang/Runnable;:Ljava/io/Serializable;>(TX;)V", "(X) null");
  }

  public void testPrimitiveWidening() throws Exception {
    String base = "protected Base(short a) {} protected Base(int a) {} protected Base(long a) {}"
        + " protected Base(float a) {} protected Base(double a) {} protected Base(boolean a) {}";
    check(true, base, "", "(B)V", "(byte) 0");
    check(true, base, "", "(C)V", "(char) 0");
    check(true, base, "", "(J)V", "(long) 0");
    check(true, base, "", "(Z)V", "false");
  }

  public void testStrictInvocationPrecedesBoxingAndVarargsExpansion() throws Exception {
    String base = "protected Base(int a) {} protected Base(Integer a) {} protected Base(int... a) {}";
    check(true, base, "", "(I)V", "(int) 0");
    check(true, base, "", "(Ljava/lang/Integer;)V", "(Integer) null");
    check(true, base, "", "([I)V", "(int[]) null");
    check(false, base, "", "(Ljava/lang/Object;)V", "null");
  }

  public void testPrimitiveArraysDoNotWidenTheirElements() throws Exception {
    check(true, "protected Base(Object a) {} protected Base(int[] a) {}", "", "([B)V", "(byte[]) null");
    check(true, "protected Base(Object a) {} protected Base(Object[] a) {}", "", "([[B)V", "(byte[][]) null");
  }

  public void testMostSpecificGenericDeclaration() throws Exception {
    check(true, "protected <T> Base(T a) {} protected Base(String a) {}", "", "(Ljava/lang/String;)V", "(String) null");
    check(true, "protected <T extends Runnable & java.io.Serializable> Base(T a) {} protected Base(java.io.Serializable a) {}",
        "<X extends Runnable & java.io.Serializable>", "<X::Ljava/lang/Runnable;:Ljava/io/Serializable;>(TX;)V", "(X) null");
  }

  public void testRawConversionDoesNotEraseOtherArgumentBounds() throws Exception {
    check(true, "protected Base(java.util.List<String> a, String b) {}"
        + " protected <T extends Number> Base(java.util.Collection<T> a, T b) {}", "",
        "(Ljava/util/List;Ljava/lang/String;)V", "(java.util.List) null", "(String) null");
  }

  public void testRawConversionAndSpecificity() throws Exception {
    check(false, "protected Base(java.util.List<String> a) {} protected Base(java.util.Collection<Integer> a) {}", "",
        "(Ljava/util/List;)V", "(java.util.List) null");
    check(true, "protected Base(java.util.List<String> a) {} protected Base(java.util.Collection<String> a) {}", "",
        "(Ljava/util/List;)V", "(java.util.List) null");
  }

  public void testInvariantInferenceEquality() throws Exception {
    String base = "protected <T> Base(java.util.List<T> a, T b) {}";
    check(true, base, "", "(Ljava/util/List<Ljava/lang/String;>;Ljava/lang/String;)V", "(java.util.List<String>) null", "(String) null");
    check(false, base, "", "(Ljava/util/List<Ljava/lang/String;>;Ljava/lang/Integer;)V", "(java.util.List<String>) null", "(Integer) null");
  }

  public void testDependentBounds() throws Exception {
    String base = "protected <T extends Number & Comparable<T>, U extends T> Base(T a, U b) {}";
    check(true, base, "", "(Ljava/lang/Integer;Ljava/lang/Integer;)V", "(Integer) null", "(Integer) null");
    check(false, base, "", "(Ljava/lang/Integer;Ljava/lang/Double;)V", "(Integer) null", "(Double) null");
  }

  public void testWildcardLowerInference() throws Exception {
    String base = "protected <T> Base(java.util.List<? super T> a, T b) {}";
    check(true, base, "", "(Ljava/util/List<Ljava/lang/CharSequence;>;Ljava/lang/String;)V",
        "(java.util.List<CharSequence>) null", "(String) null");
    check(false, base, "", "(Ljava/util/List<Ljava/lang/Integer;>;Ljava/lang/String;)V",
        "(java.util.List<Integer>) null", "(String) null");
  }

  public void testWildcardUpperProjection() throws Exception {
    String base = "protected <T extends CharSequence> Base(java.util.Collection<? extends T> a, T b) {}";
    check(true, base, "", "(Ljava/util/ArrayList<Ljava/lang/String;>;Ljava/lang/String;)V",
        "(java.util.ArrayList<String>) null", "(String) null");
    check(false, base, "", "(Ljava/util/ArrayList<Ljava/lang/Integer;>;Ljava/lang/String;)V",
        "(java.util.ArrayList<Integer>) null", "(String) null");
  }

  public void testRecursiveBounds() throws Exception {
    String base = "protected <T extends Comparable<? super T>> Base(T a) {}";
    check(true, base, "", "(Ljava/lang/String;)V", "(String) null");
    check(false, base, "", "(Ljava/lang/Object;)V", "(Object) null");
    check(true, base, "<X extends Number & Comparable<? super X>>",
        "<X:Ljava/lang/Number;:Ljava/lang/Comparable<-TX;>;>(TX;)V", "(X) null");
  }

  public void testCaptureUpperAndLowerBounds() throws Exception {
    String base = "protected <T> Base(java.util.List<T> a, T b) {}";
    check(true, base, "", "(Ljava/util/List<-Ljava/lang/String;>;Ljava/lang/String;)V",
        "(java.util.List<? super String>) null", "(String) null");
    check(false, base, "", "(Ljava/util/List<+Ljava/lang/CharSequence;>;Ljava/lang/String;)V",
        "(java.util.List<? extends CharSequence>) null", "(String) null");
  }

  public void testIndependentCapturesAreNotEqual() throws Exception {
    check(false, "protected <T> Base(java.util.List<T> a, java.util.List<T> b) {}", "",
        "(Ljava/util/List<*>;Ljava/util/List<*>;)V", "(java.util.List<?>) null", "(java.util.List<?>) null");
  }

  public void testUncheckedRecursiveBound() throws Exception {
    check(true, "protected <T extends Comparable<T>> Base(T a) {}", "",
        "(Ljava/lang/Comparable;)V", "(Comparable) null");
    check(false, "protected Base(Comparable a, String b) {}"
        + " protected <T extends Comparable<T>> Base(T a, Object b) {}", "",
        "(Ljava/lang/Comparable;Ljava/lang/String;)V", "(Comparable) null", "(String) null");
  }

  public void testCaptureRetainsDeclaredIntersection() throws Exception {
    check(true, "public static class Values<T extends Number & Runnable> extends java.util.ArrayList<T> {}"
        + " protected <T extends Number & Runnable> Base(java.util.List<T> a) {}", "",
        "(LBase$Values<*>;)V", "(Base.Values<?>) null");
  }

  public void testWildcardArrayInvariance() throws Exception {
    check(false, "protected <T> Base(java.util.List<T>[] a) {}", "",
        "([Ljava/util/List<*>;)V", "(java.util.List<?>[]) null");
    check(true, "protected <T> Base(java.util.List<T>[] a) {}", "",
        "([Ljava/util/List;)V", "(java.util.List[]) null");
  }

  public void testCapturedArrayBounds() throws Exception {
    check(true, "protected <T> Base(java.util.List<? extends T[]> a) {}", "",
        "(Ljava/util/List<+[Ljava/lang/String;>;)V", "(java.util.List<? extends String[]>) null");
    check(true, "protected <T> Base(java.util.List<? extends T> a) {}", "",
        "(Ljava/util/List<+[Ljava/lang/String;>;)V", "(java.util.List<? extends String[]>) null");
  }

  public void testFinalClassCannotSatisfyUnrelatedInterfaceBound() throws Exception {
    check(false, "protected <T extends Number & Runnable> Base(T a) {}", "", "(Ljava/lang/Integer;)V", "(Integer) null");
    check(true, "private interface Hidden {} protected <T extends Number & Hidden> Base(T a) {}", "",
        "(Ljava/lang/Object;)V", "null");
  }

  private void check(boolean accepted, String constructors, String formals, String argumentSignature, String... expressions)
      throws Exception {
    File classes = Files.createTempDirectory(work.toPath(), "case").toFile();
    assertTrue("Constructor declarations must compile", compile(classes, "Base", "public class Base {" + constructors + "}"));
    StringBuilder call = new StringBuilder("public class Probe extends Base { ").append(formals)
        .append(" Probe() throws Throwable { super(");
    for (int i = 0; i < expressions.length; i++) { if (i != 0) { call.append(","); } call.append(expressions[i]); }
    call.append("); } }");
    boolean compiled = compile(classes, "Probe", call.toString());
    assertEquals("Compiler oracle for " + call, accepted, compiled);
    final String[] selected = new String[1];
    if (compiled) {
      new ClassReader(Files.readAllBytes(new File(classes, "Probe.class").toPath())).accept(new ClassVisitor(Opcodes.ASM9) {
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
          return new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
              if (owner.equals("Base") && name.equals("<init>")) { selected[0] = descriptor; }
            }
          };
        }
      }, 0);
    }
    File home = TestJavah.jdkTool("javac").getCanonicalFile().getParentFile().getParentFile();
    String version = System.getProperty("java.specification.version");
    int release = Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    try (final JniClassPath metadata = new JniClassPath(Arrays.asList(classes), home, release)) {
      JniConstructorResolver resolver = new JniConstructorResolver(new JniConstructorResolver.Types() {
        private Map<String, Value> arguments(Value type) throws IOException {
          JniClass model = metadata.resolve(type.name);
          Map<String, Value> enclosing = model.innerInstance() && type.owner != null
              ? arguments(type.owner) : Collections.<String, Value>emptyMap();
          Map<String, Value> scope = new java.util.HashMap<String, Value>(enclosing);
          scope.putAll(JniSignature.read(model.signature).bind(type.arguments, enclosing));
          return scope;
        }
        public List<Value> parents(Value type) throws IOException {
          JniClass model = metadata.resolve(type.name);
          List<Value> result = new ArrayList<Value>();
          if (model.signature == null || (type.arguments.isEmpty() && !JniSignature.read(model.signature).bounds.isEmpty())) {
            if (model.parent != null) { result.add(Value.object(model.parent)); }
            for (String iface : model.interfaces) { result.add(Value.object(iface)); }
          } else {
            for (Value parent : JniSignature.read(model.signature).parents) { result.add(parent.substitute(arguments(type))); }
          }
          return result;
        }
        public List<List<Value>> parameterBounds(Value type) throws IOException {
          JniClass model = metadata.resolve(type.name);
          JniSignature signature = JniSignature.read(model.signature);
          Map<String, Value> enclosing = enclosingVariables(model);
          Map<String, Value> scope = new java.util.HashMap<String, Value>(enclosing);
          scope.putAll(signature.bind(type.arguments, enclosing));
          List<List<Value>> result = new ArrayList<List<Value>>();
          for (List<Value> bounds : signature.bounds.values()) {
            List<Value> values = new ArrayList<Value>();
            for (Value bound : bounds) { values.add(bound.substitute(scope)); }
            result.add(values);
          }
          return result;
        }
        private Map<String, Value> enclosingVariables(JniClass model) throws IOException {
          Map<String, Value> scope = new java.util.HashMap<String, Value>();
          if (model.innerInstance()) {
            JniClass owner = metadata.resolve(model.outer());
            scope.putAll(enclosingVariables(owner));
            scope.putAll(JniSignature.read(owner.signature).variables(scope, "#owner:" + owner.name + ":"));
          }
          return scope;
        }
        public boolean isInterface(String name) throws IOException { return metadata.resolve(name).isInterface(); }
        public boolean isFinal(String name) throws IOException { return (metadata.resolve(name).access & Opcodes.ACC_FINAL) != 0; }
      });
      List<JniConstructorResolver.Candidate> candidates = new ArrayList<JniConstructorResolver.Candidate>();
      for (JniClass.Method method : metadata.resolve("Base").constructors) {
        JniSignature signature = JniSignature.read(method.signature);
        if (method.signature == null) {
          for (Type argument : Type.getArgumentTypes(method.descriptor)) { signature.parameters.add(Value.type(argument)); }
        }
        candidates.add(new JniConstructorResolver.Candidate(method.descriptor, signature.parameters, signature.bounds));
      }
      JniSignature arguments = JniSignature.read(argumentSignature);
      for (int i = 0; i < expressions.length; i++) { if (expressions[i].equals("null")) { arguments.parameters.set(i, null); } }
      try {
        JniConstructorResolver.Candidate result = resolver.resolve(arguments.parameters, arguments.bounds, candidates);
        assertTrue("Resolver accepted a call rejected by javac: " + call, accepted);
        assertEquals("Select the same constructor as javac", selected[0], result.descriptor);
      } catch (IOException ex) {
        if (accepted) { throw new AssertionError("Resolver rejected a call accepted by javac: " + call, ex); }
        assertTrue(ex.getMessage(), ex.getMessage().contains("ambiguous constructor")
            || ex.getMessage().contains("no constructor applicable"));
      }
    }
  }

  private boolean compile(File classes, String name, String source) throws IOException {
    File file = new File(classes, name + ".java");
    Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    return ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, "-proc:none", "-encoding", "UTF-8",
        "-classpath", classes.getPath(), "-d", classes.getPath(), file.getPath()) == 0;
  }
}
