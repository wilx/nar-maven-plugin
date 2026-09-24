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
// There are no native source declarations in the consumer; all headers must
// therefore come from the dependency's compiled classes.
File headers = new File(basedir, 'consumer/target/nar/javah-include')
assert headers.list().toList().sort() == ['dependency_Container_Api.h', 'dependency_Constants.h', 'dependency_GenericApi.h'].sort()
String api = new File(headers, 'dependency_Container_Api.h').text
assert api.contains('Java_dependency_Container_00024Api_call')
assert api.contains('jthrowable, jintArray')
// Covariant return checking needs the dependency API's nested Marker interface.
assert api.contains('Java_dependency_Container_00024Api_self')
// The enum's get() implementation lives in its constant-specific class body.
// Generating its header requires reconstructing the inherited Supplier<String> contract.
String generic = new File(headers, 'dependency_GenericApi.h').text
assert generic.contains('Java_dependency_GenericApi_call')
// The synthesized contract implementation must remain non-native.
assert !generic.contains('Java_dependency_GenericApi_get')
// Ordered<GenericApi> must use Enum's inherited comparison, without a new native method.
assert !generic.contains('Java_dependency_GenericApi_compareTo')
String constants = new File(headers, 'dependency_Constants.h').text
assert constants.contains('1234567890123')
assert !constants.contains('__nar_header')
assert new File(basedir, 'consumer/target/nar/javac-headers/javac.args').isFile()
assert !new File(basedir, 'consumer/src/main/java').exists()
return true
