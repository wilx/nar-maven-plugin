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
  private Set<String> shadowed;
  private boolean collecting = true;
  private final Set<String> unnamedTypes = new HashSet<String>();
  private final Set<String> unqualified = new HashSet<String>();
  private final Set<String> qualifiedPrefixes = new HashSet<String>();
  private final Map<String, String> imports = new HashMap<String, String>();

  JniSourceNames(JniClassPath metadata, String root) {
    this.metadata = metadata;
    this.root = root;
  }

  void scope(Set<String> shadowed) { this.shadowed = shadowed; }

  void finishCollecting() { collecting = false; }

  Set<String> imports() { return new TreeSet<String>(imports.values()); }

  String name(String binary) throws IOException {
    String qualified = metadata.sourceName(binary);
    int dot = qualified.indexOf('.');
    if (collecting) {
      // A default-package root (including Owner.Member references) has no
      // alternative qualified spelling. Reserve it across the whole unit before
      // introducing any imports, even imports used by synthetic constructors.
      JniClass model = metadata.resolve(binary);
      while (model.outer() != null) { model = metadata.resolve(model.outer()); }
      if (model.packageName().isEmpty()) { unqualified.add(model.simple()); unnamedTypes.add(binary); }
      return qualified;
    }
    if (dot < 0) { return qualified; }
    String first = qualified.substring(0, dot);
    String packageName = metadata.resolve(root).packageName();
    if (!shadowed.contains(first) && !imports.containsKey(first)
        && (unnamedTypes.contains(binary) || !metadata.hasPackageType(packageName, first))) {
      qualifiedPrefixes.add(first);
      return qualified;
    }
    // A type named java (or another package prefix) hides qualified names in
    // its scope. Imports are resolved outside that scope. Prefer importing the
    // outermost type so protected member types remain qualified by their owner.
    List<JniClass> chain = new ArrayList<JniClass>();
    JniClass model = metadata.resolve(binary);
    while (true) {
      chain.add(model);
      if (model.outer() == null) { break; }
      model = metadata.resolve(model.outer());
    }
    Collections.reverse(chain);
    for (JniClass candidate : chain) {
      String canonical = metadata.sourceName(candidate.name);
      String simple = candidate.simple();
      if (candidate.name.equals(root)) { return simple + qualified.substring(canonical.length()); }
      if (shadowed.contains(simple) || unqualified.contains(simple) || qualifiedPrefixes.contains(simple)) { continue; }
      String existing = imports.get(simple);
      if (existing != null && !existing.equals(canonical)) { continue; }
      imports.put(simple, canonical);
      return simple + qualified.substring(canonical.length());
    }
    throw new IOException("Cannot express type " + binary + " without source-name shadowing in " + root);
  }

  Map<String, String> variables(JniSignature signature, String prefix) throws IOException {
    Set<String> reserved = new HashSet<String>(shadowed);
    reserved.addAll(imports.keySet());
    reserved.addAll(signature.bounds.keySet());
    Set<String> types = new HashSet<String>();
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
