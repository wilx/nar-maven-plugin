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
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * A raw argument vector, kept separate from shell syntax until a replay script
 * is written.
 */
public final class ReplayCommand {
  /**
   * Literal argument text with path positions kept separately until shell
   * rendering.
   */
  private static final class Argument {
    /**
     * A component's extent prevents unrelated captures from inheriting its base.
     */
    private static final class Path {
      final int start;
      final int end;

      Path(final int start, final int end) {
        this.start = start;
        this.end = end;
      }

      boolean overlaps(final int start, final int end) {
        return this.start < end && start < this.end;
      }
    }

    private String value;
    private final List<Path> paths = new ArrayList<>();

    private boolean pathList;

    Argument(final String original, final boolean pathList) {
      this.value = original;
      this.pathList = pathList;
      recognizeOperand(original);
    }

    private void addPath(final String text, final int start) {
      for (final Path path : this.paths) {
        if (path.start == start)
          return;
      }
      this.paths.add(new Path(start, pathEnd(text, start, text.length(), new ArrayList<Path>())));
    }

    /**
     * Locate this occurrence's complete path, including directory text added before
     * its capture.
     */
    private int copiedPathStart(final Path path, final String text, final Substitution.Replacement edit,
        final Substitution.GroupCopy copy) {
      final int position = Math.max(path.start, copy.start);
      if (position != copy.start)
        return copy.outputStart + position - copy.start;
      int start = edit.outputStart;
      Substitution.GroupCopy preceding = null;
      for (final Substitution.GroupCopy previous : edit.copies) {
        if (previous == copy)
          break;
        start = previous.outputStart + previous.end - previous.start;
        preceding = previous;
      }
      // Captures of the same component can reconstruct one filename, even when
      // reordered or when text between their source ranges is dropped. Keep the
      // base before all fragments.
      final String gap = text.substring(start, copy.outputStart);
      if (preceding != null && path.overlaps(preceding.start, preceding.end) && preceding.end <= path.end
          && gap.indexOf(',') < 0 && gap.indexOf(':') < 0 && gap.indexOf(';') < 0) {
        return copiedPathStart(path, text, edit, preceding);
      }
      // Only interpret newly inserted literal text. Punctuation inside a captured
      // filename
      // remains part of that filename, even when it looks like an option or list
      // separator.
      final int listStart = pathListStart(text, copy.outputStart);
      if (listStart >= 0)
        start = Math.max(start, listStart);
      if (preceding != null || listStart >= 0) {
        for (int i = start; i < copy.outputStart; i++) {
          final char c = text.charAt(i);
          final boolean drive = c == ':' && i == start + 1 && Character.isLetter(text.charAt(start))
              && i + 1 < copy.outputStart && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '\\');
          final boolean comma = c == ',' && (listStart < 0 || text.startsWith("-Wl,"));
          if (comma || c == ';' || c == ':' && !drive)
            start = i + 1;
        }
      }
      String prefix = text.substring(start, copy.outputStart);
      if (prefix.startsWith("-Wl,")) {
        start += prefix.lastIndexOf(',') + 1;
        prefix = text.substring(start, copy.outputStart);
      }
      if (prefix.startsWith("-L") || prefix.startsWith("-F")) {
        start += 2;
      } else if (prefix.startsWith("-")) {
        // An option assignment can contain a path, but an unknown wrapper is literal
        // text.
        final int equals = prefix.indexOf('=');
        start = equals >= 0 ? start + equals + 1 : copy.outputStart;
      }
      return start;
    }

    /**
     * Keep punctuation in unchanged or copied path text literal in the next rule.
     */
    private void keepLiteral(final Path path, final int start, final int end, final int outputStart,
        final List<Path> literal) {
      if (path.overlaps(start, end)) {
        literal.add(
            new Path(outputStart + Math.max(path.start, start) - start, outputStart + Math.min(path.end, end) - start));
      }
    }

