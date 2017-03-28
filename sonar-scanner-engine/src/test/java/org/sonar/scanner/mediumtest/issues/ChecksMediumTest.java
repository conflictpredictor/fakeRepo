/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.mediumtest.issues;

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.rule.RuleKey;
import org.sonar.scanner.mediumtest.ScannerMediumTester;
import org.sonar.scanner.mediumtest.TaskResult;
import org.sonar.scanner.protocol.output.ScannerReport.Issue;
import org.sonar.scanner.rule.LoadedActiveRule;
import org.sonar.xoo.XooPlugin;
import org.sonar.xoo.rule.XooRulesDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class ChecksMediumTest {

  @org.junit.Rule
  public TemporaryFolder temp = new TemporaryFolder();

  public ScannerMediumTester tester = ScannerMediumTester.builder()
    .registerPlugin("xoo", new XooPlugin())
    .addRules(new XooRulesDefinition())
    .addDefaultQProfile("xoo", "Sonar Way")
    .addRule("TemplateRule_1234", "xoo", "TemplateRule_1234", "A template rule")
    .addRule("TemplateRule_1235", "xoo", "TemplateRule_1235", "Another template rule")
    .activateRule(createActiveRuleWithParam("xoo", "TemplateRule_1234", "TemplateRule", "A template rule", "MAJOR", null, "xoo", "line", "1"))
    .activateRule(createActiveRuleWithParam("xoo", "TemplateRule_1235", "TemplateRule", "Another template rule", "MAJOR", null, "xoo", "line", "2"))
    .build();

  @Before
  public void prepare() {
    tester.start();
  }

  @After
  public void stop() {
    tester.stop();
  }

  @Test
  public void testCheckWithTemplate() throws IOException {

    File baseDir = temp.getRoot();
    File srcDir = new File(baseDir, "src");
    srcDir.mkdir();

    File xooFile = new File(srcDir, "sample.xoo");
    FileUtils.write(xooFile, "foo\nbar");

    TaskResult result = tester.newTask()
      .properties(ImmutableMap.<String, String>builder()
        .put("sonar.task", "scan")
        .put("sonar.projectBaseDir", baseDir.getAbsolutePath())
        .put("sonar.projectKey", "com.foo.project")
        .put("sonar.projectName", "Foo Project")
        .put("sonar.projectVersion", "1.0-SNAPSHOT")
        .put("sonar.projectDescription", "Description of Foo Project")
        .put("sonar.sources", "src")
        .build())
      .start();

    List<Issue> issues = result.issuesFor(result.inputFile("src/sample.xoo"));
    assertThat(issues)
      .extracting("msg", "textRange.startLine")
      .containsOnly(
        tuple("A template rule", 1),
        tuple("Another template rule", 2));

  }

  private LoadedActiveRule createActiveRuleWithParam(String repositoryKey, String ruleKey, @Nullable String templateRuleKey, String name,
    @Nullable String severity, @Nullable String internalKey, @Nullable String languag, String paramKey, String paramValue) {
    LoadedActiveRule r = new LoadedActiveRule();

    r.setInternalKey(internalKey);
    r.setRuleKey(RuleKey.of(repositoryKey, ruleKey));
    r.setName(name);
    r.setTemplateRuleKey(templateRuleKey);
    r.setLanguage(languag);
    r.setSeverity(severity);

    Map<String, String> params = new HashMap<>();
    params.put(paramKey, paramValue);
    r.setParams(params);
    return r;
  }

}
