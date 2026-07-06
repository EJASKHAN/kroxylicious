/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.net;

import java.util.Objects;

import io.kroxylicious.proxy.service.HostPort;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Upstream broker endpoint information learned from broker metadata.
 *
 * @param upstreamAddress upstream broker address
 * @param rackId broker rack id, if advertised by the upstream cluster
 */
public record BrokerEndpointMapping(HostPort upstreamAddress, @Nullable String rackId) {

    public BrokerEndpointMapping {
        Objects.requireNonNull(upstreamAddress);
    }

    public static BrokerEndpointMapping of(HostPort upstreamAddress) {
        return new BrokerEndpointMapping(upstreamAddress, null);
    }
}
