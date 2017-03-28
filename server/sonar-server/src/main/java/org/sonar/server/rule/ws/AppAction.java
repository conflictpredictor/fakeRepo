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

import org.sonar.api.resources.Language;
import org.sonar.api.resources.Languages;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.OrganizationPermission;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.user.UserSession;

import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_ORGANIZATION;

public class AppAction implements RulesWsAction {

  private final Languages languages;
  private final DbClient dbClient;
  private final UserSession userSession;
  private final RuleWsSupport wsSupport;
  private final DefaultOrganizationProvider defaultOrganizationProvider;

  public AppAction(Languages languages, DbClient dbClient, UserSession userSession, RuleWsSupport wsSupport,
    DefaultOrganizationProvider defaultOrganizationProvider) {
    this.languages = languages;
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.wsSupport = wsSupport;
    this.defaultOrganizationProvider = defaultOrganizationProvider;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction("app")
      .setDescription("Get data required for rendering the page 'Coding Rules'.")
      .setResponseExample(getClass().getResource("app-example.json"))
      .setSince("4.5")
      .setInternal(true)
      .setHandler(this);

    action.createParam(PARAM_ORGANIZATION)
      .setDescription("Organization key")
      .setRequired(false)
      .setInternal(true)
      .setSince("6.4")
      .setExampleValue("my-org");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    try (DbSession dbSession = dbClient.openSession(false)) {
      OrganizationDto organization = wsSupport.getOrganizationByKey(dbSession, request.param(PARAM_ORGANIZATION));

      JsonWriter json = response.newJsonWriter();
      json.beginObject();
      addPermissions(organization, json);
      addProfiles(dbSession, organization, json);
      addLanguages(json);
      addRuleRepositories(json, dbSession);
      json.endObject().close();
    }
  }

  private void addPermissions(OrganizationDto organization, JsonWriter json) {
    boolean isAdminister = userSession.hasPermission(OrganizationPermission.ADMINISTER_QUALITY_PROFILES, organization);
    json.prop("canWrite", isAdminister);
    json.prop("canCustomizeRule", isAdminister && organization.getUuid().equals(defaultOrganizationProvider.get().getUuid()));
    json.prop("canCreateCustomRule", isAdminister && organization.getUuid().equals(defaultOrganizationProvider.get().getUuid()));
  }

  private void addProfiles(DbSession dbSession, OrganizationDto organization, JsonWriter json) {
    json.name("qualityprofiles").beginArray();
    for (QualityProfileDto profile : dbClient.qualityProfileDao().selectAll(dbSession, organization)) {
      if (languageIsSupported(profile)) {
        json
          .beginObject()
          .prop("key", profile.getKey())
          .prop("name", profile.getName())
          .prop("lang", profile.getLanguage())
          .prop("parentKey", profile.getParentKee())
          .endObject();
      }
    }
    json.endArray();
  }

  private boolean languageIsSupported(QualityProfileDto profile) {
    return languages.get(profile.getLanguage()) != null;
  }

  private void addLanguages(JsonWriter json) {
    json.name("languages").beginObject();
    for (Language language : languages.all()) {
      json.prop(language.getKey(), language.getName());
    }
    json.endObject();
  }

  private void addRuleRepositories(JsonWriter json, DbSession dbSession) {
    json.name("repositories").beginArray();
    dbClient.ruleRepositoryDao()
      .selectAll(dbSession)
      .forEach(r -> json.beginObject()
        .prop("key", r.getKey())
        .prop("name", r.getName())
        .prop("language", r.getLanguage())
        .endObject());
    json.endArray();
  }
}