    private void keepUnchanged(final Path path, final List<Substitution.Replacement> edits, final List<Path> literal) {
      int start = 0;
      int shift = 0;
      for (final Substitution.Replacement edit : edits) {
        keepLiteral(path, start, edit.start, start + shift, literal);
        start = edit.end;
        shift = edit.outputEnd - edit.end;
      }
      keepLiteral(path, start, this.value.length(), start + shift, literal);
    }

    private void move(final Path path, final String before, final String after,
        final List<Substitution.Replacement> edits, final SortedSet<Integer> moved) {
      final int position = path.start;
      int shift = 0;
      for (final Substitution.Replacement edit : edits) {
        if (position < edit.start)
          break;
        if (position < edit.end || position == edit.start) {
          boolean copied = false;
          for (final Substitution.GroupCopy copy : edit.copies) {
            if (path.overlaps(copy.start, copy.end) || copy.start == copy.end && position == copy.start) {
              moved.add(copiedPathStart(path, after, edit, copy));
              copied = true;
            }
          }
          if (copied)
            return;
          // A path-prefix replacement retains its boundary, including an empty
          // replacement.
          if (position == edit.start) {
            moved.add(edit.outputStart);
            return;
          }
          // Preserve boundaries when a literal replacement retains part of the matched
          // text.
          int prefix = 0;
          while (edit.start + prefix < edit.end && edit.outputStart + prefix < edit.outputEnd
              && before.charAt(edit.start + prefix) == after.charAt(edit.outputStart + prefix))
            prefix++;
          if (position <= edit.start + prefix) {
            moved.add(edit.outputStart + position - edit.start);
            return;
          }
          int suffix = 0;
          while (edit.end - suffix > edit.start && edit.outputEnd - suffix > edit.outputStart
              && before.charAt(edit.end - suffix - 1) == after.charAt(edit.outputEnd - suffix - 1))
            suffix++;
          if (position >= edit.end - suffix)
            moved.add(edit.outputEnd - (edit.end - position));
          return;
        }
        shift = edit.outputEnd - edit.end;
      }
      moved.add(position + shift);
    }

    private int pathEnd(final String text, final int start, final int limit, final List<Path> literal) {
      final boolean list = pathListStart(text, start) >= 0 || limit < text.length();
      final boolean comma = text.startsWith("-Wl,") || text.startsWith("-") && !text.startsWith("-L")
          && !text.startsWith("-F") && text.indexOf('=') >= 0 && text.indexOf('=') < start;
      for (int i = start; i < limit; i++) {
        boolean copied = false;
        for (final Path piece : literal) {
          if (i >= piece.start && i < piece.end) {
            copied = true;
            break;
          }
        }
        if (copied)
          continue;
        final char c = text.charAt(i);
        final boolean drive = c == ':' && i == start + 1 && Character.isLetter(text.charAt(start)) && i + 1 < limit
            && (text.charAt(i + 1) == '/' || text.charAt(i + 1) == '\\');
        if (c == ',' && comma || list && (c == ';' || c == ':' && !drive))
          return i;
      }
      return limit;
    }

    private int pathListStart(final String text, final int end) {
      // Inspect option syntax before the capture; never scan the captured filename
      // itself.
      final boolean forwarded = text.startsWith("-Wl,");
      final int start = forwarded ? text.lastIndexOf(',', end - 1) + 1 : 0;
      for (final String option : new String[] {
          "-rpath", "--rpath", "-rpath-link", "--rpath-link"
      }) {
        final String prefix = option + "=";
        if (start + prefix.length() <= end && text.startsWith(prefix, start))
          return start + prefix.length();
      }
      if (forwarded && start >= 4) {
        final int previous = text.lastIndexOf(',', start - 2) + 1;
        if (isPathListOption(text.substring(previous, start - 1)))
          return start;
      }
      return this.pathList ? 0 : -1;
    }

