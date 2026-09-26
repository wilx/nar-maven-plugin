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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Names in one generated compilation unit, independent of class-file binary names. */
final class JniSourceNames {
  private final JniClassPath metadata;
  private final String root;
  interface Access {
    boolean visible(String name) throws IOException;
    Set<String> qualifiers(String member, boolean discover) throws IOException;
  }

  private Map<String, Set<String>> bindings;
  private Access access;
  private final Set<String> references = new HashSet<String>();
  private boolean collecting = true;
  private final Set<String> unnamedTypes = new HashSet<String>();
  private final Set<String> unqualified = new HashSet<String>();
  private final Set<String> qualifiedPrefixes = new HashSet<String>();
  private final Map<String, String> imports = new HashMap<String, String>();

  JniSourceNames(JniClassPath metadata, String root) {
    this.metadata = metadata;
    this.root = root;
  }

  JniSourceNames copy() {
    JniSourceNames result = new JniSourceNames(metadata, root);
    result.bindings = bindings;
    result.access = access;
    result.references.addAll(references);
    result.collecting = collecting;
    result.unnamedTypes.addAll(unnamedTypes);
    result.unqualified.addAll(unqualified);
    result.qualifiedPrefixes.addAll(qualifiedPrefixes);
    result.imports.putAll(imports);
    return result;
  }

  void use(JniSourceNames source) {
    bindings = source.bindings;
    access = source.access;
    references.clear(); references.addAll(source.references);
    collecting = source.collecting;
    unnamedTypes.clear(); unnamedTypes.addAll(source.unnamedTypes);
    unqualified.clear(); unqualified.addAll(source.unqualified);
    qualifiedPrefixes.clear(); qualifiedPrefixes.addAll(source.qualifiedPrefixes);
    imports.clear(); imports.putAll(source.imports);
  }

  void reserve(Set<String> types) throws IOException {
    for (String binary : types) {
      JniClass model = metadata.resolve(binary);
      while (model.outer() != null) { model = metadata.resolve(model.outer()); }
      if (model.packageName().isEmpty()) {
        if (!collecting && imports.containsKey(model.simple())) {
          throw new IOException("Cannot express default-package type " + binary + " beside import " + imports.get(model.simple()));
        }
        unqualified.add(model.simple()); unnamedTypes.add(binary);
      }
    }
  }

  void scope(Map<String, Set<String>> bindings, Access access) {
    this.bindings = bindings;
    this.access = access;
  }

  Set<String> references() { return new HashSet<String>(references); }

  private boolean binds(String simple, String binary) {
    Set<String> visible = bindings.get(simple);
    return visible != null && visible.size() == 1 && visible.contains(binary);
  }

  void finishCollecting() { collecting = false; }

  Set<String> imports() { return new TreeSet<String>(imports.values()); }

  String name(String binary) throws IOException { return name(binary, new HashSet<String>()); }

  private String name(String binary, Set<String> visiting) throws IOException {
    if (!visiting.add(binary)) { throw new IOException("Cyclic source qualification for " + binary); }
    try { return spelling(binary, visiting); }
    finally { visiting.remove(binary); }
  }

