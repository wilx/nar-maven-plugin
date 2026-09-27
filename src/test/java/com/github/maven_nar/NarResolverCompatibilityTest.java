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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class NarResolverCompatibilityTest {
  @Rule
  public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void readsMetadataFromTheResolvedArchiveOutsideTheRepository() throws Exception {
    final File archive = temporary.newFile("workspace artifact.nar");
    try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive.toPath()))) {
      jar.putNextEntry(new JarEntry("META-INF/nar/example/native-lib/nar.properties"));
      jar.write("nar.noarch=example:native-lib:nar:noarch\n".getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
    }
    final Artifact artifact = artifact(archive);
    final NarInfo info = new NarUnpackDependenciesMojo().getNarInfo(artifact);
    assertNotNull(info);
    assertArrayEquals(new String[] {"example:native-lib:nar:noarch"}, info.getAttachedNars(null, "noarch"));
    assertEquals("1.0-SNAPSHOT", artifact.getBaseVersion());
  }

  @Test
  public void retainsMetadataFromReactorClassesDirectories() throws Exception {
    final File classes = temporary.newFolder("reactor classes");
    final File metadata = new File(classes, "META-INF/nar/example/native-lib/nar.properties");
    assertTrue(metadata.getParentFile().mkdirs());
    Files.write(metadata.toPath(), "nar.noarch=example:native-lib:nar:noarch\n".getBytes(StandardCharsets.UTF_8));
    final NarInfo info = new NarUnpackDependenciesMojo().getNarInfo(artifact(classes));
    assertArrayEquals(new String[] {"example:native-lib:nar:noarch"}, info.getAttachedNars(null, "noarch"));
  }

  @Test
  public void attachmentCoordinatesDoNotInventARepositoryLocation() throws Exception {
    final AttachedNarArtifact attachment = new AttachedNarArtifact("example", "native-lib", "1.0-SNAPSHOT",
        Artifact.SCOPE_COMPILE, "nar", "noarch", false, temporary.newFile("parent.nar"));
    assertNull("Only artifact resolution can determine the attachment file", attachment.getFile());
    assertEquals("1.0-SNAPSHOT", attachment.getBaseVersion());
    assertEquals("noarch", attachment.getClassifier());
    assertEquals("nar", attachment.getArtifactHandler().getExtension());
  }

  private Artifact artifact(final File file) {
    final Artifact artifact = new DefaultArtifact("example", "native-lib", "1.0-SNAPSHOT", Artifact.SCOPE_COMPILE,
        "nar", null, new DefaultArtifactHandler("nar"));
    artifact.setFile(file);
    artifact.setResolved(true);
    return artifact;
  }
}
