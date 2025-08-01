/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.configure;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.graalvm.collections.EconomicMap;
import org.graalvm.nativeimage.ImageInfo;

import com.oracle.svm.util.LogUtils;

import jdk.graal.compiler.util.json.JsonParser;
import jdk.graal.compiler.util.json.JsonParserException;
import jdk.graal.compiler.util.json.JsonPrintable;
import jdk.graal.compiler.util.json.JsonPrinter;
import jdk.graal.compiler.util.json.JsonWriter;

public abstract class ConfigurationParser {
    public static InputStream openStream(URI uri) throws IOException {
        URL url = uri.toURL();
        if ("file".equals(url.getProtocol()) || "jar".equalsIgnoreCase(url.getProtocol()) ||
                        (ImageInfo.inImageRuntimeCode() && "resource".equals(url.getProtocol()))) {
            return url.openStream();
        }
        throw new IllegalArgumentException("For security reasons, reading configurations is not supported from URIs with protocol: " + url.getProtocol());
    }

    public static final String NAME_KEY = "name";
    public static final String TYPE_KEY = "type";
    public static final String PROXY_KEY = "proxy";
    public static final String LAMBDA_KEY = "lambda";
    public static final String DECLARING_CLASS_KEY = "declaringClass";
    public static final String DECLARING_METHOD_KEY = "declaringMethod";
    public static final String INTERFACES_KEY = "interfaces";
    public static final String PARAMETER_TYPES_KEY = "parameterTypes";
    public static final String REFLECTION_KEY = "reflection";
    public static final String JNI_KEY = "jni";
    public static final String FOREIGN_KEY = "foreign";
    public static final String SERIALIZATION_KEY = "serialization";
    public static final String RESOURCES_KEY = "resources";
    public static final String BUNDLES_KEY = "bundles";
    public static final String GLOBS_KEY = "globs";
    public static final String MODULE_KEY = "module";
    public static final String GLOB_KEY = "glob";
    public static final String BUNDLE_KEY = "bundle";
    private final Map<String, Set<String>> seenUnknownAttributesByType = new HashMap<>();
    private final EnumSet<ConfigurationParserOption> parserOptions;

    protected ConfigurationParser(EnumSet<ConfigurationParserOption> parserOptions) {
        this.parserOptions = parserOptions;
    }

    /**
     * Defines the options supported by this parser. A subclass can override this method to declare
     * additional supported options.
     */
    protected EnumSet<ConfigurationParserOption> supportedOptions() {
        return EnumSet.of(ConfigurationParserOption.STRICT_CONFIGURATION);
    }

    protected final boolean checkOption(ConfigurationParserOption option) {
        if (!supportedOptions().contains(option)) {
            throw new IllegalArgumentException(String.format("Tried to check option %s but it was not declared as a supported option", option));
        }
        return parserOptions.contains(option);
    }

    public void parseAndRegister(URI uri) throws IOException {
        try (Reader reader = openReader(uri)) {
            parseAndRegister(new JsonParser(reader).parse(), uri);
        } catch (FileNotFoundException e) {
            /*
             * Ignore: *-config.json files can be missing when reachability-metadata.json is
             * present, and vice-versa
             */
        }
    }

    protected static BufferedReader openReader(URI uri) throws IOException {
        return new BufferedReader(new InputStreamReader(openStream(uri)));
    }

    public void parseAndRegister(Reader reader) throws IOException {
        parseAndRegister(new JsonParser(reader).parse(), null);
    }

    public abstract void parseAndRegister(Object json, URI origin) throws IOException;

    public Object getFromGlobalFile(Object json, String key) {
        EconomicMap<String, Object> map = asMap(json, "top level of reachability metadata file must be an object");
        checkAttributes(map, "reachability metadata", Collections.emptyList(), List.of(REFLECTION_KEY, JNI_KEY, SERIALIZATION_KEY, RESOURCES_KEY, BUNDLES_KEY, FOREIGN_KEY, "reason", "comment"));
        return map.get(key);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object data, String errorMessage) {
        if (data instanceof List) {
            return (List<Object>) data;
        }
        throw new JsonParserException(errorMessage);
    }

    @SuppressWarnings("unchecked")
    public static EconomicMap<String, Object> asMap(Object data, String errorMessage) {
        if (data instanceof EconomicMap) {
            return (EconomicMap<String, Object>) data;
        }
        throw new JsonParserException(errorMessage);
    }

