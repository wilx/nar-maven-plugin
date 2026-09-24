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

/** Substitutes retained generic contracts without resolving their signature types. */
final class JniSignature extends SignatureVisitor {
  final Map<String, List<Value>> bounds = new LinkedHashMap<String, List<Value>>();
  final List<Value> parents = new ArrayList<Value>();
  private final List<Value> parameters = new ArrayList<Value>();
  private Value result;
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

  Map<String, Type> bind(List<Type> arguments) {
    Map<String, Type> result = new LinkedHashMap<String, Type>();
    int index = 0;
    for (String variable : bounds.keySet()) { result.put(variable, arguments.get(index++)); }
    return result;
  }

  JniClass.Method method(JniClass.Method method, Map<String, Type> arguments) {
    Map<String, Type> scope = new HashMap<String, Type>(arguments);
    // A method type parameter shadows a class/interface parameter of the same name.
    for (String variable : bounds.keySet()) { scope.remove(variable); }
    Type[] args = new Type[parameters.size()];
    for (int i = 0; i < args.length; i++) { args[i] = parameters.get(i).erase(scope, bounds, new HashSet<String>()); }
    return new JniClass.Method(method.access, method.name,
        Type.getMethodDescriptor(result.erase(scope, bounds, new HashSet<String>()), args));
  }

  static final class Value extends SignatureVisitor {
    String name;
    private String variable;
    private Type primitive;
    private Value component;
    final List<Value> arguments = new ArrayList<Value>();

    Value() { super(Opcodes.ASM9); }

    @Override
    public void visitBaseType(char descriptor) { primitive = Type.getType(String.valueOf(descriptor)); }
    @Override
    public void visitTypeVariable(String name) { variable = name; }
    @Override
    public SignatureVisitor visitArrayType() { component = new Value(); return component; }
    @Override
    public void visitClassType(String name) { this.name = name; }
    @Override
    public void visitInnerClassType(String name) {
      this.name += "$" + name;
      arguments.clear(); // A member type has its own formal type parameters.
    }
    @Override
    public void visitTypeArgument() {
      Value value = add(arguments);
      value.name = "java/lang/Object";
    }
    @Override
    public SignatureVisitor visitTypeArgument(char wildcard) { return add(arguments); }

    Type erase(Map<String, Type> scope) {
      return erase(scope, java.util.Collections.<String, List<Value>>emptyMap(), new HashSet<String>());
    }

    private Type erase(Map<String, Type> scope, Map<String, List<Value>> bounds, Set<String> visiting) {
      if (primitive != null) { return primitive; }
      if (name != null) { return Type.getObjectType(name); }
      if (component != null) { return Type.getType("[" + component.erase(scope, bounds, visiting).getDescriptor()); }
      if (scope.containsKey(variable)) { return scope.get(variable); }
      List<Value> limits = bounds.get(variable);
      if (limits != null && !limits.isEmpty() && visiting.add(variable)) {
        Type type = limits.get(0).erase(scope, bounds, visiting);
        visiting.remove(variable);
        return type;
      }
      return Type.getObjectType("java/lang/Object");
    }
  }
}
