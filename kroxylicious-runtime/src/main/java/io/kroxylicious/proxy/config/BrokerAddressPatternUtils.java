/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.Nullable;

final class BrokerAddressPatternUtils {
    static final String LITERAL_NODE_ID = "$(nodeId)";
    static final String LITERAL_VIRTUAL_CLUSTER_NAME = "$(virtualClusterName)";
    static final String LITERAL_UNRESOLVED_ROUTE_HOST = "$(unresolvedRouteHost)";
    static final String LITERAL_RACK_ID = "$(rackId)";
    private static final String RACK_ID_TOKEN_PREFIX = "$(rackId:";
    private static final Pattern LITERAL_NODE_ID_PATTERN = Pattern.compile(Pattern.quote(LITERAL_NODE_ID));
    private static final Pattern PORT_SPECIFIER_RE = Pattern.compile("^(.*):([1-9]\\d*)$");
    private static final Pattern TOKEN_RE = Pattern.compile("(\\$\\([^)]+\\))");

    private BrokerAddressPatternUtils() {
    }

    private static Set<String> extractTokens(String stringWithTokens) {
        var tokenMatcher = TOKEN_RE.matcher(stringWithTokens);
        var tokens = new TreeSet<String>();
        while (tokenMatcher.find()) {
            var token = tokenMatcher.group(1);
            tokens.add(token);
        }
        return tokens;

    }

    static void validateStringContainsOnlyExpectedTokens(String stringWithPossibleTokens,
                                                         Set<String> allowedTokens,
                                                         Consumer<String> disallowedTokenConsumer) {
        Objects.requireNonNull(stringWithPossibleTokens);
        Objects.requireNonNull(allowedTokens);
        Objects.requireNonNull(disallowedTokenConsumer);
        var tokens = extractTokens(stringWithPossibleTokens);
        var rackIdAllowed = allowedTokens.contains(LITERAL_RACK_ID);
        tokens.removeIf(token -> allowedTokens.contains(token) || (rackIdAllowed && isRackIdToken(token)));
        tokens.forEach(disallowedTokenConsumer);
    }

    static void validateStringContainsRequiredTokens(String stringWithTokens,
                                                     Set<String> requiredTokens,
                                                     Consumer<String> requiredTokenAbsentConsumer) {
        Objects.requireNonNull(stringWithTokens);
        Objects.requireNonNull(requiredTokens);
        Objects.requireNonNull(requiredTokenAbsentConsumer);

        var tokens = extractTokens(stringWithTokens);
        var requiredTokensCopy = new TreeSet<>(requiredTokens);
        requiredTokensCopy.removeAll(tokens);

        if (!requiredTokensCopy.isEmpty()) {
            requiredTokensCopy.forEach(requiredTokenAbsentConsumer);
        }
    }

    static void validatePortSpecifier(String address, Consumer<String> portNumber) {
        var portMatcher = PORT_SPECIFIER_RE.matcher(address);
        if (portMatcher.matches()) {
            portNumber.accept(portMatcher.group(2));
        }
    }

    /**
     * Parse a string into an addressPattern and port. For example the string abcdef:8080
     * will be parsed into PatternAndPort("abcdef", Optional.of(8080)). The addressPattern
     * part is not validated, it is the raw string that comes before the port specified.
     * @param address string to parse
     * @return a PatternAndPort
     */
    static PatternAndPort parse(String address) {
        Objects.requireNonNull(address);
        var portMatcher = PORT_SPECIFIER_RE.matcher(address);
        if (portMatcher.matches()) {
            return new PatternAndPort(portMatcher.group(1), Optional.of(Integer.parseInt(portMatcher.group(2))));
        }
        else {
            return new PatternAndPort(address, Optional.empty());
        }
    }

    static String replaceVirtualClusterName(String toReplace, String clusterName) {
        return toReplace.replaceAll(Pattern.quote(LITERAL_VIRTUAL_CLUSTER_NAME), Matcher.quoteReplacement(clusterName));
    }

