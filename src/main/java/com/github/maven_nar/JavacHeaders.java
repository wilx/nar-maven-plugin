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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Rebuilds only JNI declarations; javac remains responsible for the JNI ABI. */
final class JavacHeaders {
  private final File javac;
  private final File work;
  private final Log log;
  private final List<File> classPath;
  private final List<File> bootClassPath;
  private final JniClassPath metadata;
  private final Set<String> targets = new TreeSet<String>();
  private final Map<String, JniClass> declarations = new TreeMap<String, JniClass>();
  private final Map<String, String> constructors = new HashMap<String, String>();
  private final Map<String, List<JniClass.Method>> interfaceMethods = new HashMap<String, List<JniClass.Method>>();
  private final Map<String, JniSignature> signatures = new HashMap<String, JniSignature>();
  private final Set<String> references = new HashSet<String>();

  JavacHeaders(File javac, File work, List<File> classPath, List<File> bootClassPath, Log log) throws IOException {
    this.javac = javac;
    this.work = work;
    this.log = log;
    this.classPath = classPath;
    this.bootClassPath = bootClassPath;
    String version = run(Arrays.asList(javac.getAbsolutePath(), "-version"));
    Matcher matcher = Pattern.compile("javac (?:1\\.)?(\\d+)").matcher(version);
    if (!matcher.find()) { throw new IOException("Cannot determine compiler JDK version: " + version); }
    int release = Integer.parseInt(matcher.group(1));
    if (release < 8) { throw new IOException("javac header generation requires JDK 8 or newer: " + version); }
    File javaHome = javac.getCanonicalFile().getParentFile().getParentFile();
    List<File> paths = new ArrayList<File>(bootClassPath);
    paths.addAll(classPath);
    metadata = new JniClassPath(paths, javaHome, release);
  }

  void generate(File classes, Set<?> includes, Set<?> excludes, Set<?> extraClasses, File output) throws IOException {
    try (JniClassPath ignored = metadata) {
      generateHeaders(classes, includes, excludes, extraClasses, output);
    }
  }

