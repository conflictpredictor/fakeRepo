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
package org.sonar.server.rule;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.picocontainer.Startable;
import org.sonar.api.resources.Languages;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.debt.DebtRemediationFunction;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.api.utils.log.Profiler;
import org.sonar.core.util.stream.Collectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.qualityprofile.ActiveRuleDto;
import org.sonar.db.qualityprofile.ActiveRuleParamDto;
import org.sonar.db.rule.RuleDefinitionDto;
import org.sonar.db.rule.RuleDto.Format;
import org.sonar.db.rule.RuleParamDto;
import org.sonar.db.rule.RuleRepositoryDto;
import org.sonar.server.qualityprofile.ActiveRuleChange;
import org.sonar.server.qualityprofile.RuleActivator;
import org.sonar.server.qualityprofile.index.ActiveRuleIndexer;
import org.sonar.server.rule.index.RuleIndexer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;

/**
 * Register rules at server startup
 */
public class RegisterRules implements Startable {

  private static final Logger LOG = Loggers.get(RegisterRules.class);

  private final RuleDefinitionsLoader defLoader;
  private final RuleActivator ruleActivator;
  private final DbClient dbClient;
  private final RuleIndexer ruleIndexer;
  private final ActiveRuleIndexer activeRuleIndexer;
  private final Languages languages;
  private final System2 system2;

  public RegisterRules(RuleDefinitionsLoader defLoader, RuleActivator ruleActivator, DbClient dbClient, RuleIndexer ruleIndexer,
    ActiveRuleIndexer activeRuleIndexer, Languages languages, System2 system2) {
    this.defLoader = defLoader;
    this.ruleActivator = ruleActivator;
    this.dbClient = dbClient;
    this.ruleIndexer = ruleIndexer;
    this.activeRuleIndexer = activeRuleIndexer;
    this.languages = languages;
    this.system2 = system2;
  }

  @Override
  public void start() {
    Profiler profiler = Profiler.create(LOG).startInfo("Register rules");
    DbSession session = dbClient.openSession(false);
    try {
      Map<RuleKey, RuleDefinitionDto> allRules = loadRules(session);

      RulesDefinition.Context context = defLoader.load();
      for (RulesDefinition.ExtendedRepository repoDef : getRepositories(context)) {
        if (languages.get(repoDef.language()) != null) {
          for (RulesDefinition.Rule ruleDef : repoDef.rules()) {
            registerRule(ruleDef, allRules, session);
          }
          session.commit();
        }
      }
      List<RuleDefinitionDto> activeRules = processRemainingDbRules(allRules.values(), session);
      List<ActiveRuleChange> changes = removeActiveRulesOnStillExistingRepositories(session, activeRules, context);
      session.commit();

      persistRepositories(session, context.repositories());
      ruleIndexer.index();
      activeRuleIndexer.index(changes);
      profiler.stopDebug();
    } finally {
      session.close();
    }
  }

  private void persistRepositories(DbSession dbSession, List<RulesDefinition.Repository> repositories) {
    dbClient.ruleRepositoryDao().truncate(dbSession);
    List<RuleRepositoryDto> dtos = repositories
      .stream()
      .map(r -> new RuleRepositoryDto(r.key(), r.language(), r.name()))
      .collect(Collectors.toList(repositories.size()));
    dbClient.ruleRepositoryDao().insert(dbSession, dtos);
    dbSession.commit();
  }

  @Override
  public void stop() {
    // nothing
  }

  private void registerRule(RulesDefinition.Rule ruleDef, Map<RuleKey, RuleDefinitionDto> allRules, DbSession session) {
    RuleKey ruleKey = RuleKey.of(ruleDef.repository().key(), ruleDef.key());

    RuleDefinitionDto existingRule = allRules.remove(ruleKey);
    RuleDefinitionDto rule = existingRule == null ? createRuleDto(ruleDef, session) : existingRule;

    boolean executeUpdate = false;
    if (mergeRule(ruleDef, rule)) {
      executeUpdate = true;
    }

    if (mergeDebtDefinitions(ruleDef, rule)) {
      executeUpdate = true;
    }

    if (mergeTags(ruleDef, rule)) {
      executeUpdate = true;
    }

    if (executeUpdate) {
      update(session, rule);
    }

    mergeParams(ruleDef, rule, session);
  }

  private Map<RuleKey, RuleDefinitionDto> loadRules(DbSession session) {
    Map<RuleKey, RuleDefinitionDto> rules = new HashMap<>();
    for (RuleDefinitionDto rule : dbClient.ruleDao().selectAllDefinitions(session)) {
      rules.put(rule.getKey(), rule);
    }
    return rules;
  }

