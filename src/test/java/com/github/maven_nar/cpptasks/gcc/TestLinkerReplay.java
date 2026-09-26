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

  @Test
  public void testReplayPublishesMapsFromNormalAndDryRuns() throws Exception {
    for (final String shell : new String[] {"sh", "bash"}) {
      Assume.assumeTrue(new File("/bin/" + shell).isFile());
      for (final boolean dry : new boolean[] {false, true}) {
        final File working = new File(directory, shell + "-" + dry);
        assertTrue(working.mkdir());
        final String name = "output %,'$&.so";
        final File output = new File(working, name);
        final File map = new File(working, name + ".map");
        final List<String[]> commands = record(working, name, "map contents", dry, true, new RecordingMojo().history());
        if (dry) assertEquals("Dry-run must only record commands", 0, working.list().length);
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
  public void testBatchReplayPublishesMapAndPreservesFailureStatus() throws Exception {
    Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    final File map = new File(directory, "probe %!&.map");
    for (final String value : new String[] {"contents", "FAIL", "MISSING"}) {
      Files.write(map.toPath(), "previous map".getBytes(StandardCharsets.UTF_8));
      final List<String[]> commands = record(directory, "probe %!&", value, true, true, new RecordingMojo().history());
      final File scriptFile = script(commands, "bat", null);
      final Process process = new ProcessBuilder("cmd.exe", "/d", "/c", scriptFile.getAbsolutePath()).directory(directory)
          .redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start();
      final int status = process.waitFor();
      assertEquals(read(new File(directory, "run.log")), "contents".equals(value) ? 0 : "FAIL".equals(value) ? 7 : 1, status);
      assertEquals("contents".equals(value) ? value : "previous map", read(map));
      assertNoTemporaryMaps(directory);
    }
  }

  @Test
  public void testReplayHandlesMultipleOutputs() throws Exception {
    Assume.assumeTrue(new File("/bin/sh").isFile());
    final List<String[]> commands = new RecordingMojo().history();
    for (final String name : new String[] {"one", "two"}) {
      record(directory, name, name, true, true, commands);
    }
    assertEquals(0, execute(script(commands, "sh", null), "sh"));
    for (final String name : new String[] {"one", "two"}) {
      assertTrue(new File(directory, name + ".map").isFile());
      assertEquals(name, read(new File(directory, name + ".map")));
    }
    assertNoTemporaryMaps(directory);
  }

  private int execute(final File script, final String shell) throws Exception {
    return new ProcessBuilder("/bin/" + shell, script.getAbsolutePath()).directory(directory)
        .redirectErrorStream(true).redirectOutput(new File(directory, "run.log")).start().waitFor();
  }

  private void assertNoTemporaryMaps(final File directory) {
    for (final String name : directory.list()) {
      assertFalse("Temporary map remains: " + name, name.startsWith("nar-map-") && name.endsWith(".tmp"));
    }
  }

  private List<String[]> record(final File working, final String output, final String value, final boolean dry)
      throws Exception {
    return record(working, output, value, dry, false, new RecordingMojo().history());
  }

  private List<String[]> record(final File working, final String output, final String value, final boolean dry,
      final boolean map, final List<String[]> history) throws Exception {
    final GccLinker linker = new GccLinker(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
        new String[] {".o"}, new String[0], "", "", false, null);
    linker.setCommands(history);
    linker.setDryRun(dry);
    final CCTask task = new CCTask();
    final Project project = new Project();
    project.setProperty("nar.os", "Linux");
    task.setProject(project);
    task.setDecorateLinkerOptions(false);
    final String[] pre = {"-cp", System.getProperty("java.class.path"), Probe.class.getName(), value};
    final CommandLineLinkerConfiguration config = new CommandLineLinkerConfiguration(linker, "replay-test",
        new String[][] {pre, new String[0]}, new ProcessorParam[0], false, map, false, new String[0], null);
    linker.link(task, new File(working, output), new String[] {"object name.o"}, config);
    return history;
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
      if (!"object name.o".equals(args[args.length - 1])) throw new IllegalArgumentException("Quoted input argument");
      Files.write(new File(args[index + 1]).toPath(), args[0].getBytes(StandardCharsets.UTF_8));
      if (!"MISSING".equals(args[0])) {
        for (final String arg : args) {
          if (arg.startsWith("-Map=")) Files.write(new File(arg.substring(5)).toPath(), args[0].getBytes(StandardCharsets.UTF_8));
        }
      }
      if ("FAIL".equals(args[0])) System.exit(7);
    }
  }
}
