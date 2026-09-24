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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/** Substitutes retained generic contracts without loading their signature types. */
final class JniSignature extends SignatureVisitor {
  final Map<String, List<Value>> bounds = new LinkedHashMap<String, List<Value>>();
  final List<Value> parents = new ArrayList<Value>();
  final List<Value> parameters = new ArrayList<Value>();
  Value result;
  private String formal;

  private JniSignature() { super(Opcodes.ASM9); }

  static JniSignature read(String signature) {
    JniSignature result = new JniSignature();
    if (signature != null) { new SignatureReader(signature).accept(result); }
    return result;
  }

  @Override
  public void visitFormalTypeParameter(String name) {
    formal = name;
    bounds.put(name, new ArrayList<Value>());
  }
  @Override
  public SignatureVisitor visitClassBound() { return add(bounds.get(formal)); }
  @Override
  public SignatureVisitor visitInterfaceBound() { return add(bounds.get(formal)); }
  @Override
  public SignatureVisitor visitSuperclass() { return add(parents); }
  @Override
  public SignatureVisitor visitInterface() { return add(parents); }
  @Override
  public SignatureVisitor visitParameterType() { return add(parameters); }
  @Override
  public SignatureVisitor visitReturnType() { result = new Value(); return result; }
  @Override
  public SignatureVisitor visitExceptionType() { return new SignatureVisitor(Opcodes.ASM9) {}; }

  private static Value add(List<Value> values) {
    Value value = new Value();
    values.add(value);
    return value;
  }

  Map<String, Value> bind(List<Value> arguments) {
    Map<String, Value> result = new LinkedHashMap<String, Value>();
    int index = 0;
    for (String variable : bounds.keySet()) { result.put(variable, arguments.get(index++)); }
    return result;
  }

  Map<String, Value> variables() {
    Map<String, Value> result = new LinkedHashMap<String, Value>();
    for (String name : bounds.keySet()) {
      Value value = new Value();
      value.variable = name;
      value.erasure = value.substitute(java.util.Collections.<String, Value>emptyMap(), bounds,
          new HashSet<String>()).erase();
      result.put(name, value);
    }
    return result;
  }

  JniClass.Method method(JniClass.Method method, Map<String, Value> arguments) {
    Map<String, Value> scope = new HashMap<String, Value>(arguments);
    // A method type parameter shadows a class/interface parameter of the same name.
    for (String variable : bounds.keySet()) { scope.remove(variable); }
    Type[] args = new Type[parameters.size()];
    StringBuilder source = new StringBuilder("(");
    for (int i = 0; i < args.length; i++) {
      Value value = parameters.get(i).substitute(scope, bounds, new HashSet<String>());
      args[i] = value.erase();
      source.append(value.methodSignature());
    }
    Value returns = result.substitute(scope, bounds, new HashSet<String>());
    source.append(')').append(returns.methodSignature());
    return new JniClass.Method(method.access, method.name, Type.getMethodDescriptor(returns.erase(), args), source.toString());
  }

  static final class Value extends SignatureVisitor {
    String name;
    Value owner;
    private String variable;
    private Type erasure;
    private Value component;
    private char wildcard = '=';
    final List<Value> arguments = new ArrayList<Value>();

    Value() { super(Opcodes.ASM9); }
    static Value object(String name) { Value value = new Value(); value.name = name; return value; }

    @Override
    public void visitBaseType(char descriptor) { erasure = Type.getType(String.valueOf(descriptor)); }
    @Override
    public void visitTypeVariable(String name) { variable = name; }
    @Override
    public SignatureVisitor visitArrayType() { component = new Value(); return component; }
    @Override
    public void visitClassType(String name) { this.name = name; }
    @Override
    public void visitInnerClassType(String name) {
      Value enclosing = object(this.name);
      enclosing.owner = owner;
      enclosing.arguments.addAll(arguments);
      owner = enclosing;
      this.name += "$" + name;
      arguments.clear();
    }
    @Override
    public void visitTypeArgument() { Value value = add(arguments); value.wildcard = '*'; }
    @Override
    public SignatureVisitor visitTypeArgument(char wildcard) {
      Value value = add(arguments); value.wildcard = wildcard; return value;
    }