    private void recognizeOperand(final String text) {
      final int start;
      if (isAbsolute(text)) {
        start = 0;
      } else if ((text.startsWith("-L") || text.startsWith("-F")) && isAbsolute(text.substring(2))) {
        start = 2;
      } else {
        start = -1;
      }
      if (start >= 0)
        addPath(text, start);
    }

    void removeAbsolutePaths() {
      // Decide the base after all rules, so a path made absolute again needs no
      // runtime prefix.
      for (final java.util.Iterator<Path> positions = this.paths.iterator(); positions.hasNext();) {
        if (isAbsolute(this.value.substring(positions.next().start)))
          positions.remove();
      }
    }

    String render(final boolean batch, final boolean executable) throws MojoExecutionException {
      if (this.paths.isEmpty())
        return batch && executable ? batchPath(this.value) : quote(this.value, batch);
      // Ordinary quote mode protects metacharacters in the expanded Windows
      // directory.
      if (batch && this.value.indexOf('"') >= 0) {
        throw new MojoExecutionException("Invalid quote in replay path: " + this.value);
      }
      final StringBuilder result = new StringBuilder(batch ? "\"" : "");
      int previous = 0;
      for (final Path path : this.paths) {
        final int position = path.start;
        final String literal = this.value.substring(previous, position);
        result.append(batch ? literal.replace("%", "%%") : quote(literal, false));
        result.append(batch ? "%_nar_replay_base%\\" : "\"$_nar_replay_base\"'/'");
        previous = position;
      }
      final String tail = this.value.substring(previous);
      result.append(batch ? tail.replace("%", "%%") : quote(tail, false));
      if (batch) {
        // Escape trailing backslashes for the Windows argument parser before closing
        // the quote.
        final int length = result.length();
        for (int i = length - 1; i >= 0 && result.charAt(i) == '\\'; i--)
          result.append('\\');
        result.append('"');
      }
      return result.toString();
    }