  private List<RulesDefinition.ExtendedRepository> getRepositories(RulesDefinition.Context context) {
    List<RulesDefinition.ExtendedRepository> repositories = new ArrayList<>();
    for (RulesDefinition.Repository repoDef : context.repositories()) {
      repositories.add(repoDef);
    }
    for (RulesDefinition.ExtendedRepository extendedRepoDef : context.extendedRepositories()) {
      if (context.repository(extendedRepoDef.key()) == null) {
        LOG.warn(String.format("Extension is ignored, repository %s does not exist", extendedRepoDef.key()));
      } else {
        repositories.add(extendedRepoDef);
      }
    }
    return repositories;
  }

  private RuleDefinitionDto createRuleDto(RulesDefinition.Rule ruleDef, DbSession session) {
    RuleDefinitionDto ruleDto = new RuleDefinitionDto()
      .setRuleKey(RuleKey.of(ruleDef.repository().key(), ruleDef.key()))
      .setIsTemplate(ruleDef.template())
      .setConfigKey(ruleDef.internalKey())
      .setLanguage(ruleDef.repository().language())
      .setName(ruleDef.name())
      .setSeverity(ruleDef.severity())
      .setStatus(ruleDef.status())
      .setGapDescription(ruleDef.gapDescription())
      .setSystemTags(ruleDef.tags())
      .setType(RuleType.valueOf(ruleDef.type().name()))
      .setCreatedAt(system2.now())
      .setUpdatedAt(system2.now());
    if (ruleDef.htmlDescription() != null) {
      ruleDto.setDescription(ruleDef.htmlDescription());
      ruleDto.setDescriptionFormat(Format.HTML);
    } else {
      ruleDto.setDescription(ruleDef.markdownDescription());
      ruleDto.setDescriptionFormat(Format.MARKDOWN);
    }

    dbClient.ruleDao().insert(session, ruleDto);
    return ruleDto;
  }

  private boolean mergeRule(RulesDefinition.Rule def, RuleDefinitionDto dto) {
    boolean changed = false;
    if (!StringUtils.equals(dto.getName(), def.name())) {
      dto.setName(def.name());
      changed = true;
    }
    if (mergeDescription(def, dto)) {
      changed = true;
    }
    if (!StringUtils.equals(dto.getConfigKey(), def.internalKey())) {
      dto.setConfigKey(def.internalKey());
      changed = true;
    }
    String severity = def.severity();
    if (!ObjectUtils.equals(dto.getSeverityString(), severity)) {
      dto.setSeverity(severity);
      changed = true;
    }
    boolean isTemplate = def.template();
    if (isTemplate != dto.isTemplate()) {
      dto.setIsTemplate(isTemplate);
      changed = true;
    }
    if (def.status() != dto.getStatus()) {
      dto.setStatus(def.status());
      changed = true;
    }
    if (!StringUtils.equals(dto.getLanguage(), def.repository().language())) {
      dto.setLanguage(def.repository().language());
      changed = true;
    }
    RuleType type = RuleType.valueOf(def.type().name());
    if (!ObjectUtils.equals(dto.getType(), type.getDbConstant())) {
      dto.setType(type);
      changed = true;
    }
    return changed;
  }

  private boolean mergeDescription(RulesDefinition.Rule def, RuleDefinitionDto dto) {
    boolean changed = false;
    if (def.htmlDescription() != null && !StringUtils.equals(dto.getDescription(), def.htmlDescription())) {
      dto.setDescription(def.htmlDescription());
      dto.setDescriptionFormat(Format.HTML);
      changed = true;
    } else if (def.markdownDescription() != null && !StringUtils.equals(dto.getDescription(), def.markdownDescription())) {
      dto.setDescription(def.markdownDescription());
      dto.setDescriptionFormat(Format.MARKDOWN);
      changed = true;
    }
    return changed;
  }

  private boolean mergeDebtDefinitions(RulesDefinition.Rule def, RuleDefinitionDto dto) {
    // Debt definitions are set to null if the sub-characteristic and the remediation function are null
    DebtRemediationFunction debtRemediationFunction = def.debtRemediationFunction();
    boolean hasDebt = debtRemediationFunction != null;
    if (hasDebt) {
      return mergeDebtDefinitions(dto,
        debtRemediationFunction.type().name(),
        debtRemediationFunction.gapMultiplier(),
        debtRemediationFunction.baseEffort(),
        def.gapDescription());
    }
    return mergeDebtDefinitions(dto, null, null, null, null);
  }

