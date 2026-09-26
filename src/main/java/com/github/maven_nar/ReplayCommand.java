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
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

import org.apache.maven.plugin.MojoExecutionException;

/** A raw argument vector, kept separate from shell syntax until a replay script is written. */
public final class ReplayCommand {
  private static final String HEADER = "# nar-replay-v1\t";
  private final String directory;
  private final String[] arguments;

  public ReplayCommand(final File directory, final String[] arguments) {
    this(directory.getAbsolutePath(), arguments);
  }

  private ReplayCommand(final String directory, final String[] arguments) {
    this.directory = directory;
    this.arguments = arguments.clone();
  }

  public static boolean isRecord(final String line) {
    return line.startsWith(HEADER);
  }

  /** A comment followed by a readable command keeps logs usable as shell command logs. */
  public String encode() throws UnsupportedEncodingException {
    final StringBuilder result = new StringBuilder(HEADER).append(URLEncoder.encode(this.directory, "UTF-8"));
    for (final String argument : this.arguments) result.append('\t').append(URLEncoder.encode(argument, "UTF-8"));
    return result.toString();
  }

  public static ReplayCommand decode(final String line) throws MojoExecutionException {
    try {
      final String[] fields = line.substring(HEADER.length()).split("\t", -1);
      if (fields.length < 2) throw new IllegalArgumentException("Missing command arguments");
      final String[] args = new String[fields.length - 1];
      for (int i = 1; i < fields.length; i++) args[i - 1] = URLDecoder.decode(fields[i], "UTF-8");
      return new ReplayCommand(URLDecoder.decode(fields[0], "UTF-8"), args);
    } catch (final IllegalArgumentException | UnsupportedEncodingException ex) {
      throw new MojoExecutionException("Invalid structured replay command", ex);
    }
  }

  public String display() {
    return ("(cd " + quote(this.directory, false) + " && " + command(this.arguments, false) + ")")
        .replace("\r", "\\r").replace("\n", "\\n");
  }

  public void write(final Script script, final PrintWriter writer) throws MojoExecutionException {
    final boolean batch = "bat".equals(script.getScriptType());
    if (!batch && !"sh".equals(script.getScriptType()) && !"bash".equals(script.getScriptType())) {
      throw new MojoExecutionException("Unknown replay script type: " + script.getScriptType());
    }
    final String cwd = substitute(this.directory, script);
    final String[] args = new String[this.arguments.length];
    for (int i = 0; i < args.length; i++) args[i] = substitute(this.arguments[i], script);
    final String line = command(args, batch);
    if (batch) {
      writer.println("setlocal DisableDelayedExpansion");
      writer.println("pushd " + batchPath(cwd));
      writer.println("if errorlevel 1 exit /b 1");
      if (script.isEchoLines()) writer.println("echo " + echoBatch(line));
      writer.println(line);
      writer.println("set \"_nar_replay_status=%errorlevel%\"");
      writer.println("popd");
      writer.println("if not \"%_nar_replay_status%\"==\"0\" exit /b %_nar_replay_status%");
      writer.println("endlocal");
    } else {
      writer.println("(");
      writer.println("cd " + quote(cwd, false) + " || exit $?");
      if (script.isEchoLines()) writer.println("printf '%s\\n' " + quote(line, false));
      writer.println(line);
      writer.println(") || exit $?");
    }
  }

  private static String substitute(final String value, final Script script) {
    String result = value;
    if (script.getSubstitutions() != null) {
      for (final Substitution substitution : script.getSubstitutions()) result = substitution.substitute(result);
    }
    return result;
  }

  private static String command(final String[] args, final boolean batch) {
    final StringBuilder result = new StringBuilder();
    for (final String argument : args) {
      if (result.length() != 0) result.append(' ');
      result.append(batch && result.length() == 0 ? batchPath(argument) : quote(argument, batch));
    }
    return result.toString();
  }

  private static String quote(final String argument, final boolean batch) {
    if (!batch) return "'" + argument.replace("'", "'\"'\"'") + "'";
    // Windows programs parse backslashes before quotes and at the end of a quoted argument.
    final StringBuilder result = new StringBuilder("\"");
    int slashes = 0;
    for (int i = 0; i < argument.length(); i++) {
      final char c = argument.charAt(i);
      if (c == '\\') {
        slashes++;
      } else {
        for (int j = 0; j < slashes * (c == '"' ? 2 : 1); j++) result.append('\\');
        slashes = 0;
        if (c == '"') result.append('\\');
        result.append(c == '%' ? "%%" : Character.toString(c));
      }
    }
    for (int j = 0; j < slashes * 2; j++) result.append('\\');
    return escapeBatch(result.append('"').toString());
  }

  private static String batchPath(final String path) {
    return "\"" + path.replace("%", "%%") + "\"";
  }

  private static String escapeBatch(final String line) {
    // Keep cmd outside quote mode; the quoted argv reaches the Windows runtime unchanged.
    return line.replace("^", "^^").replace("\"", "^\"").replace("&", "^&").replace("|", "^|")
        .replace("<", "^<").replace(">", "^>").replace("(", "^(").replace(")", "^)");
  }

  private static String echoBatch(final String line) {
    return escapeBatch(line);
  }
}
