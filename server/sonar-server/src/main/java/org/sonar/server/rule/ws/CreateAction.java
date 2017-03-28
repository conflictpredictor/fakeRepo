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
package org.sonar.server.rule.ws;

import com.google.common.base.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.KeyValueFormat;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleParamDto;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.rule.NewCustomRule;
import org.sonar.server.rule.ReactivationException;
import org.sonar.server.rule.RuleCreator;
import org.sonarqube.ws.Rules;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.net.HttpURLConnection.HTTP_CONFLICT;
import static java.util.Collections.singletonList;
import static org.sonar.server.ws.WsUtils.writeProtobuf;

/**
 * @since 4.4
 */
public class CreateAction implements RulesWsAction {

  public static final String PARAM_CUSTOM_KEY = "custom_key";
  public static final String PARAM_NAME = "name";
  public static final String PARAM_DESCRIPTION = "markdown_description";
  public static final String PARAM_SEVERITY = "severity";
  public static final String PARAM_STATUS = "status";
  public static final String PARAM_TEMPLATE_KEY = "template_key";
  public static final String PARAMS = "params";

  public static final String PARAM_PREVENT_REACTIVATION = "prevent_reactivation";

  private final DbClient dbClient;
  private final RuleCreator ruleCreator;
  private final RuleMapper ruleMapper;
  private final DefaultOrganizationProvider defaultOrganizationProvider;

  public CreateAction(DbClient dbClient, RuleCreator ruleCreator, RuleMapper ruleMapper, DefaultOrganizationProvider defaultOrganizationProvider) {
    this.dbClient = dbClient;
    this.ruleCreator = ruleCreator;
    this.ruleMapper = ruleMapper;
    this.defaultOrganizationProvider = defaultOrganizationProvider;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller
      .createAction("create")
      .setDescription("Create a custom rule. <br/>" +
        "Since 5.5, it's no more possible to create manual rule.")
      .setSince("4.4")
      .setPost(true)
      .setHandler(this);

    action
      .createParam(PARAM_CUSTOM_KEY)
      .setDescription("Key of the custom rule")
      .setExampleValue("Todo_should_not_be_used")
      .setRequired(true);

    action
      .createParam("manual_key")
      .setDescription("Manual rules are no more supported. This parameter is ignored")
      .setExampleValue("Error_handling")
      .setDeprecatedSince("5.5");

    action
      .createParam(PARAM_TEMPLATE_KEY)
      .setDescription("Key of the template rule in order to create a custom rule (mandatory for custom rule)")
      .setExampleValue("java:XPath");

    action
      .createParam(PARAM_NAME)
      .setDescription("Rule name")
      .setRequired(true)
      .setExampleValue("My custom rule");

    action
      .createParam(PARAM_DESCRIPTION)
      .setDescription("Rule description")
      .setRequired(true)
      .setExampleValue("Description of my custom rule");

    action
      .createParam(PARAM_SEVERITY)
      .setDescription("Rule severity")
      .setPossibleValues(Severity.ALL);

    action
      .createParam(PARAM_STATUS)
      .setDescription("Rule status")
      .setDefaultValue(RuleStatus.READY)
      .setPossibleValues(RuleStatus.values());

    action.createParam(PARAMS)
      .setDescription("Parameters as semi-colon list of <key>=<value>, for example 'params=key1=v1;key2=v2' (Only for custom rule)");

    action
      .createParam(PARAM_PREVENT_REACTIVATION)
      .setDescription("If set to true and if the rule has been deactivated (status 'REMOVED'), a status 409 will be returned")
      .setDefaultValue(false)
      .setBooleanPossibleValues();
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String customKey = request.mandatoryParam(PARAM_CUSTOM_KEY);
    try (DbSession dbSession = dbClient.openSession(false)) {
      try {
        NewCustomRule newRule = NewCustomRule.createForCustomRule(customKey, RuleKey.parse(request.mandatoryParam(PARAM_TEMPLATE_KEY)))
          .setName(request.mandatoryParam(PARAM_NAME))
          .setMarkdownDescription(request.mandatoryParam(PARAM_DESCRIPTION))
          .setSeverity(request.mandatoryParam(PARAM_SEVERITY))
          .setStatus(RuleStatus.valueOf(request.mandatoryParam(PARAM_STATUS)))
          .setPreventReactivation(request.mandatoryParamAsBoolean(PARAM_PREVENT_REACTIVATION));
        String params = request.param(PARAMS);
        if (!isNullOrEmpty(params)) {
          newRule.setParameters(KeyValueFormat.parse(params));
        }
        writeResponse(dbSession, request, response, ruleCreator.create(dbSession, newRule));
      } catch (ReactivationException e) {
        write409(dbSession, request, response, e.ruleKey());
      }
    }
  }

  private void writeResponse(DbSession dbSession, Request request, Response response, RuleKey ruleKey) {
    writeProtobuf(createResponse(dbSession, ruleKey), request, response);
  }

  private void write409(DbSession dbSession, Request request, Response response, RuleKey ruleKey) {
    response.stream().setStatus(HTTP_CONFLICT);
    writeProtobuf(createResponse(dbSession, ruleKey), request, response);
  }

  private Rules.CreateResponse createResponse(DbSession dbSession, RuleKey ruleKey) {
    String defaultOrganizationUuid = defaultOrganizationProvider.get().getUuid();
    RuleDto rule = dbClient.ruleDao().selectOrFailByKey(dbSession, defaultOrganizationUuid, ruleKey);
    List<RuleDefinitionDto> templateRules = new ArrayList<>();
    if (rule.getTemplateId() != null) {
      Optional<RuleDefinitionDto> templateRule = dbClient.ruleDao().selectDefinitionById(rule.getTemplateId(), dbSession);
      if (templateRule.isPresent()) {
        templateRules.add(templateRule.get());
      }
    }
    List<RuleParamDto> ruleParameters = dbClient.ruleDao().selectRuleParamsByRuleIds(dbSession, singletonList(rule.getId()));
    SearchAction.SearchResult searchResult = new SearchAction.SearchResult()
      .setRules(singletonList(rule))
      .setRuleParameters(ruleParameters)
      .setTemplateRules(templateRules)
      .setTotal(1L);
    return Rules.CreateResponse.newBuilder()
      .setRule(ruleMapper.toWsRule(rule, searchResult, Collections.<String>emptySet()))
      .build();
  }
}
