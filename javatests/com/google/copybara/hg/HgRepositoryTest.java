/*
 * Copyright (C) 2018 Google Inc.
 *
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
 */

package com.google.copybara.hg;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.hg.HgRepository.HgLogEntry;
import com.google.copybara.util.CommandOutput;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HgRepositoryTest {

  private Path workDir;
  private HgRepository repository;

  @Before
  public void setup() throws Exception {
    workDir = Files.createTempDirectory("workdir");
    repository = new HgRepository(workDir);
  }

  @Test
  public void testInit() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "bar");
  }

  @Test
  public void testPullAll() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "bar");

    CommandOutput commandOutput = repository.hg(workDir, "log", "--template",
        "{rev}:{node}\n");
    List<String> before = Splitter.on("\n").splitToList(commandOutput.getStdout());
    List<String> revIds = Splitter.on(":").splitToList(before.get(0));

    HgRevision beforeRev = new HgRevision(revIds.get(1));

    Path remoteDir = Files.createTempDirectory("remotedir");
    HgRepository remoteRepo = new HgRepository(remoteDir);
    remoteRepo.init();
    Path newFile2 = Files.createTempFile(remoteDir, "bar", ".txt");
    String fileName2 = newFile2.toString();
    remoteRepo.hg(remoteDir, "add", fileName2);
    remoteRepo.hg(remoteDir, "commit", "-m", "foo");

    CommandOutput remoteCommandOutput = remoteRepo.hg(remoteDir, "log", "--template",
        "{rev}:{node}\n");
    List<String> remoteBefore = Splitter.on("\n").splitToList(remoteCommandOutput.getStdout());
    List<String> remoteRevIds = Splitter.on(":").splitToList(remoteBefore.get(0));

    HgRevision remoteRev = new HgRevision(remoteRevIds.get(1));

    repository.pullAll(remoteDir.toString());

    CommandOutput newCommandOutput = repository.hg(workDir, "log", "--template",
        "{rev}:{node}\n");
    List<String> afterRev = Splitter.on("\n").splitToList(newCommandOutput.getStdout().trim());
    assertThat(afterRev).hasSize(2);

    List<String> globalIds = new ArrayList<>();
    for (String rev : afterRev) {
      List<String> afterRevIds = Splitter.on(":").splitToList(rev);
      globalIds.add(afterRevIds.get(1));
    }

    assertThat(globalIds).hasSize(2);
    assertThat(globalIds.get(1)).isEqualTo(beforeRev.getGlobalId());
    assertThat(globalIds.get(0)).isEqualTo(remoteRev.getGlobalId());
  }

  @Test
  public void testPullFromRef() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "bar");

    Path remoteDir = Files.createTempDirectory("remotedir");
    HgRepository remoteRepo = new HgRepository(remoteDir);
    remoteRepo.init();
    Path newFile2 = Files.createTempFile(remoteDir, "bar", ".txt");
    String fileName2 = newFile2.toString();
    remoteRepo.hg(remoteDir, "add", fileName2);
    remoteRepo.hg(remoteDir, "commit", "-m", "foo");

    Path newFile3 = Files.createTempFile(remoteDir, "foobar", ".txt");
    String fileName3 = newFile3.toString();
    remoteRepo.hg(remoteDir, "add", fileName3);
    remoteRepo.hg(remoteDir, "commit", "-m", "foobar");

    Path newFile4 = Files.createTempFile(remoteDir, "barfoo", ".txt");
    String fileName4 = newFile4.toString();
    remoteRepo.hg(remoteDir, "add", fileName4);
    remoteRepo.hg(remoteDir, "commit", "-m", "barfoo");

    ImmutableList<HgLogEntry> remoteCommits = remoteRepo.log().run();

    repository.pullFromRef(remoteDir.toString(), remoteCommits.get(1).getGlobalId());

    ImmutableList<HgLogEntry> commits = repository.log().run();

    assertThat(commits).hasSize(3);
    assertThat(commits.get(0).getGlobalId()).isEqualTo(remoteCommits.get(1).getGlobalId());
  }

  @Test
  public void testPullInvalidPath() throws Exception {
    repository.init();
    String invalidPath = "/not/a/path";
    try {
      repository.pullAll(invalidPath);
      fail("Cannot pull from invalid path");
    }
    catch (ValidationException e) {
      assertThat(e).hasMessageThat().contains("Repository not found");
    }
  }

  @Test
  public void testPullInvalidRepo() throws Exception {
    repository.init();
    Path invalidRepo = Files.createTempDirectory("notRepo");
    try {
      repository.pullAll(invalidRepo.toString());
      fail("Cannot pull from invalid repository");
    }
    catch (ValidationException e) {
      assertThat(e)
          .hasMessageThat()
          .contains("Repository not found");
    }
  }

  @Test
  public void testLog() throws Exception {
    repository.init();

    String user = "Copy Bara <copy@bara.com>";
    ZonedDateTime date = ZonedDateTime.now(ZoneId.of("+11:00")).truncatedTo(ChronoUnit.SECONDS);
    ZonedDateTime date2 = date.plus(1, ChronoUnit.SECONDS);
    ZonedDateTime date3 = date.plus(2, ChronoUnit.SECONDS);
    String desc = "one";
    String desc2 = "two";
    String desc3 = "three\nthree";

    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-u", user, "-d", date.toString(), "-m", desc);

    repository.hg(workDir, "branch", "other");
    Files.write(workDir.resolve(fileName), "hello".getBytes(UTF_8));
    repository.hg(workDir, "commit", "-u", user, "-d", date2.toString(), "-m", desc2);

    Path remoteDir = Files.createTempDirectory("remotedir");
    HgRepository remoteRepo = new HgRepository(remoteDir);
    remoteRepo.init();
    Path newFile2 = Files.createTempFile(remoteDir, "bar", ".txt");
    String fileName2 = newFile2.toString();
    remoteRepo.hg(remoteDir, "add", fileName2);
    remoteRepo.hg(remoteDir, "commit", "-u", user, "-d", date3.toString(), "-m", desc3);

    repository.pullAll(remoteDir.toString());

    ImmutableList<HgLogEntry> allCommits = repository.log().run();

    assertThat(allCommits.size()).isEqualTo(3);

    assertThat(allCommits.get(0).getParents()).hasSize(1);
    assertThat(allCommits.get(0).getParents().get(0))
        .isEqualTo("0000000000000000000000000000000000000000");
    assertThat(allCommits.get(1).getParents().get(0))
        .isEqualTo(allCommits.get(2).getGlobalId());
    assertThat(allCommits.get(2).getParents().get(0))
        .isEqualTo("0000000000000000000000000000000000000000");


    assertThat(allCommits.get(0).getUser()).isEqualTo("Copy Bara <copy@bara.com>");
    assertThat(allCommits.get(0).getUser()).isEqualTo(allCommits.get(1).getUser());
    assertThat(allCommits.get(0).getUser()).isEqualTo(allCommits.get(2).getUser());

    assertThat(allCommits.get(0).getZonedDate()).isEqualTo(date3);
    assertThat(allCommits.get(1).getZonedDate()).isEqualTo(date2);
    assertThat(allCommits.get(2).getZonedDate()).isEqualTo(date);

    assertThat(allCommits.get(0).getBranch()).isEqualTo("default");
    assertThat(allCommits.get(1).getBranch()).isEqualTo("other");
    assertThat(allCommits.get(2).getBranch()).isEqualTo("default");

    assertThat(allCommits.get(0).getFiles()).containsExactly(newFile2.getFileName().toString());
    assertThat(allCommits.get(1).getFiles()).containsExactly(newFile.getFileName().toString());
    assertThat(allCommits.get(2).getFiles()).containsExactly(newFile.getFileName().toString());

    assertThat(allCommits.get(0).getDescription()).isEqualTo(desc3);
    assertThat(allCommits.get(1).getDescription()).isEqualTo(desc2);
    assertThat(allCommits.get(2).getDescription()).isEqualTo(desc);

    ImmutableList<HgLogEntry> defaultCommits = repository.log()
        .withLimit(5)
        .withBranch("default")
        .run();
    assertThat(defaultCommits).hasSize(2);

    ImmutableList<HgLogEntry> otherCommits = repository.log()
        .withLimit(5)
        .withBranch("other")
        .run();
    assertThat(otherCommits).hasSize(1);
  }

  @Test
  public void testLogTwoParents() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "foo");

    Path remoteDir = Files.createTempDirectory("remotedir");
    HgRepository remoteRepo = new HgRepository(remoteDir);
    remoteRepo.init();
    Path newFile2 = Files.createTempFile(remoteDir, "foo", ".txt");
    String fileName2 = newFile2.toString();
    Files.write(remoteDir.resolve(fileName2), "hello".getBytes(UTF_8));
    remoteRepo.hg(remoteDir, "add", fileName2);
    remoteRepo.hg(remoteDir, "commit", "-m", "hello");

    repository.pullAll(remoteDir.toString());
    repository.hg(workDir, "merge");
    Files.write(workDir.resolve(fileName), "hello".getBytes(UTF_8));
    repository.hg(workDir, "commit", "-m", "merge");

    ImmutableList<HgLogEntry> commits = repository.log().run();

    assertThat(commits.get(0).getParents()).hasSize(2);
  }

  @Test
  public void testLogNoFiles() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "foo");
    repository.hg(workDir, "rm", fileName);
    repository.hg(workDir, "commit", "--amend", "-m", "amend");

    ImmutableList<HgLogEntry> commits = repository.log().run();
    assertThat(commits.get(0).getFiles()).hasSize(0);
  }

  @Test
  public void testLogMultipleFiles() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    Path newFile2 = Files.createTempFile(workDir, "bar", ".txt");
    String fileName = newFile.toString();
    String fileName2 = newFile2.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "add", fileName2);
    repository.hg(workDir, "commit", "-m", "add 2 files");

    ImmutableList<HgLogEntry> commits = repository.log().run();
    assertThat(commits.get(0).getFiles()).hasSize(2);
  }

  @Test
  public void testLogLimit() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "foo");
    Files.write(workDir.resolve(fileName), "hello".getBytes(UTF_8));
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "hello");

    ImmutableList<HgLogEntry> commits = repository.log().withLimit(1).run();
    assertThat(commits).hasSize(1);

    try {
      repository.log().withLimit(0).run();
      fail("Cannot have limit of 0");
    }
    catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testCleanUpdate() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "foo");

    Path newFile2 = Files.createTempFile(workDir, "bar", ".txt");
    String fileName2 = newFile2.toString();
    repository.hg(workDir, "add", fileName2);
    repository.hg(workDir, "commit", "-m", "bar");

    Path newFile3 = Files.createTempFile(workDir, "foobar", ".txt");
    String fileName3 = newFile3.toString();
    repository.hg(workDir, "add", fileName3);

    ImmutableList<HgLogEntry> commits = repository.log().run();
    repository.cleanUpdate(commits.get(1).getGlobalId());

    assertThat(commits).hasSize(2);

    assertThat(Files.exists(newFile)).isTrue();
    assertThat(Files.notExists(newFile2)).isTrue();

    /*
    Hg does not delete untracked files on update. In practice, this is ok because there should
    be no untracked files as the workDir is deleted every time.
     */
    assertThat(Files.exists(newFile3)).isTrue();
  }

  @Test
  public void testIdentify() throws Exception {
    repository.init();
    Path newFile = Files.createTempFile(workDir, "foo", ".txt");
    String fileName = newFile.toString();
    repository.hg(workDir, "add", fileName);
    repository.hg(workDir, "commit", "-m", "foo");

    ImmutableList<HgLogEntry> commits = repository.log().run();
    String globalId = commits.get(0).getGlobalId();

    assertThat(repository.identify(globalId).getGlobalId()).isEqualTo(globalId);
    assertThat(repository.identify("tip").getGlobalId()).isEqualTo(globalId);
    assertThat(repository.identify(String.valueOf(0)).getGlobalId()).isEqualTo(globalId);
    assertThat(repository.identify("default").getGlobalId()).isEqualTo(globalId);

    try {
      repository.identify("not_a_branch");
    }
    catch (ValidationException expected) {
      assertThat(expected.getMessage()).isEqualTo("Reference not_a_branch is unknown");
    }
  }
}