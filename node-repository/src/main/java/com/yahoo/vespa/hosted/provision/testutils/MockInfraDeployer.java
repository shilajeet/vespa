package com.yahoo.vespa.hosted.provision.testutils;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Deployment;
import com.yahoo.config.provision.InfraDeployer;

import java.util.Map;
import java.util.Optional;

public class MockInfraDeployer implements InfraDeployer {
    @Override
    public Optional<Deployment> getDeployment(ApplicationId application) {
        return Optional.empty();
    }

    @Override
    public Map<ApplicationId, Deployment> getSupportedInfraDeployments() {
        return Map.of();
    }
}
