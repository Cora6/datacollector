/*
 * Copyright 2018 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.antennadoctor.engine;

import com.google.common.collect.ImmutableList;
import com.streamsets.datacollector.antennadoctor.bean.AntennaDoctorRuleBean;
import com.streamsets.datacollector.antennadoctor.engine.context.AntennaDoctorContext;
import com.streamsets.datacollector.antennadoctor.engine.context.AntennaDoctorStageContext;
import com.streamsets.datacollector.antennadoctor.engine.el.AntennaDoctorELDefinitionExtractor;
import com.streamsets.datacollector.antennadoctor.engine.el.SdcEL;
import com.streamsets.datacollector.antennadoctor.engine.el.StageConfigurationEL;
import com.streamsets.datacollector.antennadoctor.engine.el.StageDefinitionEL;
import com.streamsets.datacollector.antennadoctor.engine.el.StageIssueEL;
import com.streamsets.datacollector.antennadoctor.engine.el.VarEL;
import com.streamsets.datacollector.antennadoctor.engine.el.VersionEL;
import com.streamsets.datacollector.el.ELEvaluator;
import com.streamsets.datacollector.util.Version;
import com.streamsets.pipeline.api.AntennaDoctorMessage;
import com.streamsets.pipeline.api.ErrorCode;
import com.streamsets.pipeline.api.el.ELEval;
import com.streamsets.pipeline.api.el.ELEvalException;
import com.streamsets.pipeline.api.el.ELVars;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Main computing engine for Antenna Doctor.
 */
public class AntennaDoctorEngine {
  private static final Logger LOG = LoggerFactory.getLogger(AntennaDoctorEngine.class);

  /**
   * Rules that will be used to classify issues.
   */
  private final List<RuntimeRule> rules;

  /**
   * Evaluation engine for stages issues.
   */
  private final ELEval stageEval;

  public AntennaDoctorEngine(AntennaDoctorContext context,List<AntennaDoctorRuleBean> rules) {
    ImmutableList.Builder<RuntimeRule> builder = ImmutableList.builder();

    // Evaluation for precondition, we guarantee that we execute the list in order and if any older checks fail
    // we never execute the later ones. E.g. one can do dependency by checking version in first precondition and
    // using that version specific EL later on.
    ELEval preconditionEval = new ELEvaluator(
      null,
      AntennaDoctorELDefinitionExtractor.get(),
      SdcEL.class,
      VersionEL.class
    );

    for(AntennaDoctorRuleBean ruleBean : rules) {
      LOG.trace("Loading rule {}", ruleBean.getUuid());

      // We're running in SDC and currently only in STAGE 'mode', other modes will be added later
      if(ruleBean.getEntity() != AntennaDoctorRuleBean.Entity.STAGE) {
        continue;
      }

      // Evaluate preconditions
      ELVars vars = preconditionEval.createVariables();
      SdcEL.setVars(vars, context);
      VarEL.resetVars(vars);
      VersionEL.setVars(vars, context.getBuildInfo());
      for(String precondition: ruleBean.getPreconditions()) {
        try {
          LOG.trace("Evaluating precondition: {}", precondition);
          if (!preconditionEval.eval(vars, "${" + precondition + "}", Boolean.class)) {
            LOG.trace("Precondition {} failed, skipping rule {}", precondition, ruleBean.getUuid());
            continue;
          }
        } catch (Throwable e ) {
          LOG.error("Precondition {} failed, skipping rule {}: {}", precondition, ruleBean.getUuid(), e.toString(), e);
          continue;
        }
      }

      // All checks passed, so we will accept this rule
      builder.add(new RuntimeRule(ruleBean));
    }

    this.rules = builder.build();
    LOG.info("Loaded new Antenna Doctor engine with {} rules", this.rules.size());

    this.stageEval = new ELEvaluator(
        null,
        AntennaDoctorELDefinitionExtractor.get(),
        StageConfigurationEL.class,
        StageDefinitionEL.class,
        StageIssueEL.class
    );

  }

  public List<AntennaDoctorMessage> onStage(AntennaDoctorStageContext context, Exception exception) {
    ELVars vars = stageEval.createVariables();
    StageIssueEL.setVars(vars, exception);
    return onStage(context, vars);
  }

  public List<AntennaDoctorMessage> onStage(AntennaDoctorStageContext context, ErrorCode errorCode, Object... args) {
    ELVars vars = stageEval.createVariables();
    StageIssueEL.setVars(vars, errorCode, args);
    return onStage(context, vars);
  }

  public List<AntennaDoctorMessage> onStage(AntennaDoctorStageContext context, String errorMessage) {
    ELVars vars = stageEval.createVariables();
    StageIssueEL.setVars(vars, errorMessage);
    return onStage(context, vars);
  }

  private List<AntennaDoctorMessage> onStage(AntennaDoctorStageContext context, ELVars vars) {
    ImmutableList.Builder<AntennaDoctorMessage> builder = ImmutableList.builder();
    StageConfigurationEL.setVars(vars, context.getStageConfiguration());
    StageDefinitionEL.setVars(vars, context.getStageDefinition());

    // Iterate over rules and try to match them
    for(RuntimeRule rule : this.rules) {
      // Static check to execute only relevant rules
      if(rule.getEntity() != AntennaDoctorRuleBean.Entity.STAGE) {
        continue;
      }

      VarEL.resetVars(vars);

      // Firstly evaluate conditions
      boolean matched = true;
      for (String condition : rule.getConditions()) {
        LOG.trace("Evaluating rule {} condition {}", rule.getUuid(), condition);
        try {
          if (!stageEval.eval(vars, condition, Boolean.class)) {
            matched = false;
            break;
          }
        } catch (ELEvalException e) {
          matched = false;
          LOG.error("Failed to evaluate rule {} condition {}: {}", rule.getUuid(), condition, e.toString(), e);
          break;
        }
      }

      // If all rules succeeded, evaluate message
      if (matched) {
        LOG.trace("Rule {} matched!", rule.getUuid());
        try {
          String summary = stageEval.eval(vars, rule.getMessage().getSummary(), String.class);
          String description = stageEval.eval(vars, rule.getMessage().getDescription(), String.class);

          builder.add(new AntennaDoctorMessage(summary, description));
        } catch (ELEvalException e) {
          LOG.error("Failed to evaluate message for rule {}: {}", rule.getUuid(), e.toString(), e);
        }
      } else {
        LOG.trace("Rule {} did not match", rule.getUuid());
      }
    }

    return builder.build();
  }
}
