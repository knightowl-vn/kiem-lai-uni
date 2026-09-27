package com.universe.wiki.application.ports;

import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;

import java.util.List;
import java.util.UUID;

public interface WikiContributionWorkflowEventRepositoryPort {

    void save(WikiContributionWorkflowEvent event);

    List<WikiContributionWorkflowEvent> findByContributionId(UUID contributionId);
}
