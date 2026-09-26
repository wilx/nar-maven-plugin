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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.maven.plugins.annotations.Parameter;

/**
 * Substitutes matched strings in a replay script
 *
 * @author Brent Chiodo
 */
public class Substitution {
  
  private static final String[] types = new String[] {"string", "relativePath", "absolutePath", "regex"};
  private Pattern pattern;
  private final Map<String, Integer> namedGroups = new HashMap<>();
  
  protected String type;
  protected String replace;
  protected String replaceWith;
  
  public Substitution() {
    this.type = "string";
    this.replaceWith = "";
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getReplace() {
    return replace;
  }

  public void setReplace(String replace) {
    this.replace = replace;
  }

  public String getReplaceWith() {
    return replaceWith;
  }

  public void setReplaceWith(String replaceWith) {
    this.replaceWith = replaceWith;
  }
  
  public String substitute(String line) {
    return substitute(line, null);
  }

  /** Apply a rule to the whole value, optionally retaining the positions of its edits for replay paths. */
  String substitute(final String line, final List<Replacement> replacements) {
    if (replace == null) return line;
    final boolean regex = "regex".equals(type);
    final Matcher matcher;
    if (regex) {
      if (pattern == null) pattern = Pattern.compile(replace);
      matcher = pattern.matcher(line);
    } else {
      String match = replace;
      if ("relativePath".equals(type)) match = new File(replace).getPath() + File.separator;
      else if ("absolutePath".equals(type)) match = new File(replace).getAbsolutePath() + File.separator;
      matcher = Pattern.compile(Pattern.quote(match)).matcher(line);
    }
    final String replacement = regex ? replaceWith : Matcher.quoteReplacement(replaceWith);
    final StringBuffer result = new StringBuffer();
    int previousEnd = 0;
    while (matcher.find()) {
      final int outputStart = result.length() + matcher.start() - previousEnd;
      matcher.appendReplacement(result, replacement);
      if (replacements != null) {
        final Replacement edit = new Replacement(matcher.start(), matcher.end(), outputStart, result.length());
        if (regex) recordGroupCopies(matcher, outputStart, edit.copies);
        replacements.add(edit);
      }
      previousEnd = matcher.end();
    }
    return matcher.appendTail(result).toString();
  }

  /** The regex engine validates and expands the replacement; retain only the origins of copied groups. */
  private void recordGroupCopies(final Matcher matcher, final int outputStart, final List<GroupCopy> copies) {
    int output = outputStart;
    for (int i = 0; i < replaceWith.length();) {
      final char c = replaceWith.charAt(i++);
      if (c == '\\') {
        i++;
        output++;
      } else if (c != '$') {
        output++;
      } else {
        final int group;
        if (replaceWith.charAt(i) == '{') {
          final int end = replaceWith.indexOf('}', i);
          group = namedGroupIndex(replaceWith.substring(i + 1, end));
          i = end + 1;
        } else {
          int number = replaceWith.charAt(i++) - '0';
          while (i < replaceWith.length() && replaceWith.charAt(i) >= '0' && replaceWith.charAt(i) <= '9') {
            final int next = number * 10 + replaceWith.charAt(i) - '0';
            if (next > matcher.groupCount()) break;
            number = next;
            i++;
          }
          group = number;
        }
        if (matcher.start(group) >= 0) {
          copies.add(new GroupCopy(matcher.start(group), matcher.end(group), output));
          output += matcher.end(group) - matcher.start(group);
        }
      }
    }
  }

  private int namedGroupIndex(final String name) {
    final Integer cached = this.namedGroups.get(name);
    if (cached != null) return cached;
    // Java 7 has group(name), but not start(name). Ask the regex parser for the capture count
    // at the named group's opening. Compiling prefixes also handles quoted text, character
    // classes and comments without duplicating Pattern's grammar. The empty alternative lets
    // group(name) validate the name without requiring any particular input to match.
    final String regex = pattern.pattern();
    for (int end = regex.indexOf('>'); end >= 0; end = regex.indexOf('>', end + 1)) {
      final String prefix = regex.substring(0, end + 1) + "\n";
      final StringBuilder closes = new StringBuilder();
      for (int count = 0; count <= end; count++, closes.append(')')) {
        final Matcher probe;
        try {
          probe = Pattern.compile("(?:" + prefix + closes + "\n|)").matcher("");
        } catch (final PatternSyntaxException invalidPrefix) {
          continue;
        }
        if (probe.matches()) {
          try {
            probe.group(name);
            final int index = probe.groupCount();
            this.namedGroups.put(name, index);
            return index;
          } catch (final IllegalArgumentException missingName) {
            // This '>' belongs to quoted text or a different group.
          }
        }
        break;
      }
    }
    throw new IllegalArgumentException("Cannot locate named regex group: " + name);
  }

  static final class GroupCopy {
    final int start;
    final int end;
    final int outputStart;

    GroupCopy(final int start, final int end, final int outputStart) {
      this.start = start;
      this.end = end;
      this.outputStart = outputStart;
    }
  }

  /** Half-open input and output ranges for one replacement; no generated shell syntax is included. */
  static final class Replacement {
    final List<GroupCopy> copies = new ArrayList<>();
    final int start;
    final int end;
    final int outputStart;
    final int outputEnd;

    Replacement(final int start, final int end, final int outputStart, final int outputEnd) {
      this.start = start;
      this.end = end;
      this.outputStart = outputStart;
      this.outputEnd = outputEnd;
    }
  }
}