    /**
     * Represents a pattern string and optional port.
     * @param addressPattern address pattern, must not be null
     * @param port optional port, must not be null
     */
    record PatternAndPort(String addressPattern, Optional<Integer> port) {
        PatternAndPort {
            Objects.requireNonNull(addressPattern);
            Objects.requireNonNull(port);
            if (addressPattern.isEmpty()) {
                throw new IllegalArgumentException("pattern is empty");
            }
        }
    }

    static String replaceLiteralNodeId(String brokerAddressPattern, int nodeId) {
        return LITERAL_NODE_ID_PATTERN.matcher(brokerAddressPattern).replaceAll(Integer.toString(nodeId));
    }

    static boolean containsRackIdToken(String brokerAddressPattern) {
        return extractTokens(brokerAddressPattern).stream().anyMatch(BrokerAddressPatternUtils::isRackIdToken);
    }

    static String replaceRackId(String brokerAddressPattern, @Nullable String rawRackId, Map<String, String> rackIdMappings) {
        Objects.requireNonNull(brokerAddressPattern);
        Objects.requireNonNull(rackIdMappings);
        var matcher = TOKEN_RE.matcher(brokerAddressPattern);
        var result = new StringBuilder();
        while (matcher.find()) {
            var token = matcher.group(1);
            if (isRackIdToken(token)) {
                var replacement = replacementRackId(rawRackId, defaultRackId(token), rackIdMappings);
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replacementRackId(@Nullable String rawRackId, @Nullable String defaultRackId, Map<String, String> rackIdMappings) {
        if (rawRackId != null && !rawRackId.isBlank()) {
            return rackIdMappings.getOrDefault(rawRackId, rawRackId);
        }
        if (defaultRackId != null && !defaultRackId.isBlank()) {
            return defaultRackId;
        }
        throw new IllegalArgumentException("broker address pattern contains $(rackId) but broker rackId is not available and no default is configured");
    }

    static String createNodeIdCapturingRegexPattern(String brokerAddressPattern, String virtualClusterName) {
        String clusterReplaced = replaceVirtualClusterName(brokerAddressPattern, virtualClusterName);
        if (!containsRackIdToken(clusterReplaced)) {
            // the -1 split limit produces an empty final item in the array if the node id replacement is at the end of the string
            var partsQuoted = Arrays.stream(clusterReplaced.split(Pattern.quote(LITERAL_NODE_ID), -1)).map(Pattern::quote);
            return partsQuoted.collect(Collectors.joining("(\\d+)"));
        }
        return createNodeIdCapturingRegexPatternWithRackId(clusterReplaced);
    }

    private static String createNodeIdCapturingRegexPatternWithRackId(String brokerAddressPattern) {
        var matcher = TOKEN_RE.matcher(brokerAddressPattern);
        var pattern = new StringBuilder();
        var previousEnd = 0;
        while (matcher.find()) {
            pattern.append(Pattern.quote(brokerAddressPattern.substring(previousEnd, matcher.start())));
            var token = matcher.group(1);
            if (LITERAL_NODE_ID.equals(token)) {
                pattern.append("(\\d+)");
            }
            else if (isRackIdToken(token)) {
                pattern.append("(?:[^.]+)");
            }
            else {
                pattern.append(Pattern.quote(token));
            }
            previousEnd = matcher.end();
        }
        pattern.append(Pattern.quote(brokerAddressPattern.substring(previousEnd)));
        return pattern.toString();
    }

    private static boolean isRackIdToken(String token) {
        if (LITERAL_RACK_ID.equals(token)) {
            return true;
        }
        return token.startsWith(RACK_ID_TOKEN_PREFIX) && token.endsWith(")") && token.length() > RACK_ID_TOKEN_PREFIX.length() + 1
                && isValidRackIdDefault(defaultRackId(token));
    }

    private static @Nullable String defaultRackId(String token) {
        if (LITERAL_RACK_ID.equals(token)) {
            return null;
        }
        return token.substring(RACK_ID_TOKEN_PREFIX.length(), token.length() - 1);
    }

    private static boolean isValidRackIdDefault(@Nullable String rackIdDefault) {
        if (rackIdDefault == null || rackIdDefault.isBlank()) {
            return false;
        }
        for (int i = 0; i < rackIdDefault.length(); i++) {
            var ch = rackIdDefault.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-')) {
                return false;
            }
        }
        return true;
    }

}