    protected void checkAttributes(EconomicMap<String, Object> map, String type, Collection<String> requiredAttrs, Collection<String> optionalAttrs) {
        Set<String> unseenRequired = new HashSet<>(requiredAttrs);
        for (String key : map.getKeys()) {
            unseenRequired.remove(key);
        }
        if (!unseenRequired.isEmpty()) {
            throw new JsonParserException("Missing attribute(s) [" + String.join(", ", unseenRequired) + "] in " + type);
        }
        Set<String> unknownAttributes = new HashSet<>();
        for (String key : map.getKeys()) {
            unknownAttributes.add(key);
        }
        unknownAttributes.removeAll(requiredAttrs);
        unknownAttributes.removeAll(optionalAttrs);

        if (seenUnknownAttributesByType.containsKey(type)) {
            unknownAttributes.removeAll(seenUnknownAttributesByType.get(type));
        }

        if (unknownAttributes.size() > 0) {
            String message = "Unknown attribute(s) [" + String.join(", ", unknownAttributes) + "] in " + type;
            warnOrFailOnSchemaError(message);
            Set<String> unknownAttributesForType = seenUnknownAttributesByType.computeIfAbsent(type, key -> new HashSet<>());
            unknownAttributesForType.addAll(unknownAttributes);
        }
    }

    public static void checkHasExactlyOneAttribute(EconomicMap<String, Object> map, String type, Collection<String> alternativeAttributes) {
        boolean attributeFound = false;
        for (String key : map.getKeys()) {
            if (alternativeAttributes.contains(key)) {
                if (attributeFound) {
                    String message = "Exactly one of [" + String.join(", ", alternativeAttributes) + "] must be set in " + type;
                    throw new JsonParserException(message);
                }
                attributeFound = true;
            }
        }
        if (!attributeFound) {
            String message = "Exactly one of [" + String.join(", ", alternativeAttributes) + "] must be set in " + type;
            throw new JsonParserException(message);
        }
    }

    /**
     * Used to warn about schema errors in configuration files. Should never be used if the type is
     * missing.
     *
     * @param message message to be displayed.
     */
    protected void warnOrFailOnSchemaError(String message) {
        if (checkOption(ConfigurationParserOption.STRICT_CONFIGURATION)) {
            failOnSchemaError(message);
        } else {
            LogUtils.warning(message);
        }
    }

    protected void checkAttributes(EconomicMap<String, Object> map, String type, Collection<String> requiredAttrs) {
        checkAttributes(map, type, requiredAttrs, Collections.emptyList());
    }

    public static String asString(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        throw new JsonParserException("Invalid string value \"" + value + "\".");
    }

    protected static String asString(Object value, String propertyName) {
        if (value instanceof String) {
            return (String) value;
        }
        throw new JsonParserException("Invalid string value \"" + value + "\" for element '" + propertyName + "'");
    }

    protected static String asNullableString(Object value, String propertyName) {
        return (value == null) ? null : asString(value, propertyName);
    }

    protected static boolean asBoolean(Object value, String propertyName) {
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        throw new JsonParserException("Invalid boolean value '" + value + "' for element '" + propertyName + "'");
    }

    protected static long asLong(Object value, String propertyName) {
        if (value instanceof Long) {
            return (long) value;
        }
        if (value instanceof Integer) {
            return (int) value;
        }
        throw new JsonParserException("Invalid long value '" + value + "' for element '" + propertyName + "'");
    }

    protected static JsonParserException failOnSchemaError(String message) {
        throw new JsonParserException(message);
    }

    protected record TypeDescriptorWithOrigin(ConfigurationTypeDescriptor typeDescriptor, boolean definedAsType) {
    }

    protected static Optional<TypeDescriptorWithOrigin> parseName(EconomicMap<String, Object> data, boolean treatAllNameEntriesAsType) {
        Object name = data.get(NAME_KEY);
        if (name != null) {
            NamedConfigurationTypeDescriptor typeDescriptor = NamedConfigurationTypeDescriptor.fromJSONName(asString(name));
            return Optional.of(new TypeDescriptorWithOrigin(typeDescriptor, treatAllNameEntriesAsType));
        } else {
            throw failOnSchemaError("must have type or name specified for an element");
        }
    }

