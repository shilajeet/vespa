// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.yahoo.config.application.api.DeploymentInstanceSpec;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.Endpoint;
import com.yahoo.config.application.api.ValidationId;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentSteps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This contains validators for a {@link ApplicationPackage} that depend on a {@link Controller} to perform validation.
 *
 * @author mpolden
 */
public class ApplicationPackageValidator {

    private final Controller controller;

    public ApplicationPackageValidator(Controller controller) {
        this.controller = Objects.requireNonNull(controller, "controller must be non-null");
    }

    /**
     * Validate the given application package
     *
     * @throws IllegalArgumentException if any validations fail
     */
    public void validate(Application application, ApplicationPackage applicationPackage, Instant instant) {
        validateSteps(applicationPackage.deploymentSpec());
        validateEndpointRegions(applicationPackage.deploymentSpec());
        validateEndpointChange(application, applicationPackage, instant);
    }

    /** Verify that each of the production zones listed in the deployment spec exist in this system */
    private void validateSteps(DeploymentSpec deploymentSpec) {
        new DeploymentSteps(deploymentSpec, controller::system).jobs();
        deploymentSpec.instances().stream().flatMap(instance -> instance.zones().stream())
                      .filter(zone -> zone.environment() == Environment.prod)
                      .forEach(zone -> {
                          if ( ! controller.zoneRegistry().hasZone(ZoneId.from(zone.environment(),
                                                                               zone.region().orElse(null)))) {
                              throw new IllegalArgumentException("Zone " + zone + " in deployment spec was not found in this system!");
                          }
                      });
    }

    /** Verify that no single endpoint contains regions in different clouds */
    private void validateEndpointRegions(DeploymentSpec deploymentSpec) {
        for (var instance : deploymentSpec.instances()) {
            for (var endpoint : instance.endpoints()) {
                var clouds = new HashSet<CloudName>();
                for (var region : endpoint.regions()) {
                    for (ZoneApi zone : controller.zoneRegistry().zones().all().in(region).zones()) {
                        clouds.add(zone.getCloudName());
                    }
                }
                if (clouds.size() != 1) {
                    throw new IllegalArgumentException("Endpoint '" + endpoint.endpointId() + "' in " + instance +
                                                       " cannot contain regions in different clouds: " +
                                                       endpoint.regions().stream().sorted().collect(Collectors.toList()));
                }
            }
        }
    }

    /** Verify endpoint configuration of given application package */
    private void validateEndpointChange(Application application, ApplicationPackage applicationPackage, Instant instant) {
        applicationPackage.deploymentSpec().instances().forEach(instance -> validateEndpointChange(application,
                                                                                                   instance.name(),
                                                                                                   applicationPackage,
                                                                                                   instant));
    }

    /** Verify changes to endpoint configuration by comparing given application package to the existing one, if any */
    private void validateEndpointChange(Application application, InstanceName instanceName, ApplicationPackage applicationPackage, Instant instant) {
        var validationId = ValidationId.globalEndpointChange;
        if (applicationPackage.validationOverrides().allows(validationId, instant)) return;

        var endpoints = application.deploymentSpec().instance(instanceName)
                                   .map(ApplicationPackageValidator::allEndpointsOf)
                                   .orElseGet(List::of);
        var newEndpoints = allEndpointsOf(applicationPackage.deploymentSpec().requireInstance(instanceName));

        if (newEndpoints.containsAll(endpoints)) return; // Adding new endpoints is fine
        if (containsAllRegions(newEndpoints, endpoints)) return; // Adding regions to endpoints is fine

        var removedEndpoints = new ArrayList<>(endpoints);
        removedEndpoints.removeAll(newEndpoints);
        newEndpoints.removeAll(endpoints);
        throw new IllegalArgumentException(validationId.value() + ": application '" + application.id() +
                                           (instanceName.isDefault() ? "" : "." + instanceName.value()) +
                                           "' has endpoints " + endpoints +
                                           ", but does not include all of these in deployment.xml. Deploying given " +
                                           "deployment.xml will remove " + removedEndpoints +
                                           (newEndpoints.isEmpty() ? "" : " and add " + newEndpoints) +
                                           ". " + ValidationOverrides.toAllowMessage(validationId));
    }

    /** Returns whether endpoint regions in newEndpoints contains all regions of corresponding endpoint in endpoints */
    private static boolean containsAllRegions(List<Endpoint> newEndpoints, List<Endpoint> endpoints) {
        var containsAllRegions = true;
        for (var endpoint : endpoints) {
            var endpointContainsAllRegions = false;
            for (var newEndpoint : newEndpoints) {
                if (endpoint.endpointId().equals(newEndpoint.endpointId())) {
                    endpointContainsAllRegions = newEndpoint.regions().containsAll(endpoint.regions());
                }
            }
            containsAllRegions &= endpointContainsAllRegions;
        }
        return containsAllRegions;
    }

    /** Returns all configued endpoints of given deployment instance spec */
    private static List<Endpoint> allEndpointsOf(DeploymentInstanceSpec deploymentInstanceSpec) {
        var endpoints = new ArrayList<>(deploymentInstanceSpec.endpoints());
        legacyEndpoint(deploymentInstanceSpec).ifPresent(endpoints::add);
        return endpoints;
    }

    /** Returns global service ID as a endpoint, if any global service ID is set */
    private static Optional<Endpoint> legacyEndpoint(DeploymentInstanceSpec instance) {
        return instance.globalServiceId().map(globalServiceId -> {
            var regions = instance.zones().stream()
                                  .filter(zone -> zone.environment().isProduction())
                                  .map(zone -> zone.region().get())
                                  .map(RegionName::value)
                                  .collect(Collectors.toSet());
            return new Endpoint(Optional.of(EndpointId.defaultId().id()), instance.globalServiceId().get(), regions);
        });
    }

}
