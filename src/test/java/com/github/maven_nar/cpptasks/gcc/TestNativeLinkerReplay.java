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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.tools.ant.Project;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.github.maven_nar.NarPreparePackageMojo;
import com.github.maven_nar.NarUtil;
import com.github.maven_nar.ReplayCommandList;
import com.github.maven_nar.Script;
import com.github.maven_nar.Substitution;
import com.github.maven_nar.cpptasks.CCTask;
import com.github.maven_nar.cpptasks.ProcessorParam;
import com.github.maven_nar.cpptasks.compiler.CommandLineLinkerConfiguration;

/** Real linking and loading catch RUNPATH errors that successful link commands cannot detect. */
public class TestNativeLinkerReplay {
  private File directory;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue("ELF RUNPATH test", System.getProperty("os.name").equals("Linux"));
    directory = Files.createTempDirectory("nar-native-replay-").toFile();
    for (final String command : new String[] {"gcc", "g++", "readelf", "sh", "bash"}) {
      try {
        final int status = "sh".equals(command) || "bash".equals(command)
            ? run(directory, command, "-c", "exit 0") : run(directory, command, "--version");
        Assume.assumeTrue(command + " unavailable", status == 0);
      } catch (final java.io.IOException unavailable) {
        Assume.assumeNoException(unavailable);
      }
    }
  }

  @After
  public void tearDown() throws Exception {
    if (directory != null) delete(directory);
  }

  private void delete(final File file) throws Exception {
    if (file.isDirectory()) for (final File child : file.listFiles()) delete(child);
    Files.deleteIfExists(file.toPath());
  }

  @Test
  public void testDuplicatedRuntimePaths() throws Exception {
    checkReplay("duplicate", true);
  }

  @Test
  public void testPathWrappedInLinkerOption() throws Exception {
    checkReplay("wrapped", false);
  }

  @Test
  public void testLiteralRuntimePathPrefixes() throws Exception {
    for (final String mode : new String[] {"literal", "literalWrapped"}) {
      for (final boolean map : new boolean[] {false, true}) checkReplay(mode, map);
    }
  }

  private void checkReplay(final String mode, final boolean map) throws Exception {
    final boolean wrapped = "wrapped".equals(mode);
    final boolean literal = mode.startsWith("literal");
    final boolean attached = "literalWrapped".equals(mode);
    for (final String compiler : new String[] {"gcc", "g++"}) {
      for (final boolean dry : new boolean[] {false, true}) {
        final File root = new File(directory, mode + map + compiler + dry);
        final File output = new File(root, "nested/output");
        final File plugins = new File(root, "lib/plugins");
        assertTrue(output.mkdirs());
        assertTrue(plugins.mkdirs());
        for (final String source : new String[] {"provider.c", "consumer.c"}) {
          try (InputStream input = getClass().getResourceAsStream("/replay-runpath/" + source)) {
            Files.copy(input, new File(root, source).toPath());
          }
        }
        assertCommand(root, compiler, "-shared", "-fPIC", "provider.c", "-o", new File(plugins, "libreplayprobe.so").toString());
        assertCommand(root, compiler, "-c", "consumer.c", "-o", "main.o");
        final GccLinker linker = new GccLinker(compiler, new String[] {".o"}, new String[0], "", "", false, null);
        final ReplayCommandList history = new ReplayCommandList();
        linker.setCommands(history);
        linker.setDryRun(dry);
        final CCTask task = new CCTask();
        final Project project = new Project();
        project.setProperty("nar.os", "Linux");
        task.setProject(project);
        task.setDecorateLinkerOptions(false);
        final String[] preargs = attached ? new String[] {"-Wl,-rpath," + new File(root, "lib")}
            : new String[] {"-Xlinker", "-rpath", "-Xlinker", new File(root, "lib").toString()};
        final CommandLineLinkerConfiguration config = new CommandLineLinkerConfiguration(linker, "native-replay",
            new String[][] {preargs, {"-L" + plugins, "-lreplayprobe"}},
            new ProcessorParam[0], false, map, false, new String[0], null);
        final File binary = new File(output, "probe");
        linker.link(task, binary, new String[] {new File(root, "main.o").toString()}, config);
        assertEquals(!dry, binary.isFile());
        final File commands = new File(root, "link-commands");
        NarUtil.writeCommandFile(commands, history);
        final List<Substitution> rules = new ArrayList<>();
        rules.add(substitution("absolutePath", root.toString(), ""));
        if (wrapped) rules.add(substitution("regex", "^(-Xlinker|-rpath)$", ""));
        rules.add(substitution("regex", attached ? "^-Wl,-rpath,(lib)$" : "^(lib)$",
            literal ? (attached ? "-Wl,-rpath," : "") + "/usr/lib:$1/plugins"
                : wrapped ? "-Wl,-rpath,$1/plugins" : "$1:$1/plugins"));
        for (final String shell : new String[] {"sh", "bash"}) {
          Files.deleteIfExists(binary.toPath());
          Files.deleteIfExists(new File(output, "probe.map").toPath());
          final Script script = new Script();
          script.setScriptType(shell);
          script.setSubstitutions(rules);
          final File replay = new File(root, "replay." + shell);
          try (PrintWriter writer = new PrintWriter(replay, "UTF-8")) {
            new NarPreparePackageMojo().processReplayFile(Files.readAllLines(commands.toPath(), StandardCharsets.UTF_8), script, writer);
          }
          assertCommand(root, shell, replay.toString());
          assertCommand(root, "readelf", "-d", binary.toString());
          final String expected = literal ? "/usr/lib:" + plugins
              : wrapped ? plugins.toString() : new File(root, "lib") + ":" + plugins;
          assertTrue(readLog(), readLog().contains("[" + expected + "]"));
          // Neither the project cwd nor LD_LIBRARY_PATH may hide a relative RUNPATH entry.
          assertCommand(directory, binary.toString());
          assertEquals(map, new File(output, "probe.map").isFile());
          for (final String name : output.list()) assertFalse(name, name.startsWith("nar-map-") && name.endsWith(".tmp"));
        }
      }
    }
  }

  private static Substitution substitution(final String type, final String from, final String to) {
    final Substitution substitution = new Substitution();
    substitution.setType(type);
    substitution.setReplace(from);
    substitution.setReplaceWith(to);
    return substitution;
  }

  private int run(final File working, final String... command) throws Exception {
    final ProcessBuilder process = new ProcessBuilder(command).directory(working).redirectErrorStream(true)
        .redirectOutput(new File(directory, "run.log"));
    process.environment().remove("LD_LIBRARY_PATH");
    process.environment().remove("LD_PRELOAD");
    process.environment().put("LC_ALL", "C");
    return process.start().waitFor();
  }

  private void assertCommand(final File working, final String... command) throws Exception {
    final int status = run(working, command);
    assertEquals(Arrays.toString(command) + "\n" + readLog(), 0, status);
  }

  private String readLog() throws Exception {
    return new String(Files.readAllBytes(new File(directory, "run.log").toPath()), StandardCharsets.UTF_8);
  }
}