  private String spelling(String binary, Set<String> visiting) throws IOException {
    String qualified = metadata.sourceName(binary);
    int dot = qualified.indexOf('.');
    // Reserve unnamed-package roots before choosing imports for any declaration.
    reserve(Collections.singleton(binary));
    if (collecting) { return qualified; }
    if (dot < 0) {
      if (bindings.containsKey(qualified) && !binds(qualified, binary)) {
        throw new IOException("Cannot express type " + binary + ": lexical name " + qualified
            + " binds to " + bindings.get(qualified) + " in " + root);
      }
      if (!access.visible(binary)) { throw new IOException("Inaccessible source type " + binary + " in " + root); }
      return qualified;
    }
    List<JniClass> chain = new ArrayList<JniClass>();
    JniClass model = metadata.resolve(binary);
    boolean visible = true;
    while (true) {
      chain.add(model);
      // Inheritance may expose a member without exposing its declaring type.
      if (visible && binds(model.simple(), model.name)) {
        return model.simple() + qualified.substring(metadata.sourceName(model.name).length());
      }
      visible &= access.visible(model.name);
      if (model.outer() == null) { break; }
      model = metadata.resolve(model.outer());
    }
    String first = qualified.substring(0, dot);
    String packageName = metadata.resolve(root).packageName();
    if (visible && !bindings.containsKey(first) && !imports.containsKey(first)
        && (unnamedTypes.contains(binary) || !metadata.hasPackageType(packageName, first))) {
      qualifiedPrefixes.add(first);
      return qualified;
    }
    if (visible) {
      // Imports are resolved outside the lexical scope. Prefer the outermost
      // import, which also keeps protected members qualified by their owner.
      List<JniClass> importChain = new ArrayList<JniClass>(chain);
      Collections.reverse(importChain);
      boolean importable = true;
      for (JniClass candidate : importChain) {
        int flags = candidate.nesting == null ? candidate.access : candidate.nesting.access;
        importable &= (flags & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0
            || ((flags & org.objectweb.asm.Opcodes.ACC_PRIVATE) == 0 && packageName.equals(candidate.packageName()));
        if (!importable || candidate.packageName().isEmpty()) { continue; }
        String canonical = metadata.sourceName(candidate.name);
        String simple = candidate.simple();
        if (bindings.containsKey(simple) || unqualified.contains(simple) || qualifiedPrefixes.contains(simple)) { continue; }
        String existing = imports.get(simple);
        if (existing != null && !existing.equals(canonical)) { continue; }
        imports.put(simple, canonical);
        return simple + qualified.substring(canonical.length());
      }
    }
    // A source qualifier need not be the declaring class. Check the member's
    // identity through candidate subtypes; shadowing or ambiguous inheritance
    // must never silently change the native descriptor. Search cached metadata
    // first, and discover classpath hierarchy headers only if that is insufficient.
    for (boolean discover : new boolean[] {false, true}) {
      for (JniClass member : chain) {
        if (!access.visible(member.name)) { break; }
        if (member.outer() == null) { continue; }
        for (String qualifier : access.qualifiers(member.name, discover)) {
          JniSourceNames trial = copy();
          try {
            String prefix = trial.name(qualifier, visiting);
            trial.references.add(qualifier);
            use(trial);
            return prefix + "." + member.simple()
                + qualified.substring(metadata.sourceName(member.name).length());
          } catch (IOException unusable) {
            // Imports and references belong only to the successful spelling.
          }
        }
      }
    }
    throw new IOException("Cannot express type " + binary + " without inaccessible qualifiers or source-name shadowing in " + root);
  }

  Map<String, String> variables(JniSignature signature, String prefix) throws IOException {
    return variables(signature, prefix, Collections.<String>emptyList());
  }

  Map<String, String> variables(JniSignature signature, String prefix, List<String> additionalTypes) throws IOException {
    Set<String> reserved = new HashSet<String>(bindings.keySet());
    reserved.addAll(imports.keySet());
    reserved.addAll(signature.bounds.keySet());
    Set<String> types = new HashSet<String>(additionalTypes);
    for (List<JniSignature.Value> bounds : signature.bounds.values()) {
      for (JniSignature.Value bound : bounds) { bound.classNames(types); }
    }
    for (JniSignature.Value parent : signature.parents) { parent.classNames(types); }
    for (JniSignature.Value parameter : signature.parameters) { parameter.classNames(types); }
    if (signature.result != null) { signature.result.classNames(types); }
    for (String type : types) { Collections.addAll(reserved, metadata.sourceName(type).split("\\.")); }
    Map<String, String> result = new LinkedHashMap<String, String>();
    int index = 0;
    for (String variable : signature.bounds.keySet()) {
      String fresh;
      do { fresh = prefix + index++; } while (!reserved.add(fresh));
      result.put(variable, fresh);
    }
    return result;
  }
}