  private boolean mergeDebtDefinitions(RuleDefinitionDto dto, @Nullable String remediationFunction,
    @Nullable String remediationCoefficient, @Nullable String remediationOffset, @Nullable String effortToFixDescription) {
    boolean changed = false;

    if (!StringUtils.equals(dto.getDefRemediationFunction(), remediationFunction)) {
      dto.setDefRemediationFunction(remediationFunction);
      changed = true;
    }
    if (!StringUtils.equals(dto.getDefRemediationGapMultiplier(), remediationCoefficient)) {
      dto.setDefRemediationGapMultiplier(remediationCoefficient);
      changed = true;
    }
    if (!StringUtils.equals(dto.getDefRemediationBaseEffort(), remediationOffset)) {
      dto.setDefRemediationBaseEffort(remediationOffset);
      changed = true;
    }
    if (!StringUtils.equals(dto.getGapDescription(), effortToFixDescription)) {
      dto.setGapDescription(effortToFixDescription);
      changed = true;
    }
    return changed;
  }

  private void mergeParams(RulesDefinition.Rule ruleDef, RuleDefinitionDto rule, DbSession session) {
    List<RuleParamDto> paramDtos = dbClient.ruleDao().selectRuleParamsByRuleKey(session, rule.getKey());
    Map<String, RuleParamDto> existingParamsByName = Maps.newHashMap();

    for (RuleParamDto paramDto : paramDtos) {
      RulesDefinition.Param paramDef = ruleDef.param(paramDto.getName());
      if (paramDef == null) {
        dbClient.activeRuleDao().deleteParamsByRuleParam(session, rule.getId(), paramDto.getName());
        dbClient.ruleDao().deleteRuleParam(session, paramDto.getId());
      } else {
        if (mergeParam(paramDto, paramDef)) {
          dbClient.ruleDao().updateRuleParam(session, rule, paramDto);
        }
        existingParamsByName.put(paramDto.getName(), paramDto);
      }
    }

    // Create newly parameters
    for (RulesDefinition.Param param : ruleDef.params()) {
      RuleParamDto paramDto = existingParamsByName.get(param.key());
      if (paramDto != null) {
        continue;
      }
      paramDto = RuleParamDto.createFor(rule)
        .setName(param.key())
        .setDescription(param.description())
        .setDefaultValue(param.defaultValue())
        .setType(param.type().toString());
      dbClient.ruleDao().insertRuleParam(session, rule, paramDto);
      if (StringUtils.isEmpty(param.defaultValue())) {
        continue;
      }
      // Propagate the default value to existing active rule parameters
      for (ActiveRuleDto activeRule : dbClient.activeRuleDao().selectByRuleId(session, rule.getId())) {
        ActiveRuleParamDto activeParam = ActiveRuleParamDto.createFor(paramDto).setValue(param.defaultValue());
        dbClient.activeRuleDao().insertParam(session, activeRule, activeParam);
      }
    }
  }

  private boolean mergeParam(RuleParamDto paramDto, RulesDefinition.Param paramDef) {
    boolean changed = false;
    if (!StringUtils.equals(paramDto.getType(), paramDef.type().toString())) {
      paramDto.setType(paramDef.type().toString());
      changed = true;
    }
    if (!StringUtils.equals(paramDto.getDefaultValue(), paramDef.defaultValue())) {
      paramDto.setDefaultValue(paramDef.defaultValue());
      changed = true;
    }
    if (!StringUtils.equals(paramDto.getDescription(), paramDef.description())) {
      paramDto.setDescription(paramDef.description());
      changed = true;
    }
    return changed;
  }

  private static boolean mergeTags(RulesDefinition.Rule ruleDef, RuleDefinitionDto dto) {
    boolean changed = false;

    if (RuleStatus.REMOVED == ruleDef.status()) {
      dto.setSystemTags(Collections.emptySet());
      changed = true;
    } else if (dto.getSystemTags().size() != ruleDef.tags().size() ||
      !dto.getSystemTags().containsAll(ruleDef.tags())) {
      dto.setSystemTags(ruleDef.tags());
      // FIXME this can't be implemented easily with organization support: remove end-user tags that are now declared as system
      // RuleTagHelper.applyTags(dto, ImmutableSet.copyOf(dto.getTags()));
      changed = true;
    }
    return changed;
  }

