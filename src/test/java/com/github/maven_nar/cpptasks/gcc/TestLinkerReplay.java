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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.apache.tools.ant.Project;
import org.junit.Assume;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import com.github.maven_nar.NarCompileMojo;
import com.github.maven_nar.NarPreparePackageMojo;
import com.github.maven_nar.NarUtil;
import com.github.maven_nar.Script;
import com.github.maven_nar.Substitution;
import com.github.maven_nar.cpptasks.CCTask;
import com.github.maven_nar.cpptasks.ProcessorParam;
import com.github.maven_nar.cpptasks.compiler.CommandLineLinkerConfiguration;

/** Execute the generated scripts, including the persisted command-log round trip. */
public class TestLinkerReplay {
  private File directory;

  @Before
  public void setUp() throws Exception {
    directory = Files.createTempDirectory("nar-replay-test-").toFile();
  }

  @After
  public void tearDown() throws Exception {
    delete(directory);
  }

  private void delete(final File file) throws Exception {
    if (file.isDirectory()) {
      for (final File child : file.listFiles()) delete(child);
    }
    Files.deleteIfExists(file.toPath());
  }

  @Test
  public void testPosixReplayPreservesLiteralArgumentsAndWorkingDirectory() throws Exception {
    for (final String shell : new String[] {"sh", "bash"}) {
      Assume.assumeTrue(new File("/bin/" + shell).isFile());
      final File working = new File(directory, shell + " work %,'$");
      assertTrue(working.mkdir());
      final String name = "output name %,'$&.so";
      final List<String[]> commands = record(working, name, "first value '$HOME' \"quoted\" & ;", true);
      final File scriptFile = script(commands, shell, null);
      final Process process = new ProcessBuilder("/bin/" + shell, scriptFile.getAbsolutePath())
          .directory(directory).redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
      final int status = process.waitFor();
      assertEquals(read(new File(directory, "run.log")), 0, status);
      assertEquals("first value '$HOME' \"quoted\" & ;", read(new File(working, name)));
      assertFalse(new File(directory, "output").exists());
    }
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

  @Test
  public void testBatchReplayPreservesLiteralArguments() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    final File working = new File(directory, "batch work %!&");
    assertTrue(working.mkdir());
    final String value = "value \"&quoted\" %PATH% !bang! \\";
    final List<String[]> commands = record(working, "result %!&.txt", value, true);
    final File scriptFile = script(commands, "bat", null);
    final Process process = new ProcessBuilder("cmd.exe", "/d", "/c", scriptFile.getAbsolutePath())
        .directory(directory).redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
    final int status = process.waitFor();
    assertEquals(read(new File(directory, "run.log")), 0, status);
    assertEquals(value, read(new File(working, "result %!&.txt")));
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

  private List<String[]> record(final File working, final String output, final String value, final boolean dry)
      throws Exception {
    final RecordingMojo mojo = new RecordingMojo();
    final GccLinker linker = new GccLinker(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
        new String[] {".o"}, new String[0], "", "", false, null);
    linker.setCommands(mojo.history());
    linker.setDryRun(dry);
    final CCTask task = new CCTask();
    final Project project = new Project();
    project.setProperty("nar.os", "Linux");
    task.setProject(project);
    task.setDecorateLinkerOptions(false);
    final String[] pre = {"-cp", System.getProperty("java.class.path"), Probe.class.getName(), value};
    final CommandLineLinkerConfiguration config = new CommandLineLinkerConfiguration(linker, "replay-test",
        new String[][] {pre, new String[0]}, new ProcessorParam[0], false, false, false, new String[0], null);
    linker.link(task, new File(working, output), new String[] {"object name.o"}, config);
    return mojo.history();
  }

  private File script(final List<String[]> commands, final String shell, final Substitution sub) throws Exception {
    final File log = new File(directory, "link-commands");
    NarUtil.writeCommandFile(log, commands);
    final Script script = new Script();
    script.setScriptType(shell);
    if (sub != null) script.setSubstitutions(Arrays.asList(sub));
    final File output = new File(directory, "replay." + script.getExtension());
    try (PrintWriter writer = new PrintWriter(output, "UTF-8")) {
      new NarPreparePackageMojo().processReplayFile(Files.readAllLines(log.toPath(), StandardCharsets.UTF_8), script, writer);
    }
    return output;
  }

  private static String read(final File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  private static class RecordingMojo extends NarCompileMojo {
    List<String[]> history() { return linkCommands; }
  }

  public static class Probe {
    public static void main(final String[] args) throws Exception {
      final int index = Arrays.asList(args).indexOf("-o");
      if (!"object name.o".equals(args[index + 2])) throw new IllegalArgumentException("Quoted input argument");
      Files.write(new File(args[index + 1]).toPath(), args[0].getBytes(StandardCharsets.UTF_8));
    }
  }
}