    Value substitute(Map<String, Value> scope) {
      return substitute(scope, java.util.Collections.<String, List<Value>>emptyMap(), new HashSet<String>());
    }

    private Value substitute(Map<String, Value> scope, Map<String, List<Value>> bounds, Set<String> visiting) {
      if (variable != null) {
        if (scope.containsKey(variable)) { return scope.get(variable).withWildcard(wildcard); }
        List<Value> limits = bounds.get(variable);
        if (limits != null && !limits.isEmpty() && visiting.add(variable)) {
          Value value = limits.get(0).substitute(scope, bounds, visiting);
          visiting.remove(variable);
          return value.withWildcard(wildcard);
        }
        return object("java/lang/Object").withWildcard(wildcard);
      }
      Value value = new Value();
      value.name = name; value.erasure = erasure; value.wildcard = wildcard;
      if (owner != null) { value.owner = owner.substitute(scope, bounds, visiting); }
      if (component != null) { value.component = component.substitute(scope, bounds, visiting); }
      for (Value argument : arguments) { value.arguments.add(argument.substitute(scope, bounds, visiting)); }
      return value;
    }

    private Value withWildcard(char wildcard) {
      Value value = new Value();
      value.name = name; value.variable = variable; value.erasure = erasure;
      value.component = component; value.owner = owner; value.arguments.addAll(arguments);
      value.wildcard = wildcard;
      return value;
    }

    Type erase() {
      if (erasure != null) { return erasure; }
      if (name != null) { return Type.getObjectType(name); }
      if (component != null) { return Type.getType("[" + component.erase().getDescriptor()); }
      return Type.getObjectType("java/lang/Object");
    }

    private String methodSignature() {
      // Supporting methods can erase parameterized types, but a declaration's
      // own type variables must remain variables (e.g. Supplier<T>.get(): T).
      if (variable != null) { return "T" + variable + ";"; }
      if (component != null) { return "[" + component.methodSignature(); }
      return erase().getDescriptor();
    }

    Value eraseArguments(Set<String> rawNames) {
      Value value = new Value();
      value.name = name; value.variable = variable; value.erasure = erasure; value.wildcard = wildcard;
      if (owner != null) { value.owner = owner.eraseArguments(rawNames); }
      if (component != null) { value.component = component.eraseArguments(rawNames); }
      if (!rawNames.contains(name)) {
        for (Value argument : arguments) { value.arguments.add(argument.eraseArguments(rawNames)); }
      }
      return value;
    }

    void classNames(Set<String> names) {
      if (name != null) { names.add(name); }
      if (owner != null) { owner.classNames(names); }
      if (component != null) { component.classNames(names); }
      for (Value argument : arguments) { argument.classNames(names); }
    }

    String source(JniClassPath metadata) throws IOException {
      if (wildcard == '*') { return "?"; }
      String prefix = wildcard == '+' ? "? extends " : wildcard == '-' ? "? super " : "";
      if (variable != null) { return prefix + variable; }
      if (component != null) { return prefix + component.source(metadata) + "[]"; }
      if (name == null) { return prefix + erase().getClassName(); }
      StringBuilder text = new StringBuilder(prefix);
      text.append(owner == null ? metadata.sourceName(name) : owner.source(metadata) + "." + metadata.resolve(name).simple());
      if (!arguments.isEmpty()) {
        text.append('<');
        for (int i = 0; i < arguments.size(); i++) {
          if (i != 0) { text.append(", "); }
          text.append(arguments.get(i).source(metadata));
        }
        text.append('>');
      }
      return text.toString();
    }

    @Override
    public String toString() {
      return wildcard + ":" + (variable != null ? "T" + variable : name != null ? name : erase().getDescriptor())
          + ":" + owner + arguments + (component == null ? "" : "[" + component);
    }
  }
}