  private List<RuleDefinitionDto> processRemainingDbRules(Collection<RuleDefinitionDto> existingRules, DbSession session) {
    // custom rules check status of template, so they must be processed at the end
    List<RuleDefinitionDto> customRules = newArrayList();
    List<RuleDefinitionDto> removedRules = newArrayList();

    for (RuleDefinitionDto rule : existingRules) {
      if (rule.getTemplateId() != null) {
        customRules.add(rule);
      } else if (rule.getStatus() != RuleStatus.REMOVED) {
        removeRule(session, removedRules, rule);
      }
    }

    for (RuleDefinitionDto customRule : customRules) {
      Integer templateId = customRule.getTemplateId();
      checkNotNull(templateId, "Template id of the custom rule '%s' is null", customRule);
      Optional<RuleDefinitionDto> template = dbClient.ruleDao().selectDefinitionById(templateId, session);
      if (template.isPresent() && template.get().getStatus() != RuleStatus.REMOVED) {
        if (updateCustomRuleFromTemplateRule(customRule, template.get())) {
          update(session, customRule);
        }
      } else {
        removeRule(session, removedRules, customRule);
      }
    }

    session.commit();
    return removedRules;
  }

  private void removeRule(DbSession session, List<RuleDefinitionDto> removedRules, RuleDefinitionDto rule) {
    LOG.info(String.format("Disable rule %s", rule.getKey()));
    rule.setStatus(RuleStatus.REMOVED);
    rule.setSystemTags(Collections.emptySet());
    update(session, rule);
    // FIXME resetting the tags for all organizations must be handled a different way
//    rule.setTags(Collections.emptySet());
//    update(session, rule.getMetadata());
    removedRules.add(rule);
    if (removedRules.size() % 100 == 0) {
      session.commit();
    }
  }

  private static boolean updateCustomRuleFromTemplateRule(RuleDefinitionDto customRule, RuleDefinitionDto templateRule) {
    boolean changed = false;
    if (!StringUtils.equals(customRule.getLanguage(), templateRule.getLanguage())) {
      customRule.setLanguage(templateRule.getLanguage());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getConfigKey(), templateRule.getConfigKey())) {
      customRule.setConfigKey(templateRule.getConfigKey());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getDefRemediationFunction(), templateRule.getDefRemediationFunction())) {
      customRule.setDefRemediationFunction(templateRule.getDefRemediationFunction());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getDefRemediationGapMultiplier(), templateRule.getDefRemediationGapMultiplier())) {
      customRule.setDefRemediationGapMultiplier(templateRule.getDefRemediationGapMultiplier());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getDefRemediationBaseEffort(), templateRule.getDefRemediationBaseEffort())) {
      customRule.setDefRemediationBaseEffort(templateRule.getDefRemediationBaseEffort());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getGapDescription(), templateRule.getGapDescription())) {
      customRule.setGapDescription(templateRule.getGapDescription());
      changed = true;
    }
    if (customRule.getStatus() != templateRule.getStatus()) {
      customRule.setStatus(templateRule.getStatus());
      changed = true;
    }
    if (!StringUtils.equals(customRule.getSeverityString(), templateRule.getSeverityString())) {
      customRule.setSeverity(templateRule.getSeverityString());
      changed = true;
    }
    return changed;
  }

  /**
   * SONAR-4642
   * <p/>
   * Remove active rules on repositories that still exists.
   * <p/>
   * For instance, if the javascript repository do not provide anymore some rules, active rules related to this rules will be removed.
   * But if the javascript repository do not exists anymore, then related active rules will not be removed.
   * <p/>
   * The side effect of this approach is that extended repositories will not be managed the same way.
   * If an extended repository do not exists anymore, then related active rules will be removed.
   */
  private List<ActiveRuleChange> removeActiveRulesOnStillExistingRepositories(DbSession session, Collection<RuleDefinitionDto> removedRules, RulesDefinition.Context context) {
    List<String> repositoryKeys = newArrayList(Iterables.transform(context.repositories(), RulesDefinition.Repository::key));

    List<ActiveRuleChange> changes = new ArrayList<>();
    for (RuleDefinitionDto rule : removedRules) {
      // SONAR-4642 Remove active rules only when repository still exists
      if (repositoryKeys.contains(rule.getRepositoryKey())) {
        changes.addAll(ruleActivator.deactivate(session, rule));
      }
    }
    return changes;
  }

  private void update(DbSession session, RuleDefinitionDto rule) {
    rule.setUpdatedAt(system2.now());
    dbClient.ruleDao().update(session, rule);
  }

}