  private void generateHeaders(File classes, Set<?> includes, Set<?> excludes, Set<?> extraClasses, File output)
      throws IOException {
    DirectoryScanner index = new DirectoryScanner();
    index.setBasedir(classes);
    index.setIncludes(new String[] {"**/*.class"});
    index.scan();
    Map<String, JniClass> local = new HashMap<String, JniClass>();
    for (String path : index.getIncludedFiles()) {
      local.put(path, metadata.index(new File(classes, path)));
    }
    DirectoryScanner selected = new DirectoryScanner();
    selected.setBasedir(classes);
    selected.setIncludes(strings(includes));
    selected.setExcludes(strings(excludes));
    selected.scan();
    for (String path : selected.getIncludedFiles()) {
      JniClass model = local.get(path);
      if (model != null && !model.natives.isEmpty()) { targets.add(model.name); }
    }
    if (extraClasses != null) {
      for (Object name : extraClasses) { if (name != null) { targets.add(name.toString().replace('.', '/')); } }
    }
    if (targets.isEmpty()) { return; }
    Set<String> filenames = new HashSet<String>();
    for (String name : targets) {
      JniClass model = metadata.resolve(name);
      if (model.platform) { throw new IOException("Unsupported JNI header target from the JDK/platform: " + name); }
      // Case folding also protects builds on case-insensitive filesystems.
      if (!filenames.add(header(name).toLowerCase(Locale.ROOT))) {
        throw new IOException("JNI header filename collision for " + name + ": " + header(name));
      }
      declare(model);
    }
    prepare();

    // Everything is validated before writing sources or touching published headers.
    File sources = new File(work, "sources");
    File compiled = new File(work, "classes");
    File staged = new File(work, "headers");
    FileUtils.deleteDirectory(work);
    Files.createDirectories(sources.toPath());
    Files.createDirectories(compiled.toPath());
    Files.createDirectories(staged.toPath());
    List<String> args = new ArrayList<String>();
    Collections.addAll(args, "-encoding", "UTF-8", "-proc:none", "-implicit:none",
        "-sourcepath", sources.getAbsolutePath(), "-classpath", path(classPath),
        "-d", compiled.getAbsolutePath(), "-h", staged.getAbsolutePath());
    if (!bootClassPath.isEmpty()) { Collections.addAll(args, "-bootclasspath", path(bootClassPath)); }
    for (JniClass model : declarations.values()) {
      if (model.outer() != null) { continue; }
      StringBuilder source = new StringBuilder();
      if (!model.packageName().isEmpty()) {
        source.append("package ").append(model.packageName().replace('/', '.')).append(";\n");
      }
      emit(model, source, "");
      File file = new File(sources, model.name + ".java");
      Files.createDirectories(file.getParentFile().toPath());
      Files.write(file.toPath(), source.toString().getBytes(StandardCharsets.UTF_8));
      args.add(file.getAbsolutePath());
    }
    StringBuilder argumentFile = new StringBuilder();
    for (String arg : args) {
      argumentFile.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\""))
          .append('"').append('\n');
    }
    File arguments = new File(work, "javac.args");
    Files.write(arguments.toPath(), argumentFile.toString().getBytes(StandardCharsets.UTF_8));
    log.info("Generating JNI headers with " + javac + " for " + targets.size() + " classes");
    try {
      String diagnostics = run(Arrays.asList(javac.getAbsolutePath(), "-J-Dfile.encoding=UTF-8", "@" + arguments.getAbsolutePath()));
      Files.write(new File(work, "javac.log").toPath(), diagnostics.getBytes(StandardCharsets.UTF_8));
      if (!diagnostics.trim().isEmpty()) { log.info(diagnostics); }
    } catch (IOException ex) {
      Files.write(new File(work, "javac.log").toPath(), ex.getMessage().getBytes(StandardCharsets.UTF_8));
      throw ex;
    }
    for (String name : targets) {
      if (!new File(staged, header(name)).isFile()) {
        throw new IOException("javac did not generate the requested JNI header " + header(name) + " for " + name);
      }
    }
    // Support declarations must never cause an unrequested header to be published.
    Files.createDirectories(output.toPath());
    for (String name : targets) {
      Files.copy(new File(staged, header(name)).toPath(), new File(output, header(name)).toPath(),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void declare(JniClass model) throws IOException {
    metadata.sourceName(model.name); // validates the entire containment chain
    if (declarations.put(model.name, model) == null && model.outer() != null) {
      declare(metadata.resolve(model.outer()));
    }
  }

  private void prepare() throws IOException {
    int previous;
    do {
      previous = declarations.size();
      constructors.clear();
      interfaceMethods.clear();
      for (JniClass model : new ArrayList<JniClass>(declarations.values())) {
        for (String iface : model.interfaces) { reference(Type.getObjectType(iface)); }
        for (JniClass.Field field : model.constants) { metadata.identifier(field.name, model.name); }
        if (!model.isInterface() && !model.isEnum() && !model.isRecord()) {
          hierarchy(model.name, new HashSet<String>());
          if (model.parent != null) { reference(Type.getObjectType(model.parent)); }
          constructors.put(model.name, constructor(model));
        }
        if (targets.contains(model.name)) {
          for (JniClass.Method method : model.natives) {
            metadata.identifier(method.name, model.name);
            reference(Type.getReturnType(method.descriptor));
            for (Type arg : Type.getArgumentTypes(method.descriptor)) { reference(arg); }
          }
        }
      }
      declareReferencedMembers();
      if (declarations.size() != previous) { continue; }
      // Establish which interfaces are source stubs before resolving method
      // contracts: methods omitted from those interfaces need no implementation.
      for (JniClass model : new ArrayList<JniClass>(declarations.values())) {
        if (!model.isInterface() || !model.interfaces.isEmpty()) {
          List<JniClass.Method> methods = interfaceMethods(model);
          interfaceMethods.put(model.name, methods);
          for (JniClass.Method method : methods) {
            metadata.identifier(method.name, model.name);
            reference(Type.getReturnType(method.descriptor));
            for (Type arg : Type.getArgumentTypes(method.descriptor)) { reference(arg); }
          }
        }
      }
      declareReferencedMembers();
    } while (declarations.size() != previous);
  }

  private void declareReferencedMembers() throws IOException {
    // A source outer hides its classpath members. Retain only members actually
    // referenced by a signature, superclass, interface or selected constructor.
    for (String name : new ArrayList<String>(references)) {
      JniClass model = metadata.resolve(name);
      if (declarations.containsKey(root(model).name)) { declare(model); }
    }
  }

  private JniClass root(JniClass model) throws IOException {
    metadata.sourceName(model.name);
    while (model.outer() != null) { model = metadata.resolve(model.outer()); }
    return model;
  }

  private void hierarchy(String name, Set<String> visiting) throws IOException {
    if (!visiting.add(name)) { throw new IOException("Inheritance cycle involving " + name); }
    JniClass model = metadata.resolve(name);
    references.add(name);
    if (model.parent != null) { hierarchy(model.parent, visiting); }
    for (String iface : model.interfaces) { hierarchy(iface, visiting); }
    visiting.remove(name);
  }

  private void reference(Type type) throws IOException {
    if (type.getSort() == Type.ARRAY) { reference(type.getElementType()); }
    if (type.getSort() == Type.OBJECT) {
      String name = type.getInternalName();
      metadata.sourceName(name);
      hierarchy(name, new HashSet<String>());
      references.add(name);
    }
  }

  private static String methodKey(JniClass.Method method) {
    return method.name + method.descriptor.substring(0, method.descriptor.indexOf(')') + 1);
  }

  /** The declaring interface matters: a more specific declaration overrides defaults and abstracts alike. */
  private static final class Contract {
    final String owner;
    final JniClass.Method method;
    Contract(String owner, JniClass.Method method) { this.owner = owner; this.method = method; }
  }

  /** The effective type as javac sees it, after any generated declaration erases its own generics. */
  private static final class TypeView {
    final JniClass model;
    final Map<String, Type> arguments;
    final boolean raw;
    TypeView(JniClass model, Map<String, Type> arguments, boolean raw) {
      this.model = model; this.arguments = arguments; this.raw = raw;
    }
    String key() { return model.name + ":" + raw + arguments; }
    JniClass.Method method(JniClass.Method method) {
      return raw || method.signature == null ? method : JniSignature.read(method.signature).method(method, arguments);
    }
  }

  private JniSignature signature(JniClass model) {
    JniSignature signature = signatures.get(model.name);
    if (signature == null) { signature = JniSignature.read(model.signature); signatures.put(model.name, signature); }
    return signature;
  }

  private TypeView view(String name, List<Type> arguments) throws IOException {
    JniClass model = metadata.resolve(name);
    JniSignature signature = signature(model);
    if (arguments.isEmpty()) {
      return new TypeView(model, Collections.<String, Type>emptyMap(), !signature.bounds.isEmpty());
    }
    if (arguments.size() != signature.bounds.size()) {
      throw new IOException("Inconsistent generic type arguments for " + name);
    }
    return new TypeView(model, signature.bind(arguments), false);
  }

  private TypeView rawView(String name) throws IOException { return view(name, Collections.<Type>emptyList()); }

  private List<TypeView> parents(TypeView type) throws IOException {
    JniClass model = type.model;
    List<TypeView> result = new ArrayList<TypeView>();
    if (declarations.containsKey(model.name)) {
      // Match the source we emit, including Enum<Self>'s implicit specialization.
      if (model.parent != null) {
        result.add(model.isEnum() ? view(model.parent, Collections.singletonList(Type.getObjectType(model.name)))
            : rawView(model.parent));
      }
      for (String iface : directInterfaces(model)) { result.add(rawView(iface)); }
    } else if (type.raw || model.signature == null) {
      // The supertypes of a raw type are themselves erased.
      if (model.parent != null) { result.add(rawView(model.parent)); }
      for (String iface : model.interfaces) { result.add(rawView(iface)); }
    } else {
      for (JniSignature.Value parent : signature(model).parents) {
        List<Type> arguments = new ArrayList<Type>();
        for (JniSignature.Value argument : parent.arguments) { arguments.add(argument.erase(type.arguments)); }
        result.add(view(parent.name, arguments));
      }
    }
    return result;
  }

  private void interfaceMethods(TypeView type, Map<String, List<Contract>> methods, Set<String> visited)
      throws IOException {
    if (!visited.add(type.key())) { return; }
    for (TypeView parent : parents(type)) { interfaceMethods(parent, methods, visited); }
    JniClass model = type.model;
    if (model.isInterface() && !declarations.containsKey(model.name)) {
      for (JniClass.Method original : model.instanceMethods) {
        if ((original.access & Opcodes.ACC_BRIDGE) != 0) { continue; }
        JniClass.Method method = type.method(original);
        String key = methodKey(method);
        List<Contract> contracts = methods.get(key);
        if (contracts == null) { contracts = new ArrayList<Contract>(); methods.put(key, contracts); }
        contracts.add(new Contract(model.name, method));
      }
    }
  }

  private List<JniClass.Method> interfaceMethods(JniClass model) throws IOException {
    Map<String, List<Contract>> methods = new TreeMap<String, List<Contract>>();
    interfaceMethods(rawView(model.name), methods, new HashSet<String>());
    Map<String, JniClass.Method> implementations = new HashMap<String, JniClass.Method>();
    for (JniClass.Method method : model.instanceMethods) {
      // Bridges are bytecode adapters, not source overrides. In particular a
      // bridge may delegate to a narrower (possibly final) superclass method.
      if ((method.access & Opcodes.ACC_BRIDGE) == 0) { implementations.put(methodKey(method), method); }
    }
    List<JniClass.Method> result = new ArrayList<JniClass.Method>();
    for (Map.Entry<String, List<Contract>> entry : methods.entrySet()) {
      List<Contract> contracts = new ArrayList<Contract>();
      for (Contract candidate : entry.getValue()) {
        boolean overridden = false;
        for (Contract other : entry.getValue()) {
          if (!candidate.owner.equals(other.owner) && subtype(other.owner, candidate.owner)) {
            overridden = true; break;
          }
        }
        if (!overridden) { contracts.add(candidate); }
      }
      boolean hasDefault = false;
      for (Contract contract : contracts) {
        if ((contract.method.access & Opcodes.ACC_ABSTRACT) == 0) { hasDefault = true; }
      }
      // A single surviving default needs no stub. Abstract classes need only
      // conflict resolvers; do not resolve unrelated abstract method signatures.
      if (contracts.size() == 1 && hasDefault) { continue; }
      boolean concrete = model.isEnum() || model.isRecord();
      if (!concrete && !hasDefault && contracts.size() == 1) { continue; }
      JniClass.Method own = implementations.get(entry.getKey());
      if (targets.contains(model.name) && own != null && (own.access & Opcodes.ACC_NATIVE) != 0) { continue; }
      // Do not override retained superclass implementations, notably Enum's final methods.
      if (own == null && inheritsImplementation(model, entry.getKey())) { continue; }
      JniClass.Method contract = compatibleReturn(contracts);
      if (!concrete && !hasDefault && contract != null) { continue; }
      JniClass.Method method = own == null ? contract : own;
      if (method == null) {
        throw new IOException("No compatible interface return type for " + model.name + "." + entry.getKey());
      }
      result.add(method);
    }
    return result;
  }

  private JniClass.Method compatibleReturn(List<Contract> contracts) throws IOException {
    // Pick a return assignable to every contract, regardless of interface order.
    for (Contract candidate : contracts) {
      boolean compatible = true;
      for (Contract other : contracts) {
        if (!subtype(Type.getReturnType(candidate.method.descriptor), Type.getReturnType(other.method.descriptor))) {
          compatible = false; break;
        }
      }
      if (compatible) { return candidate.method; }
    }
    return null;
  }

  private boolean subtype(Type child, Type parent) throws IOException {
    if (child.equals(parent)) { return true; }
    if (child.getSort() == Type.ARRAY) {
      if (parent.getSort() == Type.ARRAY) {
        return subtype(Type.getType(child.getDescriptor().substring(1)), Type.getType(parent.getDescriptor().substring(1)));
      }
      return parent.equals(Type.getType(Object.class)) || parent.equals(Type.getType(Cloneable.class))
          || parent.equals(Type.getType(java.io.Serializable.class));
    }
    return child.getSort() == Type.OBJECT && parent.getSort() == Type.OBJECT
        && subtype(child.getInternalName(), parent.getInternalName());
  }

  private boolean subtype(String child, String parent) throws IOException {
    return subtype(child, parent, new HashSet<String>());
  }

  private boolean subtype(String child, String parent, Set<String> visited) throws IOException {
    if (child.equals(parent) || "java/lang/Object".equals(parent)) { return true; }
    if (!visited.add(child)) { return false; }
    JniClass model = metadata.resolve(child);
    if (model.parent != null && subtype(model.parent, parent, visited)) { return true; }
    for (String iface : model.interfaces) { if (subtype(iface, parent, visited)) { return true; } }
    return false;
  }

  private boolean inheritsImplementation(JniClass model, String key) throws IOException {
    TypeView type = rawView(model.name);
    while (type.model.parent != null) {
      type = parents(type).get(0);
      for (JniClass.Method original : type.model.instanceMethods) {
        if ((original.access & Opcodes.ACC_BRIDGE) != 0) { continue; }
        JniClass.Method method = type.method(original);
        if (key.equals(methodKey(method))) {
          if (declarations.containsKey(type.model.name)) { return false; } // its original bodies are omitted
          return (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)) == Opcodes.ACC_PUBLIC;
        }
      }
    }
    return false;
  }

  private List<String> directInterfaces(JniClass model) throws IOException {
    List<String> result = new ArrayList<String>();
    for (String iface : model.interfaces) {
      boolean inherited = model.parent != null && subtype(model.parent, iface);
      for (String other : model.interfaces) {
        if (!iface.equals(other) && subtype(other, iface)) { inherited = true; break; }
      }
      // Keep the inherited instantiation (e.g. Enum<E>'s Comparable<E>),
      // rather than adding an incompatible raw version of the same interface.
      if (!inherited) { result.add(iface); }
    }
    return result;
  }

  private boolean hasSealedParent(JniClass model) throws IOException {
    List<String> parents = new ArrayList<String>(directInterfaces(model));
    if (model.parent != null) { parents.add(model.parent); }
    for (String parent : parents) {
      if (!declarations.containsKey(parent) && metadata.resolve(parent).sealed) { return true; }
    }
    return false;
  }

  private String type(Type type) throws IOException {
    if (type.getSort() == Type.ARRAY) {
      StringBuilder result = new StringBuilder(type(type.getElementType()));
      for (int i = 0; i < type.getDimensions(); i++) { result.append("[]"); }
      return result.toString();
    }
    return type.getSort() == Type.OBJECT ? metadata.sourceName(type.getInternalName()) : type.getClassName();
  }

  private String constructor(JniClass model) throws IOException {
    if (model.parent == null) { return ""; }
    JniClass parent = metadata.resolve(model.parent);
    String invocation = "super(";
    int skip = 0;
    if (parent.innerInstance()) {
      reference(Type.getObjectType(parent.outer()));
      invocation = "((" + metadata.sourceName(parent.outer()) + ") null).super(";
      skip = 1;
    }
    if (declarations.containsKey(parent.name)) { return invocation + ");"; }
    List<JniClass.Method> candidates = new ArrayList<JniClass.Method>(parent.constructors);
    Collections.sort(candidates, new Comparator<JniClass.Method>() {
      public int compare(JniClass.Method left, JniClass.Method right) {
        int result = Type.getArgumentTypes(left.descriptor).length - Type.getArgumentTypes(right.descriptor).length;
        return result == 0 ? left.descriptor.compareTo(right.descriptor) : result;
      }
    });
    List<String> rejected = new ArrayList<String>();
    for (JniClass.Method candidate : candidates) {
      if (!accessible(candidate.access, parent, model, true)) {
        rejected.add(candidate.descriptor + " (inaccessible)"); continue;
      }
      try {
        Type[] arguments = Type.getArgumentTypes(candidate.descriptor);
        if (skip == 1 && (arguments.length == 0 || !Type.getObjectType(parent.outer()).equals(arguments[0]))) {
          throw new IOException("missing enclosing-instance parameter");
        }
        StringBuilder call = new StringBuilder(invocation);
        for (int i = skip; i < arguments.length; i++) {
          Type arg = arguments[i];
          reference(arg);
          Type element = arg.getSort() == Type.ARRAY ? arg.getElementType() : arg;
          if (element.getSort() == Type.OBJECT) {
            JniClass parameter = metadata.resolve(element.getInternalName());
            while (true) {
              if (!accessible(parameter.nesting == null ? parameter.access : parameter.nesting.access,
                  parameter, model, false)) { throw new IOException("inaccessible parameter type " + parameter.name); }
              if (parameter.outer() == null) { break; }
              parameter = metadata.resolve(parameter.outer());
            }
          }
          if (i > skip) { call.append(", "); }
          call.append(defaultValue(arg));
        }
        return call.append(");").toString();
      } catch (IOException ex) { rejected.add(candidate.descriptor + " (" + ex.getMessage() + ")"); }
    }
    throw new IOException("Cannot construct superclass " + parent.name + " for " + model.name
        + ": no accessible, source-expressible constructor " + rejected);
  }

  private boolean accessible(int access, JniClass owner, JniClass context, boolean constructor) throws IOException {
    if ((access & Opcodes.ACC_PUBLIC) != 0) { return true; }
    if ((access & Opcodes.ACC_PRIVATE) != 0) { return root(owner).name.equals(root(context).name); }
    if (owner.packageName().equals(context.packageName())) { return true; }
    if ((access & Opcodes.ACC_PROTECTED) == 0) { return false; }
    if (constructor) { return true; }
    // A protected member type is accessible in a subclass of its declaring outer.
    for (JniClass scope = context; scope != null;
        scope = scope.outer() == null ? null : metadata.resolve(scope.outer())) {
      for (JniClass ancestor = scope; ancestor != null;
          ancestor = ancestor.parent == null ? null : metadata.resolve(ancestor.parent)) {
        if (ancestor.name.equals(owner.outer())) { return true; }
      }
    }
    return false;
  }

  private String defaultValue(Type type) throws IOException {
    if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) { return "(" + type(type) + ") null"; }
    if (type.getSort() == Type.BOOLEAN) { return "false"; }
    return "(" + type(type) + ") 0";
  }

  private void emit(JniClass model, StringBuilder out, String indent) throws IOException {
    out.append(indent).append(access(model.nesting == null ? model.access : model.nesting.access));
    if (model.outer() != null && (model.nesting.access & Opcodes.ACC_STATIC) != 0) { out.append("static "); }
    if (!model.isEnum() && !model.isRecord() && hasSealedParent(model)) { out.append("non-sealed "); }
    if (model.isInterface()) { out.append("interface "); }
    else if (model.isEnum()) { out.append("enum "); }
    else if (model.isRecord()) { out.append("record "); }
    else {
      out.append("abstract class ");
    }
    out.append(model.simple());
    if (model.isRecord()) { out.append("()"); }
    if (!model.isInterface() && !model.isEnum() && !model.isRecord()
        && model.parent != null && !"java/lang/Object".equals(model.parent)) {
      out.append(" extends ").append(metadata.sourceName(model.parent));
    }
    List<String> interfaces = directInterfaces(model);
    for (int i = 0; i < interfaces.size(); i++) {
      out.append(i == 0 ? (model.isInterface() ? " extends " : " implements ") : ", ")
          .append(metadata.sourceName(interfaces.get(i)));
    }
    out.append(" {\n");
    if (model.isEnum()) { out.append(indent).append("  ;\n"); }
    for (JniClass.Field field : model.constants) {
      out.append(indent).append("  ").append(access(field.access)).append("static final ")
          .append(type(Type.getType(field.descriptor))).append(' ').append(field.name).append(" = ")
          .append(literal(field)).append(";\n");
    }
    if (!model.isInterface() && !model.isEnum() && !model.isRecord()) {
      out.append(indent).append("  protected ").append(model.simple()).append("() throws java.lang.Throwable { ")
          .append(constructors.get(model.name)).append(" }\n");
    }
    if (interfaceMethods.containsKey(model.name)) {
      // Keep only declarations needed by retained contracts. These must never
      // introduce extra native methods or JNI headers.
      for (JniClass.Method method : interfaceMethods.get(model.name)) {
        boolean concrete = model.isEnum() || model.isRecord();
        out.append(indent).append("  public ");
        if (!concrete) { out.append("abstract "); }
        out.append(type(Type.getReturnType(method.descriptor))).append(' ').append(method.name).append('(');
        Type[] arguments = Type.getArgumentTypes(method.descriptor);
        for (int i = 0; i < arguments.length; i++) {
          if (i > 0) { out.append(", "); }
          out.append(type(arguments[i])).append(" p").append(i);
        }
        out.append(concrete ? ") { throw new java.lang.AssertionError(); }\n" : ");\n");
      }
    }
    if (targets.contains(model.name)) {
      for (JniClass.Method method : model.natives) {
        out.append(indent).append("  ").append(access(method.access));
        if ((method.access & Opcodes.ACC_STATIC) != 0) { out.append("static "); }
        out.append("native ").append(type(Type.getReturnType(method.descriptor))).append(' ').append(method.name).append('(');
        Type[] arguments = Type.getArgumentTypes(method.descriptor);
        for (int i = 0; i < arguments.length; i++) {
          if (i > 0) { out.append(", "); }
          out.append(type(arguments[i])).append(" p").append(i);
        }
        out.append(");\n");
      }
      if (model.natives.isEmpty()) {
        String marker = "__nar_header";
        Set<String> fields = new HashSet<String>();
        for (JniClass.Field field : model.constants) { fields.add(field.name); }
        while (fields.contains(marker)) { marker += "_"; }
        out.append(indent).append("  @java.lang.annotation.Native ");
        if (model.isInterface()) { out.append("int ").append(marker).append(" = new java.lang.Object().hashCode()"); }
        else {
          if (model.isRecord()) { out.append("static "); }
          out.append("int ").append(marker);
        }
        out.append(";\n");
      }
    }
    for (JniClass child : declarations.values()) {
      if (model.name.equals(child.outer())) { emit(child, out, indent + "  "); }
    }
    out.append(indent).append("}\n");
  }

  private static String access(int flags) {
    if ((flags & Opcodes.ACC_PUBLIC) != 0) { return "public "; }
    if ((flags & Opcodes.ACC_PROTECTED) != 0) { return "protected "; }
    if ((flags & Opcodes.ACC_PRIVATE) != 0) { return "private "; }
    return "";
  }

  private static String literal(JniClass.Field field) {
    Object value = field.value;
    switch (field.descriptor.charAt(0)) {
      case 'Z': return ((Integer) value) == 0 ? "false" : "true";
      case 'C': return "(char) " + value;
      case 'J': return value + "L";
      case 'F': {
        float number = (Float) value;
        if (Float.isNaN(number)) { return "(0.0f / 0.0f)"; }
        if (Float.isInfinite(number)) { return number > 0 ? "(1.0f / 0.0f)" : "(-1.0f / 0.0f)"; }
        return Float.toHexString(number) + "f";
      }
      case 'D': {
        double number = (Double) value;
        if (Double.isNaN(number)) { return "(0.0d / 0.0d)"; }
        if (Double.isInfinite(number)) { return number > 0 ? "(1.0d / 0.0d)" : "(-1.0d / 0.0d)"; }
        return Double.toHexString(number) + "d";
      }
      default: return value.toString();
    }
  }

  static String header(String name) { return name.replace('/', '_').replace('$', '_') + ".h"; }

  private static String[] strings(Set<?> values) {
    List<String> result = new ArrayList<String>();
    if (values != null) { for (Object value : values) { if (value != null) { result.add(value.toString()); } } }
    return result.toArray(new String[result.size()]);
  }

  private static String path(List<File> entries) {
    StringBuilder result = new StringBuilder();
    for (File entry : entries) {
      if (result.length() != 0) { result.append(File.pathSeparator); }
      result.append(entry.getAbsolutePath());
    }
    return result.toString();
  }

  private static String run(List<String> command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream stream = process.getInputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = stream.read(buffer)) != -1) { output.write(buffer, 0, count); }
    }
    try {
      int status = process.waitFor();
      String diagnostics = new String(output.toByteArray(), StandardCharsets.UTF_8);
      if (status != 0) { throw new IOException(command.get(0) + " failed (exit " + status + "):\n" + diagnostics); }
      return diagnostics;
    } catch (InterruptedException ex) {
      process.destroy();
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while running " + command.get(0), ex);
    }
  }
}
