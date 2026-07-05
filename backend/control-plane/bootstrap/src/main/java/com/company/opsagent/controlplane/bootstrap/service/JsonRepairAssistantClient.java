package com.company.opsagent.controlplane.bootstrap.service;

import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantRequest;
import com.company.opsagent.contracts.toolcenter.JsonRepairAssistantResponse;

public interface JsonRepairAssistantClient {

  JsonRepairAssistantResponse repair(JsonRepairAssistantRequest request);
}