    void substitute(final String next, final List<Substitution.Replacement> edits, final boolean pathRule,
        final boolean pathList) {
      if (pathRule) {
        // Every explicit absolute path match is recognized in this rule's actual input.
        for (final Substitution.Replacement edit : edits) {
          if (isAbsolute(this.value.substring(edit.start, edit.end)))
            addPath(this.value, edit.start);
        }
      }
      this.pathList = pathList;
      final SortedSet<Integer> moved = new TreeSet<>();
      final List<Path> literal = new ArrayList<>();
      for (final Path path : this.paths) {
        move(path, this.value, next, edits, moved);
        keepUnchanged(path, edits, literal);
      }
      // Lookaround captures can copy text outside the replaced interval as well.
      for (final Substitution.Replacement edit : edits) {
        for (final Substitution.GroupCopy copy : edit.copies) {
          for (final Path path : this.paths) {
            if (path.overlaps(copy.start, copy.end)) {
              final int start = copiedPathStart(path, next, edit, copy);
              // An edit inside a path retains the boundary before its unchanged prefix.
              if (start != edit.outputStart || path.start >= edit.start || edit.start >= path.end)
                moved.add(start);
              keepLiteral(path, copy.start, copy.end, copy.outputStart, literal);
              // The directory prefix belongs to this component as well.
              literal.add(new Path(start, copy.outputStart + Math.max(path.start, copy.start) - copy.start));
            }
          }
        }
      }
      this.paths.clear();
      final List<Integer> starts = new ArrayList<>(moved);
      for (int i = 0; i < starts.size(); i++) {
        final int start = starts.get(i);
        final int limit = i + 1 < starts.size() ? starts.get(i + 1) : next.length();
        this.paths.add(new Path(start, pathEnd(next, start, limit, literal)));
      }
      this.value = next;
      recognizeOperand(next);
    }
  }

  private static final String LEGACY_HEADER = "# nar-replay-v1\t";
  private static final String HEADER = "# nar-replay-v2\t";

  private static String batchPath(final String path) {
    return "\"" + path.replace("%", "%%") + "\"";
  }

  private static String command(final String[] args, final boolean batch) {
    final StringBuilder result = new StringBuilder();
    for (final String argument : args) {
      if (result.length() != 0)
        result.append(' ');
      result.append(batch && result.length() == 0 ? batchPath(argument) : quote(argument, batch));
    }
    return result.toString();
  }

  public static ReplayCommand decode(final String line) throws MojoExecutionException {
    try {
      final boolean legacy = line.startsWith(LEGACY_HEADER);
      final int offset = legacy ? 1 : 3;
      final String[] fields = line.substring((legacy ? LEGACY_HEADER : HEADER).length()).split("\t", -1);
      if (fields.length <= offset)
        throw new IllegalArgumentException("Missing command arguments");
      final String[] args = new String[fields.length - offset];
      for (int i = offset; i < fields.length; i++)
        args[i - offset] = URLDecoder.decode(fields[i], "UTF-8");
      return new ReplayCommand(URLDecoder.decode(fields[0], "UTF-8"), args,
          legacy ? "" : URLDecoder.decode(fields[1], "UTF-8"), legacy ? "" : URLDecoder.decode(fields[2], "UTF-8"));
    } catch (final IllegalArgumentException | UnsupportedEncodingException ex) {
      throw new MojoExecutionException("Invalid structured replay command", ex);
    }
  }

  private static String escapeBatch(final String line) {
    // Keep cmd outside quote mode; the quoted argv reaches the Windows runtime
    // unchanged.
    return line.replace("^", "^^").replace("\"", "^\"").replace("&", "^&").replace("|", "^|").replace("<", "^<")
        .replace(">", "^>").replace("(", "^(").replace(")", "^)");
  }

  private static boolean isAbsolute(final String value) {
    // Records can be rendered on a different platform; do not use the host's
    // File.isAbsolute().
    return value.startsWith("/") || value.startsWith("\\") || value.length() >= 3 && Character.isLetter(value.charAt(0))
        && value.charAt(1) == ':' && (value.charAt(2) == '/' || value.charAt(2) == '\\');
  }

  private static boolean isPathListOption(final String option) {
    return "-rpath".equals(option) || "--rpath".equals(option) || "-rpath-link".equals(option)
        || "--rpath-link".equals(option);
  }

  public static boolean isRecord(final String line) {
    return line.startsWith(HEADER) || line.startsWith(LEGACY_HEADER);
  }

  private static String quote(final String argument, final boolean batch) {
    if (!batch)
      return "'" + argument.replace("'", "'\"'\"'") + "'";
    // Windows programs parse backslashes before quotes and at the end of a quoted
    // argument.
    final StringBuilder result = new StringBuilder("\"");
    int slashes = 0;
    for (int i = 0; i < argument.length(); i++) {
      final char c = argument.charAt(i);
      if (c == '\\') {
        slashes++;
      } else {
        for (int j = 0; j < slashes * (c == '"' ? 2 : 1); j++)
          result.append('\\');
        slashes = 0;
        if (c == '"')
          result.append('\\');
        result.append(c == '%' ? "%%" : Character.toString(c));
      }
    }
    for (int j = 0; j < slashes * 2; j++)
      result.append('\\');
    return escapeBatch(result.append('"').toString());
  }

  private static String substitute(final String value, final Script script) {
    String result = value;
    if (script.getSubstitutions() != null) {
      for (final Substitution substitution : script.getSubstitutions())
        result = substitution.substitute(result);
    }
    return result;
  }

  private static void writeBatch(final Script script, final PrintWriter writer, final String cwd, final String line,
      final String display, final String temporary, final String destination, final boolean needsBase) {
    writer.println("setlocal DisableDelayedExpansion");
    if (needsBase)
      writer.println("set \"_nar_replay_base=%CD%\"");
    writer.println("pushd " + batchPath(cwd));
    writer.println("if errorlevel 1 exit /b 1");
    final String temp = temporary == null ? null : batchPath(".\\" + temporary);
    if (temp != null) {
      writer.println("if exist " + temp + " del /f /q " + temp);
      writer.println("if exist " + temp + " (popd & exit /b 1)");
    }
    if (script.isEchoLines())
      writer.println("echo " + escapeBatch(display));
    writer.println(line);
    writer.println("set \"_nar_replay_status=%errorlevel%\"");
    if (temp != null) {
      writer.println("if \"%_nar_replay_status%\"==\"0\" if not exist " + temp + " set \"_nar_replay_status=1\"");
      writer.println(
          "if \"%_nar_replay_status%\"==\"0\" for %%F in (" + temp + ") do if %%~zF==0 set \"_nar_replay_status=1\"");
      writer.println("set \"_nar_replay_attributes=\"");
      writer.println("if \"%_nar_replay_status%\"==\"0\" for %%F in (" + batchPath(".\\" + destination)
          + ") do set \"_nar_replay_attributes=%%~aF\"");
      writer.println("if \"%_nar_replay_attributes:~0,1%\"==\"d\" set \"_nar_replay_status=1\"");
      writer.println(
          "if \"%_nar_replay_status%\"==\"0\" move /y " + temp + " " + batchPath(".\\" + destination) + " >nul");
      writer.println("if \"%_nar_replay_status%\"==\"0\" set \"_nar_replay_status=%errorlevel%\"");
      writer.println("if exist " + temp + " del /f /q " + temp);
    }
    writer.println("popd");
    writer.println("if not \"%_nar_replay_status%\"==\"0\" exit /b %_nar_replay_status%");
    writer.println("endlocal");
  }

  private static void writePosix(final Script script, final PrintWriter writer, final String cwd, final String line,
      final String temporary, final String destination, final boolean needsBase) {
    writer.println("(");
    if (needsBase)
      writer.println("_nar_replay_base=$(pwd -P) || exit $?");
    writer.println("cd " + quote(cwd, false) + " || exit $?");
    final String temp = temporary == null ? null : quote("./" + temporary, false);
    if (temp != null) {
      // Clear an earlier failed replay's map so a linker that produces nothing cannot
      // appear successful.
      writer.println("trap " + quote("rm -f " + temp, false) + " 0");
      writer.println("rm -f " + temp + " || exit $?");
    }
    if (script.isEchoLines())
      writer.println("printf '%s\\n' " + quote(line, false));
    writer.println(line);
    if (temp != null) {
      writer.println("_nar_replay_status=$?");
      writer.println("if [ \"$_nar_replay_status\" -ne 0 ]; then exit \"$_nar_replay_status\"; fi");
      writer.println("test -s " + temp + " || exit 1");
      final String output = quote("./" + destination, false);
      writer.println("test ! -d " + output + " || exit 1");
      writer.println("mv -f " + temp + " " + output + " || exit $?");
    }
    writer.println(") || exit $?");
  }

  private final String directory;

  private final String[] arguments;

  private final String temporaryMap;

  private final String finalMap;

  public ReplayCommand(final File directory, final String[] arguments) {
    this(directory.getAbsolutePath(), arguments, "", "");
  }

  public ReplayCommand(final File directory, final String[] arguments, final String temporaryMap,
      final String finalMap) {
    this(directory.getAbsolutePath(), arguments, temporaryMap == null ? "" : temporaryMap,
        finalMap == null ? "" : finalMap);
  }

  private ReplayCommand(final String directory, final String[] arguments, final String temporaryMap,
      final String finalMap) {
    this.directory = directory;
    this.arguments = arguments.clone();
    this.temporaryMap = temporaryMap;
    this.finalMap = finalMap;
  }

  public String display() {
    return ("(cd " + quote(this.directory, false) + " && " + command(this.arguments, false) + ")").replace("\r", "\\r")
        .replace("\n", "\\n");
  }

  /** Preserve literal arguments in metadata alongside a readable command. */
  public String encode() throws UnsupportedEncodingException {
    final StringBuilder result = new StringBuilder(HEADER).append(URLEncoder.encode(this.directory, "UTF-8"));
    result.append('\t').append(URLEncoder.encode(this.temporaryMap, "UTF-8"));
    result.append('\t').append(URLEncoder.encode(this.finalMap, "UTF-8"));
    for (final String argument : this.arguments)
      result.append('\t').append(URLEncoder.encode(argument, "UTF-8"));
    return result.toString();
  }

  private boolean isPathListOperand(final String[] values, final int index) {
    int previous = index - 1;
    while (previous >= 0 && values[previous].isEmpty() && !this.arguments[previous].isEmpty())
      previous--;
    if (previous >= 0 && "-Xlinker".equals(values[previous])) {
      previous--;
      while (previous >= 0 && values[previous].isEmpty() && !this.arguments[previous].isEmpty())
        previous--;
    }
    if (previous < 0)
      return false;
    final String option = values[previous];
    return isPathListOption(option) || option.startsWith("-Wl,") && isPathListOption(option.substring(4));
  }

  private Argument[] substituteArguments(final Script script) {
    final Argument[] result = new Argument[this.arguments.length];
    for (int i = 0; i < result.length; i++)
      result[i] = new Argument(this.arguments[i], isPathListOperand(this.arguments, i));
    if (script.getSubstitutions() != null) {
      for (final Substitution rule : script.getSubstitutions()) {
        final boolean pathRule = "absolutePath".equals(rule.getType()) || "relativePath".equals(rule.getType());
        final String[] next = new String[result.length];
        final List<List<Substitution.Replacement>> edits = new ArrayList<>();
        for (int i = 0; i < result.length; i++) {
          final List<Substitution.Replacement> replacements = new ArrayList<>();
          edits.add(replacements);
          next[i] = rule.substitute(result[i].value, result[i].paths.isEmpty() && !pathRule ? null : replacements);
        }
        // Option context must reflect the same rule as its operand, including inserted
        // or removed options.
        for (int i = 0; i < result.length; i++) {
          result[i].substitute(next[i], edits.get(i), pathRule, isPathListOperand(next, i));
        }
      }
    }
    for (final Argument argument : result)
      argument.removeAbsolutePaths();
    return result;
  }

  public void write(final Script script, final PrintWriter writer) throws MojoExecutionException {
    final boolean batch = "bat".equals(script.getScriptType());
    if (!batch && !"sh".equals(script.getScriptType()) && !"bash".equals(script.getScriptType())) {
      throw new MojoExecutionException("Unknown replay script type: " + script.getScriptType());
    }
    final String cwd = substitute(this.directory, script);
    final List<String> args = new ArrayList<>();
    final StringBuilder command = new StringBuilder();
    boolean needsBase = false;
    final Argument[] rewritten = substituteArguments(script);
    for (int i = 0; i < this.arguments.length; i++) {
      final Argument argument = rewritten[i];
      final String value = argument.value;
      // Only an explicitly emptied argument is removed, after every substitution has
      // run.
      if (!this.arguments[i].isEmpty() && value.isEmpty()) {
        if (i == 0)
          throw new MojoExecutionException("Replay substitutions removed the executable: " + this.arguments[i]);
        continue;
      }
      if (!args.isEmpty())
        command.append(' ');
      args.add(value);
      command.append(argument.render(batch, i == 0));
      needsBase |= !argument.paths.isEmpty();
    }
    final String line = command.toString();
    final String temporary = this.temporaryMap.isEmpty() ? null : substitute(this.temporaryMap, script);
    final String destination = this.finalMap.isEmpty() ? null : substitute(this.finalMap, script);
    if (batch) {
      writeBatch(script, writer, cwd, line, command(args.toArray(new String[args.size()]), true), temporary,
          destination, needsBase);
    } else {
      writePosix(script, writer, cwd, line, temporary, destination, needsBase);
    }
  }
}
