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
package com.github.maven_nar.cpptasks.gcc;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.tools.ant.Project;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.github.maven_nar.NarCompileMojo;
import com.github.maven_nar.NarPreparePackageMojo;
import com.github.maven_nar.NarUtil;
import com.github.maven_nar.Script;
import com.github.maven_nar.Substitution;
import com.github.maven_nar.cpptasks.CCTask;
import com.github.maven_nar.cpptasks.ProcessorParam;
import com.github.maven_nar.cpptasks.compiler.CommandLineLinkerConfiguration;

import static org.junit.Assert.*;

/**
 * Execute the generated scripts, including the persisted command-log round
 * trip.
 */
public class TestLinkerReplay {
  public static class PathProbe {
    public static void main(final String[] args) throws Exception {
      final List<String> values = Arrays.asList(args);
      for (int i = 0; i < args.length; i++) {
        final String value = args[i];
        if (value.startsWith("-L") || value.startsWith("-F")) {
          final String directory = value.length() == 2 ? args[++i] : value.substring(2);
          if (!new File(directory).isDirectory())
            throw new IllegalArgumentException("Missing search directory: " + directory);
        } else if (value.startsWith("--input=")) {
          if (!new File(value.substring(8)).isFile())
            throw new IllegalArgumentException("Missing explicit path: " + value);
        }
      }
      if (!new File(args[args.length - 2]).isFile())
        throw new IllegalArgumentException("Missing input: " + args[args.length - 2]);
      if (!new File(args[args.length - 1]).isFile())
        throw new IllegalArgumentException("Missing relative input");
      Files.write(new File(args[values.indexOf("-o") + 1]).toPath(), "linked".getBytes(StandardCharsets.UTF_8));
      for (final String arg : args) {
        if (arg.startsWith("-Map="))
          Files.write(new File(arg.substring(5)).toPath(), "map".getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  public static class PrefixPathProbe {
    public static void main(final String[] args) throws Exception {
      for (final String arg : args) {
        if ("END_ARGUMENTS".equals(arg))
          break;
        if ("-L".equals(arg) || "-F".equals(arg))
          continue;
        final String path = arg.startsWith("-L") || arg.startsWith("-F") ? arg.substring(2) : arg;
        if (!new File(path).exists())
          throw new IllegalArgumentException("Missing replay path: " + path);
      }
      ReplayArgumentProbe.main(args);
    }
  }

  public static class Probe {
    public static void main(final String[] args) throws Exception {
      final int index = Arrays.asList(args).indexOf("-o");
      if (!"object name.o".equals(args[args.length - 1]))
        throw new IllegalArgumentException("Quoted input argument");
      Files.write(new File(args[index + 1]).toPath(), args[0].getBytes(StandardCharsets.UTF_8));
      if (!"MISSING".equals(args[0])) {
        for (final String arg : args) {
          if (arg.startsWith("-Map="))
            Files.write(new File(arg.substring(5)).toPath(), args[0].getBytes(StandardCharsets.UTF_8));
        }
      }
      if ("FAIL".equals(args[0]))
        System.exit(7);
    }
  }

  private static class RecordingMojo extends NarCompileMojo {
    List<String[]> history() {
      return linkCommands;
    }
  }

  private static String encodedArguments(final List<String> values) throws Exception {
    return ReplayArgumentProbe.encodedArguments(values);
  }

  private static Substitution prefixSubstitution(final String option, final String pattern, final String prefix,
      final boolean named) {
    return substitution("regex", "^" + option + (named ? "(?<file>" : "(") + pattern + ")$",
        java.util.regex.Matcher.quoteReplacement(option + prefix) + (named ? "${file}" : "$1"));
  }

  private static String read(final File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static Substitution substitution(final String type, final String match, final String replacement) {
    final Substitution sub = new Substitution();
    sub.setType(type);
    sub.setReplace(match);
    sub.setReplaceWith(replacement);
    return sub;
  }

  private File directory;

  private File argumentScript(final List<String[]> history, final String shell, final File working,
      final List<Substitution> rules)
      throws Exception {
    // Exercise the payload rules without also rewriting the JVM or the test's
    // working directory.
    final String[] paths = {
        stageArgumentProbe().getAbsolutePath(), history.get(0)[0], working.getAbsolutePath()
    };
    final List<Substitution> substitutions = new ArrayList<>();
    for (int i = 0; i < paths.length; i++) {
      substitutions.add(substitution("string", paths[i], "REPLAY_LAUNCH_PATH_" + i));
    }
    substitutions.addAll(rules);
    for (int i = paths.length - 1; i >= 0; i--) {
      substitutions.add(substitution("string", "REPLAY_LAUNCH_PATH_" + i, paths[i]));
    }
    return scriptWithSubstitutions(history, shell, substitutions);
  }

  private void assertNoTemporaryMaps(final File directory) {
    for (final String name : directory.list()) {
      assertFalse("Temporary map remains: " + name, name.startsWith("nar-map-") && name.endsWith(".tmp"));
    }
  }

  private void assertReplaySucceeds(final File replay, final String shell, final File invocation) throws Exception {
    final String[] command = "bat".equals(shell) ? new String[] {
        "cmd.exe", "/d", "/c", replay.getAbsolutePath()
    } : new String[] {
        "/bin/" + shell, replay.getAbsolutePath()
    };
    final File log = new File(directory, "run.log");
    final int status = new ProcessBuilder(command).directory(invocation).redirectErrorStream(true).redirectOutput(log)
        .start().waitFor();
    assertEquals(read(log), 0, status);
  }

  private void checkCapturedPathSuffixes(final String shell) throws Exception {
    for (final boolean named : new boolean[] {
        false, true
    }) {
      for (final boolean map : new boolean[] {
          false, true
      }) {
        for (final boolean dry : new boolean[] {
            false, true
        }) {
          final String id = shell + named + map + dry;
          final File original = new File(directory, "suffix original " + id);
          final File working = new File(original, "nested/output");
          assertTrue(working.mkdirs());
          final File relocated = new File(directory, "suffix relocated " + id + " %!&");
          final String sep = File.separator;
          final String delimiter = File.pathSeparator;
          final String base = relocated + sep;
          final String capture = named ? "${path}" : "$1";
          final String other = named ? "${other}" : "$2";
          final List<String> payload = new ArrayList<>();
          final List<String> expected = new ArrayList<>();
          final List<Substitution> rules = new ArrayList<>();
          rules.add(substitution("absolutePath", original.toString(), ""));
          for (final String form : new String[] {
              "separate", "attached", "later", "adjacent", "literal", "unrelated", "file", "punctuation"
          }) {
            final String option = "attached".equals(form) || "unrelated".equals(form) ? "-Wl,-rpath,"
                : "file".equals(form) ? "-L" : "punctuation".equals(form) ? "-F" : "";
            if (option.isEmpty()) {
              payload.addAll(Arrays.asList("-Xlinker", "-rpath", "-Xlinker"));
              expected.addAll(Arrays.asList("-Xlinker", "-rpath", "-Xlinker"));
            }
            final String component = "punctuation".equals(form) ? "leaf,part" + delimiter + "name" : form;
            final String tail = "literal".equals(form) ? delimiter + "literal"
                : "unrelated".equals(form) ? ",--as-needed" : "";
            payload.add(option + new File(original, "lib" + sep + component) + tail);
            String pattern = "^" + java.util.regex.Pattern.quote(option + "lib" + sep) + (named ? "(?<path>" : "(")
                + java.util.regex.Pattern.quote(component) + ")";
            String replacement = option + capture + delimiter + capture
                + java.util.regex.Matcher.quoteReplacement(sep + "fallback");
            String value = option + base + component + delimiter + base + component + sep + "fallback";
            if ("literal".equals(form) || "unrelated".equals(form)) {
              final String separator = "literal".equals(form) ? delimiter : ",";
              final String literal = "literal".equals(form) ? "literal" : "--as-needed";
              pattern += java.util.regex.Pattern.quote(separator) + (named ? "(?<other>" : "(") + literal + ")";
              // A capture outside the tracked path must remain literal, even between copies.
              replacement = option + capture + separator + other + separator + capture
                  + java.util.regex.Matcher.quoteReplacement(sep + "fallback");
              value = option + base + component + separator + literal + separator + base + component + sep + "fallback";
            } else if ("adjacent".equals(form)) {
              pattern = "^" + java.util.regex.Pattern.quote("lib" + sep)
                  + (named ? "(?<path>adja)(?<other>cent)" : "(adja)(cent)");
              replacement = java.util.regex.Matcher.quoteReplacement("objects" + sep) + capture + other;
              value = base + "objects" + sep + component;
            } else if ("file".equals(form) || "punctuation".equals(form)) {
              replacement = java.util.regex.Matcher.quoteReplacement(option + "objects" + sep) + capture
                  + ("file".equals(form) ? capture : "");
              value = option + base + "objects" + sep + component + ("file".equals(form) ? component : "");
            }
            rules.add(substitution("regex", pattern + "$", replacement));
            if ("later".equals(form)) {
              rules.add(substitution("regex",
                  "^" + java.util.regex.Pattern.quote("later" + delimiter + "later" + sep)
                      + (named ? "(?<path>fallback)" : "(fallback)") + "$",
                  capture + delimiter + capture + java.util.regex.Matcher.quoteReplacement(sep + "next")));
              value = base + "fallback" + delimiter + base + "fallback" + sep + "next";
            }
            expected.add(value);
          }
          final List<String[]> history = recordArguments(working, payload, dry, map);
          assertEquals(!dry, new File(working, "result").isFile());
          Files.deleteIfExists(new File(working, "result").toPath());
          Files.deleteIfExists(new File(working, "result.map").toPath());
          final File replay = scriptWithSubstitutions(history, shell, rules);
          Files.move(original.toPath(), relocated.toPath());
          assertReplaySucceeds(replay, shell, relocated);
          final File output = new File(relocated, "nested/output/result");
          assertEquals(encodedArguments(expected), read(output));
          assertEquals(map, new File(output.getParentFile(), "result.map").isFile());
          assertNoTemporaryMaps(output.getParentFile());
        }
      }
    }
  }

  private void checkCapturePrefixedExecutable(final String shell, final boolean absolute, final boolean named)
      throws Exception {
    final boolean batch = "bat".equals(shell);
    final String id = shell + absolute + named;
    final File original = new File(directory, "prefix executable " + id);
    final File working = new File(original, "nested/output");
    assertTrue(working.mkdirs());
    final File executable = new File(original, batch ? "tool.exe" : "tool");
    Files.copy((batch ? new File(System.getenv("ComSpec")) : new File("/bin/true")).toPath(), executable.toPath());
    if (!batch)
      assertTrue(executable.setExecutable(true));
    final String[] args = batch ? new String[] {
        executable.toString(), "/d", "/c", "exit", "0"
    } : new String[] {
        executable.toString()
    };
    final com.github.maven_nar.ReplayCommandList history = new com.github.maven_nar.ReplayCommandList();
    history.add(args, new com.github.maven_nar.ReplayCommand(working, args));
    final File tools = absolute ? new File(directory, "absolute tools " + id + " %!&") : new File(original, "tools");
    assertTrue(tools.mkdir());
    Files.move(executable.toPath(), new File(tools, executable.getName()).toPath());
    final File replay = scriptWithSubstitutions(history, shell, Arrays.asList(
        substitution("absolutePath", original.toString(), ""),
        prefixSubstitution("", "tool(?:\\.exe)?", (absolute ? tools.toString() : "tools") + File.separator, named)));
    final File relocated = new File(directory, "prefix executable relocated " + id + " %!&");
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
  }

  private void checkCapturePrefixedPaths(final String shell) throws Exception {
    for (final boolean absolute : new boolean[] {
        false, true
    }) {
      for (final boolean named : new boolean[] {
          false, true
      }) {
        for (final boolean map : new boolean[] {
            false, true
        }) {
          for (final boolean dry : new boolean[] {
              false, true
          }) {
            checkCapturePrefixedPaths(shell, absolute, named, map, dry);
          }
        }
        checkCapturePrefixedExecutable(shell, absolute, named);
      }
    }
  }

  private void checkCapturePrefixedPaths(final String shell, final boolean absolute, final boolean named,
      final boolean map, final boolean dry)
      throws Exception {
    final String id = shell + absolute + named + map + dry;
    final File original = new File(directory, "prefix original " + id);
    final File working = new File(original, "nested/output");
    assertTrue(working.mkdirs());
    final File input = new File(original, "main.o");
    final File libraries = new File(original, "libraries");
    assertTrue(libraries.mkdir());
    Files.write(input.toPath(), "object".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(working, "local.o").toPath(), "local".getBytes(StandardCharsets.UTF_8));
    final List<String> payload = Arrays.asList(input.toString(), "-L" + libraries, "-L", libraries.toString(),
        "-F" + libraries, "-F", libraries.toString(), "local.o");
    final List<String> pre = new ArrayList<>(
        Arrays.asList("-cp", System.getProperty("java.class.path"), PrefixPathProbe.class.getName()));
    pre.addAll(payload);
    pre.add("END_ARGUMENTS");
    final List<String[]> history = new RecordingMojo().history();
    recordProbe(working, "result", pre, new String[0], dry, map, history);
    assertEquals(!dry, new File(working, "result").isFile());
    Files.deleteIfExists(new File(working, "result").toPath());
    Files.deleteIfExists(new File(working, "result.map").toPath());

    final String special = "bat".equals(shell) ? " %PATH%! & ^" : " %! & ' \" $";
    final File relocated = new File(directory, "prefix relocated " + id + special);
    final File destination = absolute ? new File(directory, "absolute objects " + id + special)
        : new File(original, "objects");
    assertTrue(destination.mkdir());
    Files.move(input.toPath(), new File(destination, "main.o").toPath());
    Files.move(libraries.toPath(), new File(destination, "libraries").toPath());
    final String prefix = (absolute ? destination.toString() : "objects") + File.separator;
    final List<Substitution> rules = new ArrayList<>();
    rules.add(substitution("absolutePath", original.toString(), ""));
    rules.add(prefixSubstitution("", "main\\.o|libraries", prefix, named));
    rules.add(prefixSubstitution("-L", "libraries", prefix, named));
    rules.add(prefixSubstitution("-F", "libraries", prefix, named));
    final File replay = scriptWithSubstitutions(history, shell, rules);
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
    final File expected = absolute ? destination : new File(relocated, "objects");
    final String expectedLibraries = new File(expected, "libraries").toString();
    final File output = new File(relocated, "nested/output/result");
    assertEquals(encodedArguments(Arrays.asList(new File(expected, "main.o").toString(), "-L" + expectedLibraries, "-L",
        expectedLibraries, "-F" + expectedLibraries, "-F", expectedLibraries, "local.o")), read(output));
    assertEquals(map, new File(output.getParentFile(), "result.map").isFile());
    if (map)
      assertEquals("map", read(new File(output.getParentFile(), "result.map")));
    assertFalse(new File(relocated, "result").exists());
    assertNoTemporaryMaps(output.getParentFile());
  }

  private void checkChangedPathStructure(final String shell) throws Exception {
    for (final String shape : new String[] {
        "duplicate", "wrapped", "prefixed", "absolute", "selected", "reordered", "adjacent", "adjacentReordered",
        "concatenated", "unwrapped", "punctuation"
    }) {
      for (final boolean named : new boolean[] {
          false, true
      }) {
        for (final boolean map : new boolean[] {
            false, true
        }) {
          for (final boolean dry : new boolean[] {
              false, true
          }) {
            checkChangedPathStructure(shell, shape, named, map, dry);
          }
        }
      }
    }
  }

  private void checkChangedPathStructure(final String shell, final String shape, final boolean named, final boolean map,
      final boolean dry)
      throws Exception {
    final String id = shell + shape + named + map + dry;
    final File original = new File(directory, "structure original " + id);
    final File working = new File(original, "nested/output");
    assertTrue(working.mkdirs());
    final String special = "bat".equals(shell) ? " %PATH%! & ^" : " %! & ' \" $";
    final File relocated = new File(directory, "structure relocated " + id + special);
    final String sep = File.separator;
    final String delimiter = File.pathSeparator;
    final String component = "punctuation".equals(shape) ? "lib, %!&" + ("bat".equals(shell) ? "" : " ' \" :data")
        : "lib";
    final List<String[]> history = recordArguments(working, Arrays.asList(new File(original, "lib").toString()), dry,
        map);
    assertEquals(!dry, new File(working, "result").isFile());
    Files.deleteIfExists(new File(working, "result").toPath());
    Files.deleteIfExists(new File(working, "result.map").toPath());
    final List<Substitution> rules = new ArrayList<>();
    rules.add(substitution("absolutePath", original.toString(), ""));
    // Ant's readable command display cannot record both quote styles in one
    // argument.
    // Introduce the punctuation through a rule, before it is copied by the next
    // rule.
    if (!"lib".equals(component)) {
      rules.add(substitution("regex", "^lib$", java.util.regex.Matcher.quoteReplacement(component)));
    }
    final String capture = named ? "${path}" : "$1";
    final String pattern = "^" + (named ? "(?<path>" : "(") + java.util.regex.Pattern.quote(component) + ")$";
    final String base = relocated.toString() + sep;
    final String absolute = new File(directory, "absolute structure " + id + special).toString() + sep;
    String replacement;
    String expected;
    if ("wrapped".equals(shape) || "unwrapped".equals(shape)) {
      replacement = "-Wl,-rpath," + capture + java.util.regex.Matcher.quoteReplacement(sep + "plugins");
      expected = "-Wl,-rpath," + base + component + sep + "plugins";
    } else if ("prefixed".equals(shape) || "punctuation".equals(shape)) {
      replacement = "-Wl,-rpath," + java.util.regex.Matcher.quoteReplacement("objects" + sep) + capture + delimiter
          + java.util.regex.Matcher.quoteReplacement("other" + sep) + capture;
      expected = "-Wl,-rpath," + base + "objects" + sep + component + delimiter + base + "other" + sep + component;
    } else if ("absolute".equals(shape)) {
      replacement = java.util.regex.Matcher.quoteReplacement(absolute) + capture + delimiter + capture;
      expected = absolute + component + delimiter + base + component;
    } else {
      replacement = capture + delimiter + capture + java.util.regex.Matcher.quoteReplacement(sep + "plugins");
      expected = base + component + delimiter + base + component + sep + "plugins";
    }
    if (shape.startsWith("adjacent")) {
      final boolean reordered = "adjacentReordered".equals(shape);
      final String fragments = reordered ? (named ? "${tail}${head}" : "$2$1") : (named ? "${head}${tail}" : "$1$2");
      rules.add(substitution("regex", named ? "^(?<head>l)(?<tail>ib)$" : "^(l)(ib)$",
          java.util.regex.Matcher.quoteReplacement("objects" + sep) + fragments));
      expected = base + "objects" + sep + (reordered ? "ibl" : component);
    } else if ("concatenated".equals(shape)) {
      rules.add(substitution("regex", pattern,
          java.util.regex.Matcher.quoteReplacement("objects" + sep) + capture + capture));
      expected = base + "objects" + sep + component + component;
    } else
      rules.add(substitution("regex", pattern, replacement));
    if ("selected".equals(shape) || "reordered".equals(shape)) {
      rules.add(substitution("regex", "^(.*)" + java.util.regex.Pattern.quote(delimiter) + "(.*)$",
          "selected".equals(shape) ? "$2" : "$2" + delimiter + "$1"));
      if ("selected".equals(shape)) {
        rules.add(substitution("regex", "^(.*" + java.util.regex.Pattern.quote(sep + "plugins") + ")$",
            java.util.regex.Matcher.quoteReplacement("objects" + sep) + "$1"));
        expected = base + "objects" + sep + component + sep + "plugins";
      } else
        expected = base + component + sep + "plugins" + delimiter + base + component;
    }
    if ("unwrapped".equals(shape)) {
      rules.add(substitution("regex", "^-Wl,-rpath,(.*)$", "$1"));
      rules.add(substitution("regex", "^(lib.*)$", java.util.regex.Matcher.quoteReplacement("objects" + sep) + "$1"));
      expected = base + "objects" + sep + component + sep + "plugins";
    }
    final File replay = scriptWithSubstitutions(history, shell, rules);
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
    final File output = new File(relocated, "nested/output/result");
    assertEquals(shape, encodedArguments(Arrays.asList(expected)), read(output));
    assertEquals(map, new File(output.getParentFile(), "result.map").isFile());
    if (map)
      assertEquals("map", read(new File(output.getParentFile(), "result.map")));
    assertFalse(new File(relocated, "result").exists());
    assertNoTemporaryMaps(output.getParentFile());
  }

  private void checkLiteralSearchEntries(final String shell) throws Exception {
    for (final boolean named : new boolean[] {
        false, true
    }) {
      for (final boolean map : new boolean[] {
          false, true
      }) {
        for (final boolean dry : new boolean[] {
            false, true
        }) {
          final String id = shell + named + map + dry;
          final File original = new File(directory, "literal original " + id);
          final File working = new File(original, "nested/output");
          assertTrue(working.mkdirs());
          final File relocated = new File(directory, "literal relocated " + id + " %!&");
          final String sep = File.separator;
          final String delimiter = File.pathSeparator;
          final String fixed = new File(directory, "fixed search %!&").toString();
          final List<String> payload = new ArrayList<>();
          final List<String> expected = new ArrayList<>();
          final List<Substitution> rules = new ArrayList<>();
          rules.add(substitution("absolutePath", original.toString(), ""));
          rules.add(substitution("regex", "^NAR_RPATH$", "-rpath"));
          rules.add(substitution("regex", "^-rpath-link$", ""));
          int number = 0;
          for (final String form : new String[] {
              "direct", "forwarded", "attached", "bundled", "introduced", "assignment", "forwardedAssignment",
              "flagIntroduced", "flagRemoved", "file", "library"
          }) {
            final boolean ordinaryPath = "file".equals(form) || "library".equals(form) || "flagRemoved".equals(form);
            for (int variant = 0; variant < (ordinaryPath ? 1 : 5); variant++) {
              // A comma is literal in a separate path-list operand; -Wl uses it to split
              // linker arguments.
              if (variant == 4 && ("attached".equals(form) || "bundled".equals(form) || "introduced".equals(form)
                  || "forwardedAssignment".equals(form)))
                continue;
              final String entries = fixed + delimiter + (variant == 1 ? "relative fallback" + delimiter : "");
              final String directoryPrefix = variant == 2 ? "objects" + sep
                  : variant == 3 ? "Q:\\absolute objects\\" : variant == 4 ? "objects,cache" + sep : "";
              final String component = "lib" + number++;
              final String option = "attached".equals(form) ? "-Wl,-rpath,"
                  : "bundled".equals(form) ? "-Wl,--as-needed,-rpath,"
                      : "forwardedAssignment".equals(form) ? "-Wl,--rpath="
                          : "assignment".equals(form) ? "--rpath=" : "library".equals(form) ? "-L" : "";
              final String literal = ordinaryPath ? ("bat".equals(shell) ? "objects,cache;" : "objects:cache;") + sep
                  : entries + directoryPrefix;
              final String[] leading = "direct".equals(form) ? new String[] {
                  "-rpath"
              } : "forwarded".equals(form) ? new String[] {
                  "-Xlinker", "-rpath", "-Xlinker"
              } : "flagIntroduced".equals(form) ? new String[] {
                  "NAR_RPATH"
              } : "flagRemoved".equals(form) ? new String[] {
                  "-rpath-link"
              } : new String[0];
              payload.addAll(Arrays.asList(leading));
              if ("flagIntroduced".equals(form))
                expected.add("-rpath");
              else if (!"flagRemoved".equals(form))
                expected.addAll(Arrays.asList(leading));
              payload.add(option + new File(original, component));
              final String outputOption = "introduced".equals(form) ? "-Wl,-rpath," : option;
              final String capture = named ? "${path}" : "$1";
              rules.add(substitution("regex",
                  "^" + java.util.regex.Pattern.quote(option) + (named ? "(?<path>" : "(") + component + ")$",
                  java.util.regex.Matcher.quoteReplacement(outputOption + literal) + capture
                      + java.util.regex.Matcher.quoteReplacement(sep + "plugins")));
              expected.add(outputOption + (ordinaryPath ? "" : entries) + (variant == 3 ? "" : relocated + sep)
                  + (ordinaryPath ? literal : directoryPrefix) + component + sep + "plugins");
            }
          }
          final List<String[]> history = recordArguments(working, payload, dry, map);
          assertEquals(!dry, new File(working, "result").isFile());
          Files.deleteIfExists(new File(working, "result").toPath());
          Files.deleteIfExists(new File(working, "result.map").toPath());
          final File replay = scriptWithSubstitutions(history, shell, rules);
          Files.move(original.toPath(), relocated.toPath());
          assertReplaySucceeds(replay, shell, relocated);
          final File output = new File(relocated, "nested/output/result");
          assertEquals(encodedArguments(expected), read(output));
          assertEquals(map, new File(output.getParentFile(), "result.map").isFile());
          if (map)
            assertEquals("map", read(new File(output.getParentFile(), "result.map")));
          assertFalse(new File(relocated, "result").exists());
          assertNoTemporaryMaps(output.getParentFile());
        }
      }
    }
  }

  private void checkPathSubstitutions(final String shell) throws Exception {
    for (final String order : new String[] {
        "direct", "string", "regex", "later", "captures"
    }) {
      for (final boolean map : new boolean[] {
          false, true
      }) {
        for (final boolean dry : new boolean[] {
            false, true
        }) {
          checkPathSubstitutions(shell, order, map, dry);
        }
      }
    }
  }

  private void checkPathSubstitutions(final String shell, final String order, final boolean map, final boolean dry)
      throws Exception {
    final String id = shell + order + map + dry;
    final File original = new File(directory, "original " + id);
    final File working = new File(original, "nested/output");
    assertTrue(working.mkdirs());
    final String special = "bat".equals(shell) ? " %PATH%! & ^" : " %! & ' \" $";
    final File relocated = new File(directory, "relocated " + id + special);
    final String firstName = "objects/first %!&.map".replace('/', File.separatorChar);
    final String secondName = "vendor/second.map".replace('/', File.separatorChar);
    final File first = new File(original, firstName);
    final File second = new File(original, secondName);
    assertTrue(first.getParentFile().mkdirs());
    assertTrue(second.getParentFile().mkdirs());
    Files.write(first.toPath(), "first".getBytes(StandardCharsets.UTF_8));
    Files.write(second.toPath(), "second".getBytes(StandardCharsets.UTF_8));
    final String unchanged = new File(directory, "unchanged.map").getAbsolutePath();
    final List<String> payload = Arrays.asList("-Wl,--version-script=" + first + ",--version-script=" + second,
        "-Wl,-rpath," + first.getParent() + ":" + second.getParent() + File.separator,
        "--repeat=" + first + "," + first, "--mixed=" + unchanged + "," + second + ",local.map",
        first + File.pathSeparator + second);
    final List<String[]> history = recordArguments(working, payload, dry, map);
    assertEquals(!dry, new File(working, "result").isFile());
    Files.deleteIfExists(new File(working, "result").toPath());
    Files.deleteIfExists(new File(working, "result.map").toPath());

    final List<Substitution> rules = new ArrayList<>();
    File root = original;
    if ("string".equals(order) || "regex".equals(order)) {
      root = new File(directory, "intermediate " + id);
      if ("regex".equals(order)) {
        // Captures and anchors operate on the whole argument before explicit paths are
        // recognized.
        rules.add(substitution("regex", "^(?<prefix>.*)" + java.util.regex.Pattern.quote(original.toString()) + "(.*)$",
            "${prefix}" + java.util.regex.Matcher.quoteReplacement(root.toString()) + "$2"));
        // Replace any earlier occurrences too (the greedy prefix above selects the last
        // one).
        rules.add(substitution("regex", java.util.regex.Pattern.quote(original.toString()),
            java.util.regex.Matcher.quoteReplacement(root.toString())));
      } else
        rules.add(substitution("string", original.toString(), root.toString()));
    }
    // Different rules recognize separate roots; the same root also occurs more than
    // once in an argument.
    rules.add(substitution("absolutePath", new File(root, "vendor").toString(), "vendor" + File.separator));
    rules.add(substitution("absolutePath", root.toString(), ""));
    String expectedFirst = new File(relocated, firstName).toString();
    if ("later".equals(order)) {
      expectedFirst = new File(directory, "absolute replacement %!&.map").toString();
      rules.add(substitution("string", firstName, expectedFirst));
      rules.add(substitution("string", "--version-script=", "--script="));
      rules.add(substitution("regex", "(second)(\\.map)", "$1-edited$2"));
    }
    if ("captures".equals(order)) {
      rules.add(substitution("regex", "^(-Wl,--version-script=)([^,]*)(,--version-script=)(.*)$", "$1$4$3$2"));
      rules.add(substitution("regex", "^--repeat=(.*),(.*)$", "--repeat=$2,$1,$1"));
      rules.add(substitution("regex", "^--mixed=(?<fixed>[^,]*),(?<moving>[^,]*),(?<local>.*)$",
          "--mixed=${fixed},${moving},${moving},${local}"));
    }
    final File replay = scriptWithSubstitutions(history, shell, rules);
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
    final String expectedSecond = new File(relocated,
        "later".equals(order) ? secondName.replace("second.map", "second-edited.map") : secondName).toString();
    final String flag = "later".equals(order) ? "--script=" : "--version-script=";
    final List<String> expected = Arrays.asList("-Wl," + flag + expectedFirst + "," + flag + expectedSecond,
        "-Wl,-rpath," + new File(relocated, "objects") + ":" + new File(relocated, "vendor") + File.separator,
        "--repeat=" + expectedFirst + "," + expectedFirst, "--mixed=" + unchanged + "," + expectedSecond + ",local.map",
        expectedFirst + File.pathSeparator + expectedSecond);
    if ("captures".equals(order)) {
      expected.set(0, "-Wl," + flag + expectedSecond + "," + flag + expectedFirst);
      expected.set(2, "--repeat=" + expectedFirst + "," + expectedFirst + "," + expectedFirst);
      expected.set(3, "--mixed=" + unchanged + "," + expectedSecond + "," + expectedSecond + ",local.map");
    }
    final File output = new File(relocated, "nested/output/result");
    assertEquals(encodedArguments(expected), read(output));
    assertEquals(map, new File(output.getParentFile(), "result.map").isFile());
    if (map)
      assertEquals("map", read(new File(output.getParentFile(), "result.map")));
    assertFalse(new File(relocated, "result").exists());
    assertNoTemporaryMaps(output.getParentFile());
  }

  private void checkRelocatedPaths(final String shell, final String type, final boolean map, final boolean dry)
      throws Exception {
    final String id = shell + type + map + dry;
    final File original = new File(directory, "original " + id);
    final File working = new File(original, "target/bin");
    final File objects = new File(original,
        "target/object dir %PATH%! & " + ("bat".equals(shell) ? "' ^" : map ? "' $" : "\" $"));
    assertTrue(working.mkdirs());
    assertTrue(objects.mkdirs());
    final File input = new File(objects, "input.o");
    Files.write(input.toPath(), "object".getBytes(StandardCharsets.UTF_8));
    Files.write(new File(working, "local.o").toPath(), "local".getBytes(StandardCharsets.UTF_8));
    final List<String> pre = new ArrayList<>(Arrays.asList("-cp", System.getProperty("java.class.path"),
        PathProbe.class.getName(), "-L" + objects, "-L", objects.toString(), "-F" + objects, "-F", objects.toString()));
    if (type.endsWith("Path"))
      pre.add("--input=" + input);
    final List<String[]> history = new RecordingMojo().history();
    final String name = "output %!&.bin";
    recordProbe(working, name, pre, new String[] {
        input.toString(), "local.o"
    }, dry, map, history);
    assertEquals(!dry, new File(working, name).isFile());
    Files.deleteIfExists(new File(working, name).toPath());
    Files.deleteIfExists(new File(working, name + ".map").toPath());
    assertNoTemporaryMaps(working);
    final Substitution sub = new Substitution();
    sub.setType(type);
    sub.setReplace(type.endsWith("Path") ? original.toString() : original + File.separator);
    if ("regex".equals(type))
      sub.setReplace(java.util.regex.Pattern.quote(sub.getReplace()));
    // A rule restoring empty arguments must not affect the base of nonempty paths.
    final File replay = scriptWithSubstitutions(history, shell,
        Arrays.asList(sub, substitution("regex", "^$", "restored argument")));
    // The original tree must be unavailable: otherwise an accidentally retained
    // absolute path could pass.
    final String special = "bat".equals(shell) ? " %PATH%! & ^" : " %! & ' \" $";
    final File relocated = new File(directory, "relocated " + id + special);
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
    final File result = new File(relocated, "target/bin/" + name);
    assertEquals("linked", read(result));
    assertEquals(map, new File(result.getParentFile(), name + ".map").isFile());
    if (map)
      assertEquals("map", read(new File(result.getParentFile(), name + ".map")));
    assertFalse("Output belongs in the recorded directory", new File(relocated, name).exists());
    assertNoTemporaryMaps(result.getParentFile());
  }

  private void checkRemovedArguments(final String shell) throws Exception {
    for (final String type : new String[] {
        "string", "regex"
    }) {
      for (final boolean map : new boolean[] {
          false, true
      }) {
        for (final boolean dry : new boolean[] {
            false, true
        }) {
          final File working = new File(directory, shell + type + map + dry + "/nested/output");
          assertTrue(working.mkdirs());
          final List<String> payload = Arrays.asList("-g", "", " \t ", "REPLAY_VALUE", "-remove", "-g");
          final List<String[]> history = recordArguments(working, payload, dry, map);
          assertEquals(!dry, new File(working, "result").isFile());
          Files.deleteIfExists(new File(working, "result").toPath());
          Files.deleteIfExists(new File(working, "result.map").toPath());
          final String replacement = "two words 'quoted' \"&quoted\" %PATH% !bang! $HOME ; \\\\";
          final Substitution value = substitution("string", "REPLAY_VALUE", replacement);
          final File replay = argumentScript(history, shell, working,
              Arrays.asList(substitution(type, "regex".equals(type) ? "^-g$" : "-g", ""),
                  substitution(type, "regex".equals(type) ? "^-remove$" : "-remove", ""), value));
          assertReplaySucceeds(replay, shell, directory);
          // Exact argv comparison catches unwanted empty tokens, trimming, splitting and
          // shell expansion.
          assertEquals(encodedArguments(Arrays.asList("", " \t ", replacement)), read(new File(working, "result")));
          assertEquals(map, new File(working, "result.map").isFile());
          if (map)
            assertEquals("map", read(new File(working, "result.map")));
          assertNoTemporaryMaps(working);
        }
      }
    }
    // A value temporarily emptied and then restored must survive; only the final
    // value decides removal.
    final List<String[]> history = recordArguments(directory, Arrays.asList("-g", ""), true, false);
    final File replay = argumentScript(history, shell, directory,
        Arrays.asList(substitution("string", "-g", ""), substitution("regex", "^$", "restored value")));
    assertReplaySucceeds(replay, shell, directory);
    assertEquals(encodedArguments(Arrays.asList("restored value", "restored value")),
        read(new File(directory, "result")));
  }

  private void checkRewrittenExecutable(final String shell) throws Exception {
    final boolean batch = "bat".equals(shell);
    final File original = new File(directory, "executable original " + shell);
    final File working = new File(original, "nested/output");
    assertTrue(working.mkdirs());
    final File executable = new File(original, batch ? "tool.exe" : "tool");
    Files.copy((batch ? new File(System.getenv("ComSpec")) : new File("/bin/true")).toPath(), executable.toPath());
    if (!batch)
      assertTrue(executable.setExecutable(true));
    final String[] args = batch ? new String[] {
        executable.toString(), "/d", "/c", "exit", "0"
    } : new String[] {
        executable.toString()
    };
    final StringBuilder v1 = new StringBuilder("# nar-replay-v1\t")
        .append(URLEncoder.encode(working.toString(), "UTF-8"));
    for (final String arg : args)
      v1.append('\t').append(URLEncoder.encode(arg, "UTF-8"));
    final Substitution sub = new Substitution();
    sub.setType("absolutePath");
    sub.setReplace(original.toString());
    final Script config = new Script();
    config.setScriptType(shell);
    config.setSubstitutions(Arrays.asList(sub));
    final File replay = new File(directory, "executable." + config.getExtension());
    try (PrintWriter writer = new PrintWriter(replay, "UTF-8")) {
      new NarPreparePackageMojo().processReplayFile(Arrays.asList(v1.toString(), "readable command"), config, writer);
    }
    final File relocated = new File(directory, "executable relocated " + shell + " %PATH%! &");
    Files.move(original.toPath(), relocated.toPath());
    assertReplaySucceeds(replay, shell, relocated);
  }

  private void delete(final File file) throws Exception {
    if (file.isDirectory()) {
      for (final File child : file.listFiles())
        delete(child);
    }
    Files.deleteIfExists(file.toPath());
  }

  private int execute(final File script, final String shell) throws Exception {
    return new ProcessBuilder("/bin/" + shell, script.getAbsolutePath()).directory(directory).redirectErrorStream(true)
        .redirectOutput(new File(directory, "run.log")).start().waitFor();
  }

  private List<String[]> record(final File working, final String output, final String value, final boolean dry)
      throws Exception {
    return record(working, output, value, dry, false, new RecordingMojo().history());
  }

  private List<String[]> record(final File working, final String output, final String value, final boolean dry,
      final boolean map, final List<String[]> history)
      throws Exception {
    final GccLinker linker = new GccLinker(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
        new String[] {
            ".o"
        }, new String[0], "", "", false, null);
    linker.setCommands(history);
    linker.setDryRun(dry);
    final CCTask task = new CCTask();
    final Project project = new Project();
    project.setProperty("nar.os", "Linux");
    task.setProject(project);
    task.setDecorateLinkerOptions(false);
    final String[] pre = {
        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), value
    };
    final CommandLineLinkerConfiguration config = new CommandLineLinkerConfiguration(linker, "replay-test",
        new String[][] {
            pre, new String[0]
        }, new ProcessorParam[0], false, map, false, new String[0], null);
    linker.link(task, new File(working, output), new String[] {
        "object name.o"
    }, config);
    return history;
  }

  private List<String[]> recordArguments(final File working, final List<String> payload, final boolean dry,
      final boolean map)
      throws Exception {
    final List<String> pre = new ArrayList<>(
        Arrays.asList("-cp", stageArgumentProbe().getAbsolutePath(), ReplayArgumentProbe.class.getName()));
    pre.addAll(payload);
    pre.add("END_ARGUMENTS");
    final List<String[]> history = new RecordingMojo().history();
    recordProbe(working, "result", pre, new String[0], dry, map, history);
    return history;
  }

  private void recordProbe(final File working, final String output, final List<String> pre, final String[] inputs,
      final boolean dry, final boolean map, final List<String[]> history)
      throws Exception {
    final GccLinker linker = new GccLinker(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
        new String[] {
            ".o"
        }, new String[0], "", "", false, null);
    linker.setCommands(history);
    linker.setDryRun(dry);
    final CCTask task = new CCTask();
    final Project project = new Project();
    project.setProperty("nar.os", "Linux");
    task.setProject(project);
    task.setDecorateLinkerOptions(false);
    final CommandLineLinkerConfiguration config = new CommandLineLinkerConfiguration(linker, "replay-test",
        new String[][] {
            pre.toArray(new String[pre.size()]), new String[0]
        }, new ProcessorParam[0], false, map, false, new String[0], null);
    linker.link(task, new File(working, output), inputs, config);
  }

  private File script(final List<String[]> commands, final String shell, final Substitution sub) throws Exception {
    return scriptWithSubstitutions(commands, shell, sub == null ? null : Arrays.asList(sub));
  }

  private File scriptWithSubstitutions(final List<String[]> commands, final String shell,
      final List<Substitution> substitutions)
      throws Exception {
    final File log = new File(directory, "link-commands");
    NarUtil.writeCommandFile(log, commands);
    final Script script = new Script();
    script.setScriptType(shell);
    script.setSubstitutions(substitutions);
    final File output = new File(directory, "replay." + script.getExtension());
    try (PrintWriter writer = new PrintWriter(output, "UTF-8")) {
      new NarPreparePackageMojo().processReplayFile(Files.readAllLines(log.toPath(), StandardCharsets.UTF_8), script,
          writer);
    }
    return output;
  }

  @Before
  public void setUp() throws Exception {
    directory = Files.createTempDirectory("nar-replay-test-").toFile();
  }

  private File stageArgumentProbe() throws Exception {
    // String substitutions must not accidentally rewrite the JVM's own launch
    // paths.
    final File classes = new File(directory, "launcher-g-remove/classes");
    final String resource = ReplayArgumentProbe.class.getName().replace('.', '/') + ".class";
    final File target = new File(classes, resource);
    if (!target.isFile()) {
      assertTrue(target.getParentFile().mkdirs());
      try (InputStream input = ReplayArgumentProbe.class.getResourceAsStream("/" + resource)) {
        Files.copy(input, target.toPath());
      }
    }
    return classes;
  }

  @After
  public void tearDown() throws Exception {
    delete(directory);
  }

  @Test
  public void testBatchReplayPreservesCapturedPathSuffixes() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkCapturedPathSuffixes("bat");
  }

  @Test
  public void testBatchReplayPreservesLiteralArguments() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    final File working = new File(directory, "batch work %!&");
    assertTrue(working.mkdir());
    final String value = "value \"&quoted\" %PATH% !bang! \\";
    final List<String[]> commands = record(working, "result %!&.txt", value, true);
    final File scriptFile = script(commands, "bat", null);
    final Process process = new ProcessBuilder("cmd.exe", "/d", "/c", scriptFile.getAbsolutePath()).directory(directory)
        .redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
    final int status = process.waitFor();
    assertEquals(read(new File(directory, "run.log")), 0, status);
    assertEquals(value, read(new File(working, "result %!&.txt")));
  }

  @Test
  public void testBatchReplayPreservesPathsAfterLiteralSearchEntries() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkLiteralSearchEntries("bat");
  }