    protected Optional<ConfigurationTypeDescriptor> parseTypeContents(Object typeObject) {
        if (typeObject instanceof String stringValue) {
            return Optional.of(NamedConfigurationTypeDescriptor.fromJSONName(stringValue));
        } else {
            EconomicMap<String, Object> type = asMap(typeObject, "type descriptor should be a string or object");
            if (type.containsKey(PROXY_KEY)) {
                checkHasExactlyOneAttribute(type, "type descriptor object", Set.of(PROXY_KEY));
                return Optional.of(getProxyDescriptor(type.get(PROXY_KEY)));
            } else if (type.containsKey(LAMBDA_KEY)) {
                return Optional.of(getLambdaDescriptor(type.get(LAMBDA_KEY)));
            }
            /*
             * We return if we find a future version of a type descriptor (as a JSON object) instead
             * of failing parsing.
             */
            // TODO warn (GR-65606)
            return Optional.empty();
        }
    }

    private static ProxyConfigurationTypeDescriptor getProxyDescriptor(Object proxyObject) {
        List<Object> proxyInterfaces = asList(proxyObject, "proxy interface content should be an interface list");
        List<String> proxyInterfaceNames = proxyInterfaces.stream().map(obj -> asString(obj, "proxy")).toList();
        return ProxyConfigurationTypeDescriptor.fromInterfaceTypeNames(proxyInterfaceNames);
    }

    private LambdaConfigurationTypeDescriptor getLambdaDescriptor(Object lambdaObject) {
        EconomicMap<String, Object> lambda = asMap(lambdaObject, "lambda type descriptor should be an object");
        checkAttributes(lambda, "lambda descriptor object", List.of(DECLARING_CLASS_KEY, INTERFACES_KEY), List.of(DECLARING_METHOD_KEY));
        Optional<ConfigurationTypeDescriptor> declaringType = parseTypeContents(lambda.get(DECLARING_CLASS_KEY));
        if (declaringType.isEmpty()) {
            throw new JsonParserException("Could not parse lambda declaring type");
        }
        ConfigurationMethodDescriptor method = null;
        if (lambda.containsKey(DECLARING_METHOD_KEY)) {
            EconomicMap<String, Object> methodObject = asMap(lambda.get(DECLARING_METHOD_KEY), "lambda declaring method descriptor should be an object");
            method = parseMethod(methodObject);
        }
        List<?> interfaceNames = asList(lambda.get(INTERFACES_KEY), "lambda implemented interfaces must be specified");
        if (interfaceNames.isEmpty()) {
            throw new JsonParserException("Lambda interfaces must not be empty");
        }
        List<NamedConfigurationTypeDescriptor> interfaces = interfaceNames.stream().map(s -> NamedConfigurationTypeDescriptor.fromJSONName(asString(s))).toList();
        return new LambdaConfigurationTypeDescriptor(declaringType.get(), method, interfaces);
    }

    public record ConfigurationMethodDescriptor(String name, List<NamedConfigurationTypeDescriptor> parameterTypes) implements JsonPrintable, Comparable<ConfigurationMethodDescriptor> {
        @Override
        public int compareTo(ConfigurationMethodDescriptor other) {
            return Comparator.comparing(ConfigurationMethodDescriptor::name)
                            .thenComparing((a, b) -> Arrays.compare(a.parameterTypes.toArray(ConfigurationTypeDescriptor[]::new), b.parameterTypes.toArray(ConfigurationTypeDescriptor[]::new)))
                            .compare(this, other);
        }

        @Override
        public void printJson(JsonWriter writer) throws IOException {
            writer.appendObjectStart();
            writer.quote(NAME_KEY).appendFieldSeparator().quote(name);
            if (parameterTypes != null) {
                writer.appendSeparator().quote(PARAMETER_TYPES_KEY).appendFieldSeparator();
                JsonPrinter.printCollection(writer, parameterTypes, ConfigurationTypeDescriptor::compareTo, ConfigurationTypeDescriptor::printJson);
            }
        }
    }

    protected ConfigurationMethodDescriptor parseMethod(EconomicMap<String, Object> methodJson) {
        checkAttributes(methodJson, "method descriptor", List.of(NAME_KEY), List.of(PARAMETER_TYPES_KEY));
        String name = asString(methodJson.get(NAME_KEY));
        List<NamedConfigurationTypeDescriptor> parameterTypes = null;
        if (methodJson.containsKey(PARAMETER_TYPES_KEY)) {
            List<?> parameterTypesStrings = asList(methodJson.get(PARAMETER_TYPES_KEY), "parameter types list");
            parameterTypes = parameterTypesStrings.stream().map(s -> NamedConfigurationTypeDescriptor.fromJSONName(asString(s))).toList();
        }
        return new ConfigurationMethodDescriptor(name, parameterTypes);
    }
}
