/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Preconditions;
import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.store.PipelineInfo;
import com.streamsets.pipeline.validation.Issues;
import com.streamsets.pipeline.validation.PipelineConfigurationValidator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PipelineConfiguration {
  private int schemaVersion;
  private UUID uuid = null;
  private PipelineInfo info;
  private String description;
  private List<ConfigConfiguration> configuration;
  private final Map<String, Object> uiInfo;
  private List<StageConfiguration> stages;
  private StageConfiguration errorStage;
  private Issues issues;
  private boolean previewable;
  private final Map<String, Object> constants;

  @SuppressWarnings("unchecked")
  public PipelineConfiguration(int schemaVersion, UUID uuid, List<ConfigConfiguration> configuration,
      Map<String, Object> uiInfo, List<StageConfiguration> stages, StageConfiguration errorStage,
      Map<String, Object> constants) {
    this.schemaVersion = schemaVersion;
    this.uuid = Preconditions.checkNotNull(uuid, "uuid cannot be null");
    this.configuration = configuration;
    this.uiInfo = uiInfo;
    this.stages = (stages != null) ? stages : Collections.<StageConfiguration>emptyList();
    this.errorStage = errorStage;
    issues = new Issues();
    this.constants = constants;
  }

  public void setInfo(PipelineInfo info) {
    //NOP, just for jackson
  }

  @JsonIgnore
  public void setPipelineInfo(PipelineInfo info) {
    this.info = info;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getDescription() {
    return description;
  }

  public PipelineInfo getInfo() {
    return info;
  }

  public List<StageConfiguration> getStages() {
    return stages;
  }

  public void setStages(List<StageConfiguration> stages) {
    this.stages = stages;
  }

  public void setErrorStage(StageConfiguration errorStage) {
    this.errorStage = errorStage;
  }

  public StageConfiguration getErrorStage() {
    return this.errorStage;
  }

  public void setUuid(UUID uuid) {
    this.uuid = uuid;
  }

  public UUID getUuid() {
    return uuid;
  }

  public void setIssues(Issues issues) {
    //NOP, just for jackson
  }

  public void setValidation(PipelineConfigurationValidator validation) {
    issues = validation.getIssues();
    previewable = validation.canPreview();
  }

  public Issues getIssues() {
    return issues;
  }

  public void setValid(boolean dummy) {
    //NOP, just for jackson
  }
  public void setPreviewable(boolean dummy) {
    //NOP, just for jackson
  }

  public boolean isValid() {
    return (issues != null) && !issues.hasIssues();
  }

  public boolean isPreviewable() {
    return (issues !=null) && previewable;
  }

  public List<ConfigConfiguration> getConfiguration() {
    return configuration;
  }

  public Map<String, Object> getUiInfo() {
    return uiInfo;
  }

  public Map<String, Object> getConstants() {
    return constants;
  }

  @Override
  public String toString() {
    return Utils.format("PipelineConfiguration[uuid='{}' valid='{}' previewable='{}' configuration='{}']", getUuid(),
                        isValid(), isPreviewable(), getConfiguration());
  }

}