  @Test
  public void testBatchReplayPublishesMapAndPreservesFailureStatus() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    final File map = new File(directory, "probe %!&.map");
    for (final String value : new String[] {
        "contents", "FAIL", "MISSING"
    }) {
      Files.write(map.toPath(), "previous map".getBytes(StandardCharsets.UTF_8));
      final List<String[]> commands = record(directory, "probe %!&", value, true, true, new RecordingMojo().history());
      final File scriptFile = script(commands, "bat", null);
      final Process process = new ProcessBuilder("cmd.exe", "/d", "/c", scriptFile.getAbsolutePath())
          .directory(directory).redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
      final int status = process.waitFor();
      assertEquals(read(new File(directory, "run.log")), "contents".equals(value) ? 0 : "FAIL".equals(value) ? 7 : 1,
          status);
      assertEquals("contents".equals(value) ? value : "previous map", read(map));
      assertNoTemporaryMaps(directory);
    }
  }

  @Test
  public void testBatchReplayRemovesOnlyExplicitlyEmptiedArguments() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkRemovedArguments("bat");
  }

  @Test
  public void testBatchReplayResolvesCapturePrefixedPathOperands() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkCapturePrefixedPaths("bat");
  }

  @Test
  public void testBatchReplayResolvesRelocatedPathsFromInvocationDirectory() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    for (final String type : new String[] {
        "absolutePath", "relativePath", "string", "regex"
    }) {
      for (final boolean map : new boolean[] {
          false, true
      }) {
        for (final boolean dry : new boolean[] {
            false, true
        }) {
          checkRelocatedPaths("bat", type, map, dry);
        }
      }
    }
  }

  @Test
  public void testBatchReplayResolvesRewrittenExecutableAndV1Record() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkRewrittenExecutable("bat");
  }

  @Test
  public void testBatchReplayTracksAllPathsThroughOrderedSubstitutions() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkPathSubstitutions("bat");
  }

  @Test
  public void testBatchReplayTracksChangedPathStructure() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    checkChangedPathStructure("bat");
  }

  @Test
  public void testLegacyQuotedLogStillWorks() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final File scriptFile = new File(directory, "legacy.sh");
    try (PrintWriter writer = new PrintWriter(scriptFile, "UTF-8")) {
      new NarPreparePackageMojo().processReplayFile(Arrays.asList("printf '%s' 'legacy value' > 'legacy output'"),
          new Script(), writer);
    }
    final Process process = new ProcessBuilder("/bin/sh", scriptFile.getAbsolutePath()).directory(directory).start();
    assertEquals(0, process.waitFor());
    assertEquals("legacy value", read(new File(directory, "legacy output")));
  }

  @Test
  public void testPosixReplayPreservesCapturedPathSuffixes() throws Exception {
    Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
    for (final String shell : new String[] {
        "sh", "bash"
    }) {
      checkCapturedPathSuffixes(shell);
    }
  }

  @Test
  public void testPosixReplayPreservesLiteralArgumentsAndWorkingDirectory() throws Exception {
    for (final String shell : new String[] {
        "sh", "bash"
    }) {
      Assume.assumeTrue(new File("/bin/" + shell).isFile());
      final File working = new File(directory, shell + " work %,'$");
      assertTrue(working.mkdir());
      final String name = "output name %,'$&.so";
      final List<String[]> commands = record(working, name, "first value '$HOME' \"quoted\" & ;", true);
      final File scriptFile = script(commands, shell, null);
      final Process process = new ProcessBuilder("/bin/" + shell, scriptFile.getAbsolutePath()).directory(directory)
          .redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
      final int status = process.waitFor();
      assertEquals(read(new File(directory, "run.log")), 0, status);
      assertEquals("first value '$HOME' \"quoted\" & ;", read(new File(working, name)));
      assertFalse(new File(directory, "output").exists());
    }
  }

  @Test
  public void testPosixReplayPreservesPathsAfterLiteralSearchEntries() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkLiteralSearchEntries(shell);
  }

  @Test
  public void testPosixReplayRemovesOnlyExplicitlyEmptiedArguments() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkRemovedArguments(shell);
  }

  @Test
  public void testPosixReplayResolvesCapturePrefixedPathOperands() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkCapturePrefixedPaths(shell);
  }

  @Test
  public void testPosixReplayResolvesRelocatedPathsFromInvocationDirectory() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    }) {
      for (final String type : new String[] {
          "absolutePath", "relativePath", "string", "regex"
      }) {
        for (final boolean map : new boolean[] {
            false, true
        }) {
          for (final boolean dry : new boolean[] {
              false, true
          }) {
            checkRelocatedPaths(shell, type, map, dry);
          }
        }
      }
    }
  }

  @Test
  public void testPosixReplayResolvesRewrittenExecutableAndV1Record() throws Exception {
    Assume.assumeTrue(new File("/bin/true").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkRewrittenExecutable(shell);
  }

  @Test
  public void testPosixReplayTracksAllPathsThroughOrderedSubstitutions() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkPathSubstitutions(shell);
  }

  @Test
  public void testPosixReplayTracksChangedPathStructure() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    for (final String shell : new String[] {
        "sh", "bash"
    })
      checkChangedPathStructure(shell);
  }

  @Test
  public void testReplayFailurePreservesOldMapAndRemovesTemporaryMap() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final File map = new File(directory, "probe.map");
    Files.write(map.toPath(), "previous map".getBytes(StandardCharsets.UTF_8));
    final List<String[]> commands = record(directory, "probe", "FAIL", true, true, new RecordingMojo().history());
    assertEquals(7, execute(script(commands, "sh", null), "sh"));
    assertEquals("previous map", read(map));
    assertNoTemporaryMaps(directory);
  }

  @Test
  public void testReplayHandlesMultipleOutputs() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final List<String[]> commands = new RecordingMojo().history();
    for (final String name : new String[] {
        "one", "two"
    }) {
      record(directory, name, name, true, true, commands);
    }
    assertEquals(0, execute(script(commands, "sh", null), "sh"));
    for (final String name : new String[] {
        "one", "two"
    }) {
      assertTrue(new File(directory, name + ".map").isFile());
      assertEquals(name, read(new File(directory, name + ".map")));
    }
    assertNoTemporaryMaps(directory);
  }

  @Test
  public void testReplayPublishesMapsFromNormalAndDryRuns() throws Exception {
    for (final String shell : new String[] {
        "sh", "bash"
    }) {
      Assume.assumeTrue(new File("/bin/" + shell).isFile());
      for (final boolean dry : new boolean[] {
          false, true
      }) {
        final File working = new File(directory, shell + "-" + dry);
        assertTrue(working.mkdir());
        final String name = "output %,'$&.so";
        final File output = new File(working, name);
        final File map = new File(working, name + ".map");
        final List<String[]> commands = record(working, name, "map contents", dry, true, new RecordingMojo().history());
        if (dry)
          assertEquals("Dry-run must only record commands", 0, working.list().length);
        Files.deleteIfExists(output.toPath());
        Files.deleteIfExists(map.toPath());
        final int status = execute(script(commands, shell, null), shell);
        assertEquals(read(new File(directory, "run.log")), 0, status);
        assertEquals("map contents", read(output));
        assertTrue("Replay must publish the map under the final name", map.isFile());
        assertEquals("map contents", read(map));
        assertEquals("Temporary maps must be cleaned up", 2, working.list().length);
      }
    }
  }

  @Test
  public void testReplayRejectsMissingMapAfterSuccessfulLink() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final File map = new File(directory, "probe.map");
    Files.write(map.toPath(), "previous map".getBytes(StandardCharsets.UTF_8));
    final List<String[]> commands = record(directory, "probe", "MISSING", true, true, new RecordingMojo().history());
    assertTrue(execute(script(commands, "sh", null), "sh") != 0);
    assertEquals("previous map", read(map));
    assertNoTemporaryMaps(directory);
  }

  @Test
  public void testReplayRejectsRemovalOfExecutable() throws Exception {
    final List<String[]> history = recordArguments(directory, Arrays.asList("value"), true, false);
    for (final String shell : new String[] {
        "sh", "bash", "bat"
    }) {
      for (final String type : new String[] {
          "string", "regex"
      }) {
        final String executable = history.get(0)[0];
        final String match = "regex".equals(type) ? "^" + java.util.regex.Pattern.quote(executable) + "$" : executable;
        try {
          scriptWithSubstitutions(history, shell, Arrays.asList(substitution(type, match, "")));
          fail("Removing the executable must fail during script generation");
        } catch (final MojoExecutionException expected) {
          assertTrue(expected.getMessage(), expected.getMessage().contains("executable"));
        }
      }
    }
  }

  @Test
  public void testReplayRejectsStaleMap() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final List<String[]> commands = record(directory, "probe", "MISSING", true, true, new RecordingMojo().history());
    for (final String argument : commands.get(0)) {
      if (argument.startsWith("-Map=")) {
        Files.write(new File(directory, argument.substring(5)).toPath(), "stale map".getBytes(StandardCharsets.UTF_8));
      }
    }
    assertTrue(execute(script(commands, "sh", null), "sh") != 0);
    assertFalse(new File(directory, "probe.map").exists());
    assertNoTemporaryMaps(directory);
  }

  @Test
  public void testSubstitutionCanIntroduceSpacesAndQuotes() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final List<String[]> commands = record(directory, "result", "REPLAY_VALUE", true);
    final Substitution sub = new Substitution();
    sub.setReplace("REPLAY_VALUE");
    sub.setReplaceWith("a value with 'quotes' and $HOME");
    final File scriptFile = script(commands, "sh", sub);
    final Process process = new ProcessBuilder("/bin/sh", scriptFile.getAbsolutePath()).directory(directory)
        .redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
    final int status = process.waitFor();
    assertEquals(read(new File(directory, "run.log")), 0, status);
    assertEquals(sub.getReplaceWith(), read(new File(directory, "result")));
  }
}
